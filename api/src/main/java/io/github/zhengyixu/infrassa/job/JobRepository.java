package io.github.zhengyixu.infrassa.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private static final String COLUMNS = "id, environment_id, type, status, phase, attempt, created_at, updated_at";

    private static final RowMapper<Job> MAPPER = JobRepository::map;

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a PENDING job. Fails with DuplicateKeyException if the environment has an active job. */
    public Job insertPending(UUID environmentId, JobType type) {
        return jdbc.sql("""
                        INSERT INTO jobs (environment_id, type, status)
                        VALUES (:environmentId, :type, 'PENDING')
                        RETURNING %s""".formatted(COLUMNS))
                .param("environmentId", environmentId)
                .param("type", type.name())
                .query(MAPPER)
                .single();
    }

    public Optional<Job> findLatestForEnvironment(UUID environmentId) {
        return jdbc.sql("""
                        SELECT %s
                          FROM jobs
                         WHERE environment_id = :environmentId
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1""".formatted(COLUMNS))
                .param("environmentId", environmentId)
                .query(MAPPER)
                .optional();
    }

    // ---------------------------------------------------------------------------------------------
    // Leases (ADR 0001). Every timestamp comes from the database's now(), never from the JVM clock,
    // so API instances with skewed clocks still agree on whether a lease has expired.
    //
    // Ownership rule: a worker owns a job while lease_version equals the version it claimed.
    // Only a claim and the recovery sweep increment it. Every worker update is fenced on it and
    // reports whether exactly one row changed; false means "you lost the job, stop".
    // ---------------------------------------------------------------------------------------------

    /**
     * Claims the oldest PENDING job, or returns empty if there is none (or all are being claimed).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}: concurrent claimers skip rows another transaction has
     * locked instead of waiting for them, so N workers claim N different jobs without blocking.
     * The row lock lasts only for this statement's transaction; the lease is what keeps
     * ownership afterwards.
     */
    public Optional<ClaimedJob> claimNext(String workerId, Duration leaseDuration) {
        return jdbc.sql("""
                        UPDATE jobs
                           SET status = 'RUNNING',
                               phase = 'PLANNING',
                               claimed_by = :workerId,
                               lease_version = lease_version + 1,
                               lease_expires_at = now() + :leaseMillis * interval '1 millisecond',
                               attempt = attempt + 1,
                               last_error = NULL,
                               started_at = coalesce(started_at, now()),
                               updated_at = now()
                         WHERE id = (SELECT id
                                       FROM jobs
                                      WHERE status = 'PENDING'
                                      ORDER BY created_at, id
                                      LIMIT 1
                                        FOR UPDATE SKIP LOCKED)
                        RETURNING %s, lease_version""".formatted(COLUMNS))
                .param("workerId", workerId)
                .param("leaseMillis", leaseDuration.toMillis())
                .query((rs, rowNum) -> new ClaimedJob(map(rs, rowNum), lease(rs)))
                .optional();
    }

    /** Heartbeat: extends the lease. False if the lease was lost. */
    public boolean renewLease(JobLease lease, Duration leaseDuration) {
        return fencedUpdate(
                lease,
                "lease_expires_at = now() + :leaseMillis * interval '1 millisecond'",
                Map.of("leaseMillis", leaseDuration.toMillis()));
    }

    /** Records the phase the job is entering, and renews the lease. False if the lease was lost. */
    public boolean enterPhase(JobLease lease, JobPhase phase, Duration leaseDuration) {
        return fencedUpdate(
                lease,
                "phase = :phase, lease_expires_at = now() + :leaseMillis * interval '1 millisecond'",
                Map.of("phase", phase.name(), "leaseMillis", leaseDuration.toMillis()));
    }

    /**
     * Ends a job with SUCCEEDED or FAILED. Keeps phase and claimed_by to show where and by whom
     * it ended. False if the lease was lost (the result is then not recorded).
     */
    public boolean finish(JobLease lease, JobStatus status, String lastError) {
        if (status != JobStatus.SUCCEEDED && status != JobStatus.FAILED) {
            throw new IllegalArgumentException("A worker can only finish a job as SUCCEEDED or FAILED: " + status);
        }
        var params = new HashMap<String, Object>();
        params.put("status", status.name());
        params.put("lastError", lastError); // Map.of() rejects null values
        return fencedUpdate(
                lease,
                "status = :status, last_error = :lastError, lease_expires_at = NULL, finished_at = now()",
                params);
    }

    /** RUNNING jobs whose lease has expired, oldest expiry first. */
    public List<ExpiredJob> findExpired(int limit) {
        return jdbc.sql("""
                        SELECT id, environment_id, type, phase, attempt, lease_version
                          FROM jobs
                         WHERE status = 'RUNNING'
                           AND lease_expires_at < now()
                         ORDER BY lease_expires_at
                         LIMIT :limit""")
                .param("limit", limit)
                .query((rs, rowNum) -> new ExpiredJob(
                        rs.getObject("environment_id", UUID.class),
                        JobType.valueOf(rs.getString("type")),
                        JobPhase.valueOf(rs.getString("phase")),
                        rs.getInt("attempt"),
                        lease(rs)))
                .list();
    }

    /**
     * Takes an expired job away from its worker: back to PENDING (another worker claims it), or to
     * INTERRUPTED / FAILED (a human decides).
     *
     * <p>Increments lease_version, which fences off the old worker: if it was only paused (a long
     * GC, a network partition) and wakes up, its next heartbeat or update matches zero rows. The
     * WHERE clause re-checks expiry, so a heartbeat that landed after {@link #findExpired} wins
     * and this returns false. Several API instances can sweep at once: only one update succeeds.
     */
    public boolean recoverExpired(JobLease lease, JobStatus status, String reason) {
        if (status != JobStatus.PENDING && status != JobStatus.INTERRUPTED && status != JobStatus.FAILED) {
            throw new IllegalArgumentException(
                    "An expired job can only become PENDING, INTERRUPTED or FAILED: " + status);
        }
        boolean requeue = status == JobStatus.PENDING;
        return jdbc.sql("""
                        UPDATE jobs
                           SET status = :status,
                               lease_version = lease_version + 1,
                               lease_expires_at = NULL,
                               phase = CASE WHEN :requeue THEN NULL ELSE phase END,
                               claimed_by = CASE WHEN :requeue THEN NULL ELSE claimed_by END,
                               finished_at = CASE WHEN :requeue THEN NULL ELSE now() END,
                               last_error = :reason,
                               updated_at = now()
                         WHERE id = :id
                           AND lease_version = :version
                           AND status = 'RUNNING'
                           AND lease_expires_at < now()""")
                        .param("status", status.name())
                        .param("requeue", requeue)
                        .param("reason", reason)
                        .param("id", lease.jobId())
                        .param("version", lease.version())
                        .update()
                == 1;
    }

    /**
     * {@code UPDATE jobs SET <assignments> WHERE <this lease still holds>}. The assignments are
     * constants from this class, never user input.
     */
    private boolean fencedUpdate(JobLease lease, String assignments, Map<String, ?> params) {
        return jdbc.sql("""
                        UPDATE jobs
                           SET %s,
                               updated_at = now()
                         WHERE id = :id
                           AND lease_version = :version
                           AND status = 'RUNNING'""".formatted(assignments))
                        .params(params)
                        .param("id", lease.jobId())
                        .param("version", lease.version())
                        .update()
                == 1;
    }

    private static JobLease lease(ResultSet rs) throws SQLException {
        return new JobLease(rs.getObject("id", UUID.class), rs.getLong("lease_version"));
    }

    private static Job map(ResultSet rs, int rowNum) throws SQLException {
        String phase = rs.getString("phase");
        return new Job(
                rs.getObject("id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                JobType.valueOf(rs.getString("type")),
                JobStatus.valueOf(rs.getString("status")),
                phase == null ? null : JobPhase.valueOf(phase),
                rs.getInt("attempt"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}

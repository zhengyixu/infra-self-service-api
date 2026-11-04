package io.github.zhengyixu.infrassa.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

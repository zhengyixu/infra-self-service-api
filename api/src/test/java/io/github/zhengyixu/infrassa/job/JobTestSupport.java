package io.github.zhengyixu.infrassa.job;

import io.github.zhengyixu.infrassa.environment.CreateEnvironmentRequest;
import io.github.zhengyixu.infrassa.environment.EnvironmentService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Database helpers for job tests. Integration tests share one database, so names are unique. */
public record JobTestSupport(JdbcClient jdbc, EnvironmentService environments) {

    /** Park jobs left by other tests, so claims and sweeps only see the current test's jobs. */
    public void parkOtherJobs() {
        jdbc.sql("""
                        UPDATE jobs
                           SET status = 'FAILED', lease_expires_at = NULL, last_error = 'parked by test'
                         WHERE status IN ('PENDING', 'RUNNING')""").update();
    }

    /** Requests an environment through the real service; returns its PENDING job's ID. */
    public UUID newPendingJob(String namePrefix) {
        String name = namePrefix + UUID.randomUUID().toString().substring(0, 8);
        return environments
                .request(new CreateEnvironmentRequest(name, null, null))
                .currentJob()
                .orElseThrow()
                .id();
    }

    public void expireLease(UUID jobId) {
        jdbc.sql("UPDATE jobs SET lease_expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", jobId)
                .update();
    }

    /** One column of a job as text; null-safe (unlike query(String.class).single()). */
    public String jobColumn(UUID jobId, String column) {
        return jdbc.sql("SELECT %s::text FROM jobs WHERE id = :id".formatted(column))
                .param("id", jobId)
                .query((rs, rowNum) -> rs.getString(1))
                .list()
                .getFirst();
    }

    public String environmentStatusOfJob(UUID jobId) {
        return jdbc.sql("SELECT e.status FROM environments e JOIN jobs j ON j.environment_id = e.id WHERE j.id = :id")
                .param("id", jobId)
                .query(String.class)
                .single();
    }

    /** Audit event types of a job and its environment, oldest first. */
    public List<String> auditTrail(UUID jobId) {
        return jdbc.sql("""
                        SELECT a.aggregate_type || ':' || a.event_type
                          FROM audit_events a
                          JOIN jobs j ON a.aggregate_id IN (j.id, j.environment_id)
                         WHERE j.id = :id
                         ORDER BY a.id""").param("id", jobId).query(String.class).list();
    }
}

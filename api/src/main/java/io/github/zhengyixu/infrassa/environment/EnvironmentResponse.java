package io.github.zhengyixu.infrassa.environment;

import io.github.zhengyixu.infrassa.job.Job;
import io.github.zhengyixu.infrassa.job.JobPhase;
import io.github.zhengyixu.infrassa.job.JobStatus;
import io.github.zhengyixu.infrassa.job.JobType;
import java.time.Instant;
import java.util.UUID;

/** Response body for an environment and its most recent job. */
public record EnvironmentResponse(
        UUID id,
        String name,
        String instanceType,
        int instanceCount,
        EnvironmentStatus status,
        Instant createdAt,
        Instant updatedAt,
        JobSummary currentJob) {

    public record JobSummary(UUID id, JobType type, JobStatus status, JobPhase phase, int attempt) {}

    static EnvironmentResponse from(EnvironmentDetails details) {
        Environment env = details.environment();
        return new EnvironmentResponse(
                env.id(),
                env.name(),
                env.instanceType(),
                env.instanceCount(),
                env.status(),
                env.createdAt(),
                env.updatedAt(),
                details.currentJob().map(EnvironmentResponse::summarize).orElse(null));
    }

    private static JobSummary summarize(Job job) {
        return new JobSummary(job.id(), job.type(), job.status(), job.phase(), job.attempt());
    }
}

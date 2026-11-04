package io.github.zhengyixu.infrassa.job;

import java.time.Instant;
import java.util.UUID;

/**
 * A provisioning job as stored in the {@code jobs} table. Lease columns (claimed_by,
 * lease_expires_at) are read by the job engine, not by the API, so they are not mapped here.
 *
 * @param phase null until the job is first claimed
 */
public record Job(
        UUID id,
        UUID environmentId,
        JobType type,
        JobStatus status,
        JobPhase phase,
        int attempt,
        Instant createdAt,
        Instant updatedAt) {}

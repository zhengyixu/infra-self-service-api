package io.github.zhengyixu.infrassa.job;

import java.util.UUID;

/**
 * A RUNNING job whose lease expired: its worker crashed, hung, or lost the database. Found by the
 * recovery sweep, which decides from {@code phase} what happens next (ADR 0001).
 */
public record ExpiredJob(UUID environmentId, JobType type, JobPhase phase, int attempt, JobLease lease) {}

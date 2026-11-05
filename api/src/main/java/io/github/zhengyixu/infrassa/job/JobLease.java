package io.github.zhengyixu.infrassa.job;

import java.util.UUID;

/**
 * A worker's claim on a job: the fencing token it must present on every update (ADR 0001).
 *
 * <p>{@code version} is the value of {@code jobs.lease_version} at claim time. Only a new claim or
 * the recovery sweep increments it, so a worker whose update matches zero rows knows that someone
 * else owns the job now and must stop.
 */
public record JobLease(UUID jobId, long version) {}

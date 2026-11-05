package io.github.zhengyixu.infrassa.provisioning;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code infrassa.jobs.*} settings (ADR 0001). Bound once at startup, immutable afterwards.
 * .NET analogy: {@code IOptions<JobEngineOptions>} bound from appsettings, with validation in the
 * constructor instead of {@code ValidateOnStart()}.
 *
 * @param enabled false runs the API without background workers (tests, a read-only replica)
 * @param leaseDuration how long a claim stays valid without a heartbeat
 * @param heartbeatInterval how often running jobs renew their lease
 * @param pollInterval how often idle workers look for PENDING jobs
 * @param sweepInterval how often expired leases are recovered (also runs once at startup)
 * @param maxConcurrentJobs jobs this instance runs at the same time
 * @param maxPlanningAttempts claims before a job that keeps expiring in PLANNING becomes FAILED
 * @param shutdownGracePeriod how long stopping jobs get to end their child processes on shutdown
 */
@ConfigurationProperties("infrassa.jobs")
public record JobEngineProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue("20s") Duration heartbeatInterval,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("60s") Duration sweepInterval,
        @DefaultValue("2") int maxConcurrentJobs,
        @DefaultValue("3") int maxPlanningAttempts,
        @DefaultValue("30s") Duration shutdownGracePeriod) {

    public JobEngineProperties {
        // One missed heartbeat (a slow query, a GC pause) must not cost the lease.
        if (heartbeatInterval.multipliedBy(2).compareTo(leaseDuration) > 0) {
            throw new IllegalArgumentException(
                    "infrassa.jobs.heartbeat-interval (%s) must be at most half of lease-duration (%s)"
                            .formatted(heartbeatInterval, leaseDuration));
        }
        if (maxConcurrentJobs < 1 || maxPlanningAttempts < 1) {
            throw new IllegalArgumentException(
                    "infrassa.jobs.max-concurrent-jobs and max-planning-attempts must be >= 1");
        }
    }
}

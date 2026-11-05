package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.job.JobPhase;

/** Callback a runner uses to report progress. Implemented by the engine, fenced by the lease. */
public interface JobProgress {

    /**
     * Records that the job is entering {@code phase} (and renews the lease).
     *
     * @throws LeaseLostException if this worker no longer owns the job
     */
    void enterPhase(JobPhase phase);
}

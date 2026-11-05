package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.job.JobLease;

/**
 * A fenced update matched no row: the recovery sweep or another worker owns the job now. The
 * worker must stop without writing anything else about the job.
 */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(JobLease lease) {
        super("Lease lost for job %s (version %d)".formatted(lease.jobId(), lease.version()));
    }
}

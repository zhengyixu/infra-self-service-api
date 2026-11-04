package io.github.zhengyixu.infrassa.job;

/** Lifecycle of a job. See docs/adr/0001-job-execution-model.md. */
public enum JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** The lease expired after infrastructure may have changed; a user resumes or retries. */
    INTERRUPTED
}

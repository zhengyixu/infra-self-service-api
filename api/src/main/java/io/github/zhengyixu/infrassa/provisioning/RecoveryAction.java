package io.github.zhengyixu.infrassa.provisioning;

/** What the recovery sweep does with a job whose lease expired. */
public enum RecoveryAction {
    /** Back to PENDING: another worker claims it and starts again from PLANNING. */
    REQUEUE,
    /** INTERRUPTED: infrastructure may be half-changed, a human resumes or retries. */
    INTERRUPT,
    /** FAILED: retrying automatically has not helped. */
    FAIL
}

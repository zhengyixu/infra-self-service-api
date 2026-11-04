package io.github.zhengyixu.infrassa.job;

/** Step a RUNNING job is in. Only PLANNING is safe to retry automatically. */
public enum JobPhase {
    PLANNING,
    APPLYING,
    CONFIGURING
}

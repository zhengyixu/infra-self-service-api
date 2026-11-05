package io.github.zhengyixu.infrassa.job;

/** A job this worker just claimed, with the lease it must present on every later update. */
public record ClaimedJob(Job job, JobLease lease) {}

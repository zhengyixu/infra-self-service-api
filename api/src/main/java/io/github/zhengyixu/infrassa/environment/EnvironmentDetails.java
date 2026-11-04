package io.github.zhengyixu.infrassa.environment;

import io.github.zhengyixu.infrassa.job.Job;
import java.util.Optional;

/** An environment together with its most recent job, as returned by the service. */
public record EnvironmentDetails(Environment environment, Optional<Job> currentJob) {}

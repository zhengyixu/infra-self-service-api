package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.environment.Environment;
import io.github.zhengyixu.infrassa.job.Job;
import io.github.zhengyixu.infrassa.job.JobType;
import java.util.UUID;

/**
 * Everything a runner needs to do one job, as plain values. Runners never see the job table or the
 * lease: those stay with the engine.
 *
 * @param attempt 1 for the first claim, higher when the job was retried
 */
public record ProvisioningRequest(
        UUID jobId,
        JobType type,
        int attempt,
        UUID environmentId,
        String environmentName,
        String instanceType,
        int instanceCount) {

    public static ProvisioningRequest of(Job job, Environment environment) {
        return new ProvisioningRequest(
                job.id(),
                job.type(),
                job.attempt(),
                environment.id(),
                environment.name(),
                environment.instanceType(),
                environment.instanceCount());
    }
}

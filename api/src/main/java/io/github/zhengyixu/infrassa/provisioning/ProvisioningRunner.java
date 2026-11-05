package io.github.zhengyixu.infrassa.provisioning;

/**
 * Executes one claimed job (ADR 0001). The job engine owns claiming, leases, heartbeats and
 * recording the outcome; a runner only does the work and reports which phase it is in.
 *
 * <p>This is the seam for other execution models: an {@code SqsDispatchRunner} or an
 * {@code AnsibleAutomationPlatformRunner} implements the same interface, and controllers, the job
 * table and the engine do not change. (A "delegated" outcome for runners whose work finishes
 * elsewhere will be added with the first runner that needs it.)
 *
 * <p>.NET analogy: an {@code IJobHandler} resolved from DI by a {@code BackgroundService}.
 */
public interface ProvisioningRunner {

    /**
     * Runs the job on the calling (virtual) thread and blocks until it finishes.
     *
     * <p>Call {@link JobProgress#enterPhase} before each phase. It checks the fencing token and
     * throws {@link LeaseLostException} if this worker no longer owns the job; let it propagate.
     *
     * @return the outcome to record; a failure the runner understands (a non-zero exit code) is a
     *     {@link RunOutcome.Failed}, not an exception
     * @throws InterruptedException when the engine stops this job (lease lost, or shutdown). Stop
     *     child processes promptly, then rethrow. The engine records nothing in that case: the
     *     recovery sweep decides from the phase what happens to the job.
     */
    RunOutcome run(ProvisioningRequest request, JobProgress progress) throws InterruptedException;
}

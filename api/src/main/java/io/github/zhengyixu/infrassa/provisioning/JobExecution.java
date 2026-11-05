package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.environment.Environment;
import io.github.zhengyixu.infrassa.environment.EnvironmentRepository;
import io.github.zhengyixu.infrassa.job.ClaimedJob;
import io.github.zhengyixu.infrassa.job.JobLease;
import io.github.zhengyixu.infrassa.job.JobPhase;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One claimed job running on its own virtual thread: hands it to the runner, records the outcome,
 * and can be stopped from outside ({@link #abort}) when the heartbeat loses the lease or the
 * application shuts down.
 *
 * <p>Rule: a job that has been told to stop records nothing. Its lease runs out and the recovery
 * sweep decides from the phase what happens next. That keeps a single place (the sweep) deciding
 * about unfinished work, whatever the reason it is unfinished.
 */
class JobExecution implements JobProgress {

    private static final Logger log = LoggerFactory.getLogger(JobExecution.class);

    private final ClaimedJob claimed;
    private final JobTransitions transitions;
    private final EnvironmentRepository environments;
    private final ProvisioningRunner runner;
    private final Duration leaseDuration;

    // Written by the heartbeat or shutdown thread, read by the job's thread.
    private volatile Thread thread;
    private volatile String abortReason;

    JobExecution(
            ClaimedJob claimed,
            JobTransitions transitions,
            EnvironmentRepository environments,
            ProvisioningRunner runner,
            Duration leaseDuration) {
        this.claimed = claimed;
        this.transitions = transitions;
        this.environments = environments;
        this.runner = runner;
        this.leaseDuration = leaseDuration;
    }

    JobLease lease() {
        return claimed.lease();
    }

    /**
     * Stops the job: interrupts its thread, which makes the runner terminate its child process.
     * Safe to call from any thread, before or while the job runs.
     */
    void abort(String reason) {
        abortReason = reason;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void enterPhase(JobPhase phase) {
        transitions.enterPhase(claimed.lease(), phase, leaseDuration);
        log.info("Job entered phase {}", phase);
    }

    void run() {
        thread = Thread.currentThread();
        if (abortReason != null) { // aborted before the thread started
            return;
        }
        // MDC: these keys appear on every log line this thread writes (ECS JSON fields).
        MDC.put("jobId", claimed.job().id().toString());
        MDC.put("environmentId", claimed.job().environmentId().toString());
        try {
            Environment environment = environments
                    .findById(claimed.job().environmentId())
                    .orElseThrow(() -> new IllegalStateException("Job has no environment"));
            log.info(
                    "Job started: type={} attempt={} environment={}",
                    claimed.job().type(),
                    claimed.job().attempt(),
                    environment.name());

            RunOutcome outcome = runner.run(ProvisioningRequest.of(claimed.job(), environment), this);

            if (abortReason != null) {
                log.warn("Job was stopped ({}); its outcome is not recorded, recovery decides", abortReason);
                return;
            }
            transitions.finish(claimed, outcome);
            log.info("Job finished: {}", outcome);
        } catch (InterruptedException e) {
            log.warn("Job stopped ({}); recovery decides what happens next", abortReason);
            Thread.currentThread().interrupt();
        } catch (LeaseLostException e) {
            log.warn("Job abandoned: {}", e.getMessage());
        } catch (RuntimeException e) {
            if (abortReason != null) {
                log.warn("Job stopped ({}) with {}; recovery decides", abortReason, e.toString());
                return;
            }
            log.error("Job failed with an unexpected error", e);
            recordUnexpectedFailure(e);
        } finally {
            MDC.clear();
        }
    }

    private void recordUnexpectedFailure(RuntimeException e) {
        try {
            transitions.finish(claimed, new RunOutcome.Failed("Unexpected error: " + e));
        } catch (LeaseLostException lost) {
            log.warn("Could not record the failure: {}", lost.getMessage());
        } catch (RuntimeException dbError) {
            // Database unavailable: the lease expires and recovery classifies the job.
            log.error("Could not record the failure; recovery will handle the job", dbError);
        }
    }
}

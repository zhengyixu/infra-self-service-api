package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.job.ExpiredJob;
import io.github.zhengyixu.infrassa.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Finds RUNNING jobs whose lease expired and applies the {@link RecoveryPolicy} (ADR 0001).
 *
 * <p>Runs at startup and periodically on every instance. That is safe because each recovery is a
 * fenced update that re-checks expiry: when two instances sweep the same job, one update wins and
 * the other changes nothing. Expiry, not "the instance restarted", is the signal, so a starting
 * instance never takes a job away from another instance that is still heartbeating.
 */
@Component
public class RecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(RecoverySweep.class);

    /** Per run; anything beyond waits for the next run. */
    private static final int BATCH_SIZE = 100;

    private final JobRepository jobs;
    private final JobTransitions transitions;
    private final RecoveryPolicy policy;

    public RecoverySweep(JobRepository jobs, JobTransitions transitions, RecoveryPolicy policy) {
        this.jobs = jobs;
        this.transitions = transitions;
        this.policy = policy;
    }

    /** @return how many jobs were recovered */
    public int sweep() {
        int recovered = 0;
        for (ExpiredJob expired : jobs.findExpired(BATCH_SIZE)) {
            RecoveryAction action = policy.decide(expired.phase(), expired.attempt());
            if (transitions.recover(expired, action)) {
                recovered++;
                log.warn(
                        "Recovered job with expired lease: jobId={} phase={} attempt={} action={}",
                        expired.lease().jobId(),
                        expired.phase(),
                        expired.attempt(),
                        action);
            }
        }
        return recovered;
    }
}

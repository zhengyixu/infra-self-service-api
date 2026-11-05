package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.job.JobPhase;

/**
 * Decides what happens to a job whose lease expired (ADR 0001, "Recovery of expired leases").
 * A pure function of the job's phase and attempt count, so it is unit-tested without a database.
 *
 * @param maxPlanningAttempts claims allowed before a job that keeps expiring in PLANNING fails
 */
public record RecoveryPolicy(int maxPlanningAttempts) {

    /**
     * @param phase the phase the job was in when its lease expired
     * @param attempt how many times the job has been claimed so far (1 after the first claim)
     */
    public RecoveryAction decide(JobPhase phase, int attempt) {
        // TODO(you): implement the ADR 0001 recovery table.
        //  - PLANNING has not changed infrastructure, so REQUEUE it, unless it has already been
        //    claimed maxPlanningAttempts times: then FAIL (a plan that crashes the worker every
        //    time should not loop forever).
        //  - APPLYING and CONFIGURING may have changed infrastructure: INTERRUPT (a human decides).
        // Hint: a switch expression over the enum, with no default branch:
        //    return switch (phase) { case PLANNING -> ...; case APPLYING, CONFIGURING -> ...; };
        //  Without a default, adding a JobPhase value becomes a compile error here, which is what
        //  you want for a safety decision.
        // Then remove @Disabled from RecoveryPolicyTest and RecoverySweepIntegrationTest.
        //
        // Until then every expired job is interrupted: the safe default, since a human looks at
        // it and nothing is ever retried by mistake.
        return RecoveryAction.INTERRUPT;
    }
}

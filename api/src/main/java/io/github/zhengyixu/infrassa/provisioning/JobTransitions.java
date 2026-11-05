package io.github.zhengyixu.infrassa.provisioning;

import io.github.zhengyixu.infrassa.audit.AggregateType;
import io.github.zhengyixu.infrassa.audit.AuditEventRepository;
import io.github.zhengyixu.infrassa.environment.EnvironmentRepository;
import io.github.zhengyixu.infrassa.environment.EnvironmentStatus;
import io.github.zhengyixu.infrassa.job.ClaimedJob;
import io.github.zhengyixu.infrassa.job.ExpiredJob;
import io.github.zhengyixu.infrassa.job.JobLease;
import io.github.zhengyixu.infrassa.job.JobPhase;
import io.github.zhengyixu.infrassa.job.JobRepository;
import io.github.zhengyixu.infrassa.job.JobStatus;
import io.github.zhengyixu.infrassa.job.JobType;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every job state change the engine makes, each in its own short transaction together with the
 * matching environment status and audit events (ADR 0003): either all of them commit or none.
 *
 * <p>Heartbeats are the exception: they change no state anyone audits, and writing an event every
 * 20 seconds per job would flood the outbox.
 */
@Service
public class JobTransitions {

    private final JobRepository jobs;
    private final EnvironmentRepository environments;
    private final AuditEventRepository audit;

    public JobTransitions(JobRepository jobs, EnvironmentRepository environments, AuditEventRepository audit) {
        this.jobs = jobs;
        this.environments = environments;
        this.audit = audit;
    }

    @Transactional
    public Optional<ClaimedJob> claimNext(String workerId, Duration leaseDuration) {
        Optional<ClaimedJob> claimed = jobs.claimNext(workerId, leaseDuration);
        claimed.ifPresent(c -> audit.append(
                AggregateType.JOB,
                c.job().id(),
                "JOB_CLAIMED",
                Map.of(
                        "workerId", workerId,
                        "attempt", c.job().attempt(),
                        "leaseVersion", c.lease().version())));
        return claimed;
    }

    /** No transaction or audit: one fenced UPDATE. */
    public boolean renewLease(JobLease lease, Duration leaseDuration) {
        return jobs.renewLease(lease, leaseDuration);
    }

    /** @throws LeaseLostException if the lease no longer holds (nothing is written) */
    @Transactional
    public void enterPhase(JobLease lease, JobPhase phase, Duration leaseDuration) {
        if (!jobs.enterPhase(lease, phase, leaseDuration)) {
            throw new LeaseLostException(lease);
        }
        audit.append(AggregateType.JOB, lease.jobId(), "JOB_PHASE_STARTED", Map.of("phase", phase.name()));
    }

    /** @throws LeaseLostException if the lease no longer holds (nothing is written) */
    @Transactional
    public void finish(ClaimedJob claimed, RunOutcome outcome) {
        JobLease lease = claimed.lease();
        JobStatus status;
        String error;
        switch (outcome) {
            case RunOutcome.Succeeded() -> {
                status = JobStatus.SUCCEEDED;
                error = null;
            }
            case RunOutcome.Failed(String reason) -> {
                status = JobStatus.FAILED;
                error = reason;
            }
        }
        if (!jobs.finish(lease, status, error)) {
            throw new LeaseLostException(lease); // rolls back: nothing is recorded
        }
        var payload = new HashMap<String, Object>();
        payload.put("status", status.name());
        if (error != null) {
            payload.put("error", error);
        }
        audit.append(AggregateType.JOB, lease.jobId(), "JOB_" + status.name(), payload);
        changeEnvironmentStatus(claimed.job().environmentId(), claimed.job().type(), status, lease.jobId());
    }

    /**
     * Applies the recovery sweep's decision to an expired job.
     *
     * @return false if the job was not expired any more (a late heartbeat) or another instance
     *     recovered it first
     */
    @Transactional
    public boolean recover(ExpiredJob expired, RecoveryAction action) {
        JobStatus status = switch (action) {
            case REQUEUE -> JobStatus.PENDING;
            case INTERRUPT -> JobStatus.INTERRUPTED;
            case FAIL -> JobStatus.FAILED;
        };
        String reason = "Lease expired during %s (attempt %d)".formatted(expired.phase(), expired.attempt());
        if (!jobs.recoverExpired(expired.lease(), status, reason)) {
            return false;
        }
        String eventType = switch (action) {
            case REQUEUE -> "JOB_REQUEUED";
            case INTERRUPT -> "JOB_INTERRUPTED";
            case FAIL -> "JOB_FAILED";
        };
        audit.append(
                AggregateType.JOB,
                expired.lease().jobId(),
                eventType,
                Map.of("phase", expired.phase().name(), "attempt", expired.attempt(), "reason", reason));
        changeEnvironmentStatus(
                expired.environmentId(), expired.type(), status, expired.lease().jobId());
        return true;
    }

    private void changeEnvironmentStatus(UUID environmentId, JobType type, JobStatus jobStatus, UUID jobId) {
        environmentStatusAfter(type, jobStatus).ifPresent(status -> {
            environments.updateStatus(environmentId, status);
            audit.append(
                    AggregateType.ENVIRONMENT,
                    environmentId,
                    "ENVIRONMENT_STATUS_CHANGED",
                    Map.of("status", status.name(), "jobId", jobId.toString()));
        });
    }

    /**
     * The environment only changes when a job ends for good. An INTERRUPTED job leaves it in
     * PROVISIONING / DESTROYING: the work is unfinished, not failed, and the job says why.
     */
    static Optional<EnvironmentStatus> environmentStatusAfter(JobType type, JobStatus jobStatus) {
        return switch (jobStatus) {
            case SUCCEEDED ->
                Optional.of(
                        switch (type) {
                            case PROVISION -> EnvironmentStatus.READY;
                            case DESTROY -> EnvironmentStatus.DESTROYED;
                        });
            case FAILED -> Optional.of(EnvironmentStatus.FAILED);
            case PENDING, RUNNING, INTERRUPTED -> Optional.empty();
        };
    }
}

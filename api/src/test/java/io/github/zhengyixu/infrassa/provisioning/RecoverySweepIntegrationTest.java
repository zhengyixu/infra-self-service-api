package io.github.zhengyixu.infrassa.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zhengyixu.infrassa.IntegrationTest;
import io.github.zhengyixu.infrassa.environment.EnvironmentService;
import io.github.zhengyixu.infrassa.job.JobLease;
import io.github.zhengyixu.infrassa.job.JobPhase;
import io.github.zhengyixu.infrassa.job.JobTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The sweep end to end: expired lease, policy decision, job + environment + audit in one commit. */
@IntegrationTest
class RecoverySweepIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    RecoverySweep sweep;

    @Autowired
    JobTransitions transitions;

    @Autowired
    EnvironmentService environments;

    @Autowired
    JdbcClient jdbc;

    JobTestSupport db;

    @BeforeEach
    void setUp() {
        db = new JobTestSupport(jdbc, environments);
        db.parkOtherJobs();
    }

    @Test
    void jobThatExpiredWhileApplyingIsInterruptedForAHuman() {
        UUID id = claimedJobExpiredIn(JobPhase.APPLYING);

        assertThat(sweep.sweep()).isEqualTo(1);

        assertThat(db.jobColumn(id, "status")).isEqualTo("INTERRUPTED");
        assertThat(db.jobColumn(id, "last_error")).isEqualTo("Lease expired during APPLYING (attempt 1)");
        assertThat(db.environmentStatusOfJob(id)).isEqualTo("PROVISIONING"); // unfinished, not failed
        assertThat(db.auditTrail(id)).endsWith("JOB:JOB_PHASE_STARTED", "JOB:JOB_INTERRUPTED");
        assertThat(sweep.sweep()).isZero(); // nothing left to do
    }

    @Test
    void liveJobsAreLeftAlone() {
        UUID id = db.newPendingJob("sweep-");
        transitions.claimNext("worker-a", LEASE);

        assertThat(sweep.sweep()).isZero();
        assertThat(db.jobColumn(id, "status")).isEqualTo("RUNNING");
    }

    @Test
    @Disabled("TODO(you): enable after implementing RecoveryPolicy.decide")
    void jobThatExpiredWhilePlanningIsRequeuedAndClaimedAgain() {
        UUID id = claimedJobExpiredIn(JobPhase.PLANNING);

        assertThat(sweep.sweep()).isEqualTo(1);

        assertThat(db.jobColumn(id, "status")).isEqualTo("PENDING");
        assertThat(db.jobColumn(id, "phase")).isNull();
        assertThat(db.auditTrail(id)).last().isEqualTo("JOB:JOB_REQUEUED");
        var reclaimed = transitions.claimNext("worker-b", LEASE).orElseThrow();
        assertThat(reclaimed.job().id()).isEqualTo(id);
        assertThat(reclaimed.job().attempt()).isEqualTo(2);
    }

    @Test
    @Disabled("TODO(you): enable after implementing RecoveryPolicy.decide")
    void jobThatKeepsExpiringWhilePlanningFailsAfterTheLastAttempt() {
        UUID id = claimedJobExpiredIn(JobPhase.PLANNING);
        for (int attempt = 2; attempt <= 3; attempt++) { // default max-planning-attempts = 3
            sweep.sweep();
            transitions.claimNext("worker-" + attempt, LEASE).orElseThrow();
            db.expireLease(id);
        }

        sweep.sweep();

        assertThat(db.jobColumn(id, "status")).isEqualTo("FAILED");
        assertThat(db.environmentStatusOfJob(id)).isEqualTo("FAILED");
        assertThat(db.auditTrail(id)).endsWith("JOB:JOB_FAILED", "ENVIRONMENT:ENVIRONMENT_STATUS_CHANGED");
    }

    private UUID claimedJobExpiredIn(JobPhase phase) {
        UUID id = db.newPendingJob("sweep-");
        JobLease lease = transitions.claimNext("worker-a", LEASE).orElseThrow().lease();
        if (phase != JobPhase.PLANNING) {
            transitions.enterPhase(lease, phase, LEASE);
        }
        db.expireLease(id);
        return id;
    }
}

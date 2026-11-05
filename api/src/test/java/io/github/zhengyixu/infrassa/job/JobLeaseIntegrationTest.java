package io.github.zhengyixu.infrassa.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zhengyixu.infrassa.IntegrationTest;
import io.github.zhengyixu.infrassa.environment.EnvironmentService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The lease guarantees of ADR 0001, checked against real PostgreSQL: SKIP LOCKED claims, fencing
 * tokens, and expiry. These rely on PostgreSQL's locking and row re-checks, so a mock would prove
 * nothing.
 */
@IntegrationTest
class JobLeaseIntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(60);

    @Autowired
    JobRepository jobs;

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
    void claimTakesTheOldestPendingJobAndStartsALease() {
        UUID first = db.newPendingJob("lease-");
        db.newPendingJob("lease-");

        ClaimedJob claimed = jobs.claimNext("worker-a", LEASE).orElseThrow();

        assertThat(claimed.job().id()).isEqualTo(first);
        assertThat(claimed.job().status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.job().phase()).isEqualTo(JobPhase.PLANNING);
        assertThat(claimed.job().attempt()).isEqualTo(1);
        assertThat(claimed.lease().version()).isEqualTo(1);
        assertThat(db.jobColumn(first, "claimed_by")).isEqualTo("worker-a");
        assertThat(secondsUntilExpiry(first)).isBetween(55.0, 60.0);
    }

    @Test
    void claimReturnsEmptyWhenNothingIsPending() {
        assertThat(jobs.claimNext("worker-a", LEASE)).isEmpty();
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        List<UUID> created =
                IntStream.range(0, 20).mapToObj(i -> db.newPendingJob("lease-")).toList();
        var claimedIds = new ConcurrentLinkedQueue<UUID>();
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int w = 0; w < 8; w++) {
                String worker = "worker-" + w;
                pool.submit(() -> {
                    start.await(); // all workers race for the same rows
                    Optional<ClaimedJob> claimed;
                    while ((claimed = jobs.claimNext(worker, LEASE)).isPresent()) {
                        claimedIds.add(claimed.get().job().id());
                    }
                    return null;
                });
            }
            start.countDown();
        } // close() waits for all tasks

        assertThat(claimedIds).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(created);
    }

    @Test
    void renewExtendsTheLeaseWhileTheTokenMatches() {
        UUID id = db.newPendingJob("lease-");
        JobLease lease =
                jobs.claimNext("worker-a", Duration.ofSeconds(5)).orElseThrow().lease();

        assertThat(jobs.renewLease(lease, LEASE)).isTrue();
        assertThat(secondsUntilExpiry(id)).isGreaterThan(55.0);
        assertThat(jobs.enterPhase(lease, JobPhase.APPLYING, LEASE)).isTrue();
        assertThat(db.jobColumn(id, "phase")).isEqualTo("APPLYING");
    }

    @Test
    void oldWorkerIsFencedOffOnceItsExpiredJobWasRecovered() {
        UUID id = db.newPendingJob("lease-");
        JobLease oldLease = jobs.claimNext("worker-a", LEASE).orElseThrow().lease();
        db.expireLease(id);

        assertThat(jobs.recoverExpired(oldLease, JobStatus.PENDING, "lease expired"))
                .isTrue();
        ClaimedJob reclaimed = jobs.claimNext("worker-b", LEASE).orElseThrow();

        // worker-a wakes up from a long pause: every update it tries is rejected.
        assertThat(jobs.renewLease(oldLease, LEASE)).isFalse();
        assertThat(jobs.enterPhase(oldLease, JobPhase.APPLYING, LEASE)).isFalse();
        assertThat(jobs.finish(oldLease, JobStatus.SUCCEEDED, null)).isFalse();

        assertThat(reclaimed.lease().version()).isEqualTo(oldLease.version() + 2); // recovery + claim
        assertThat(reclaimed.job().attempt()).isEqualTo(2);
        assertThat(db.jobColumn(id, "claimed_by")).isEqualTo("worker-b");
        assertThat(db.jobColumn(id, "phase")).isEqualTo("PLANNING");
    }

    @Test
    void recoveryLeavesALiveLeaseAlone() {
        db.newPendingJob("lease-");
        JobLease lease = jobs.claimNext("worker-a", LEASE).orElseThrow().lease();

        assertThat(jobs.findExpired(10)).isEmpty();
        assertThat(jobs.recoverExpired(lease, JobStatus.INTERRUPTED, "should not happen"))
                .isFalse();
    }

    @Test
    void aHeartbeatThatLandsBeforeRecoveryWins() {
        UUID id = db.newPendingJob("lease-");
        JobLease lease = jobs.claimNext("worker-a", LEASE).orElseThrow().lease();
        db.expireLease(id);
        assertThat(jobs.findExpired(10)).extracting(e -> e.lease().jobId()).containsExactly(id);

        // The worker was slow, not dead: its heartbeat arrives between the sweep's read and update.
        assertThat(jobs.renewLease(lease, LEASE)).isTrue();

        assertThat(jobs.recoverExpired(lease, JobStatus.INTERRUPTED, "lease expired"))
                .isFalse();
        assertThat(db.jobColumn(id, "status")).isEqualTo("RUNNING");
    }

    @Test
    void interruptedJobKeepsItsPhaseForTheHumanWhoResumesIt() {
        UUID id = db.newPendingJob("lease-");
        JobLease lease = jobs.claimNext("worker-a", LEASE).orElseThrow().lease();
        jobs.enterPhase(lease, JobPhase.APPLYING, LEASE);
        db.expireLease(id);

        ExpiredJob expired = jobs.findExpired(10).getFirst();
        assertThat(expired.phase()).isEqualTo(JobPhase.APPLYING);
        assertThat(jobs.recoverExpired(expired.lease(), JobStatus.INTERRUPTED, "lease expired while APPLYING"))
                .isTrue();

        assertThat(db.jobColumn(id, "status")).isEqualTo("INTERRUPTED");
        assertThat(db.jobColumn(id, "phase")).isEqualTo("APPLYING");
        assertThat(db.jobColumn(id, "last_error")).isEqualTo("lease expired while APPLYING");
        assertThat(jobs.claimNext("worker-b", LEASE)).isEmpty(); // not retried automatically
    }

    @Test
    void finishRecordsTheResultAndEndsTheLease() {
        UUID id = db.newPendingJob("lease-");
        JobLease lease = jobs.claimNext("worker-a", LEASE).orElseThrow().lease();

        assertThat(jobs.finish(lease, JobStatus.FAILED, "terraform plan failed"))
                .isTrue();

        assertThat(db.jobColumn(id, "status")).isEqualTo("FAILED");
        assertThat(db.jobColumn(id, "last_error")).isEqualTo("terraform plan failed");
        assertThat(db.jobColumn(id, "lease_expires_at")).isNull();
        assertThat(jobs.finish(lease, JobStatus.SUCCEEDED, null)).isFalse(); // only once
    }

    private double secondsUntilExpiry(UUID id) {
        return jdbc.sql("SELECT extract(epoch FROM lease_expires_at - now())::float8 FROM jobs WHERE id = :id")
                .param("id", id)
                .query(Double.class)
                .single();
    }
}

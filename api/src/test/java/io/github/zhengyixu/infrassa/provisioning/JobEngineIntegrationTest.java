package io.github.zhengyixu.infrassa.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.zhengyixu.infrassa.TestcontainersConfiguration;
import io.github.zhengyixu.infrassa.environment.CreateEnvironmentRequest;
import io.github.zhengyixu.infrassa.environment.EnvironmentService;
import io.github.zhengyixu.infrassa.job.JobPhase;
import io.github.zhengyixu.infrassa.job.JobTestSupport;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The engine running for real (polling, virtual threads, heartbeats, sweep) with a scripted runner
 * instead of Terraform. Short intervals keep each test to a few seconds.
 *
 * <p>Different properties from {@code @IntegrationTest}, so this class gets its own context and
 * its own PostgreSQL container: no other test's jobs are visible here.
 */
@SpringBootTest(
        properties = {
            "infrassa.jobs.poll-interval=100ms",
            "infrassa.jobs.heartbeat-interval=300ms",
            "infrassa.jobs.lease-duration=2s",
            "infrassa.jobs.sweep-interval=300ms",
            "infrassa.jobs.max-concurrent-jobs=4",
        })
@Import({TestcontainersConfiguration.class, JobEngineIntegrationTest.RunnerConfig.class})
class JobEngineIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    EnvironmentService environments;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ScriptedRunner runner;

    JobTestSupport db;

    @BeforeEach
    void setUp() {
        db = new JobTestSupport(jdbc, environments);
    }

    @Test
    void successfulRunMakesTheEnvironmentReady() {
        UUID job = request("ok-");

        await().atMost(TIMEOUT).until(() -> "SUCCEEDED".equals(db.jobColumn(job, "status")));

        assertThat(db.environmentStatusOfJob(job)).isEqualTo("READY");
        assertThat(db.jobColumn(job, "phase")).isEqualTo("CONFIGURING");
        assertThat(db.jobColumn(job, "finished_at")).isNotNull();
        assertThat(db.auditTrail(job))
                .containsExactly(
                        "ENVIRONMENT:ENVIRONMENT_REQUESTED",
                        "JOB:JOB_CREATED",
                        "JOB:JOB_CLAIMED",
                        "JOB:JOB_PHASE_STARTED", // PLANNING
                        "JOB:JOB_PHASE_STARTED", // APPLYING
                        "JOB:JOB_PHASE_STARTED", // CONFIGURING
                        "JOB:JOB_SUCCEEDED",
                        "ENVIRONMENT:ENVIRONMENT_STATUS_CHANGED");
    }

    @Test
    void failedRunRecordsTheReasonAndFailsTheEnvironment() {
        UUID job = request("fail-");

        await().atMost(TIMEOUT).until(() -> "FAILED".equals(db.jobColumn(job, "status")));

        assertThat(db.jobColumn(job, "last_error")).contains("boom");
        assertThat(db.jobColumn(job, "phase")).isEqualTo("PLANNING");
        assertThat(db.environmentStatusOfJob(job)).isEqualTo("FAILED");
    }

    @Test
    void unexpectedExceptionInTheRunnerFailsTheJob() {
        UUID job = request("bug-");

        await().atMost(TIMEOUT).until(() -> "FAILED".equals(db.jobColumn(job, "status")));

        assertThat(db.jobColumn(job, "last_error")).contains("IllegalStateException", "bug in runner");
    }

    @Test
    void workerThatLosesItsLeaseStopsAndTheSweepInterruptsTheJob() {
        String name = "hang-" + shortId();
        UUID job = request(name);
        await().atMost(TIMEOUT).until(() -> "APPLYING".equals(db.jobColumn(job, "phase")));

        // Another worker takes over (as after a long pause): the fencing token moves on.
        jdbc.sql("UPDATE jobs SET lease_version = lease_version + 1 WHERE id = :id")
                .param("id", job)
                .update();

        // The next heartbeat sees zero rows changed and interrupts the runner's thread.
        await().atMost(TIMEOUT).until(() -> runner.interrupted.contains(name));
        // Nobody renews the lease any more: it expires and the sweep interrupts the job.
        await().atMost(TIMEOUT).until(() -> "INTERRUPTED".equals(db.jobColumn(job, "status")));
        assertThat(db.environmentStatusOfJob(job)).isEqualTo("PROVISIONING");
    }

    private UUID request(String nameOrPrefix) {
        String name = nameOrPrefix.endsWith("-") ? nameOrPrefix + shortId() : nameOrPrefix;
        return environments
                .request(new CreateEnvironmentRequest(name, null, null))
                .currentJob()
                .orElseThrow()
                .id();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Behaves according to the environment name's prefix: ok-, fail-, bug-, hang-. */
    static class ScriptedRunner implements ProvisioningRunner {

        final Set<String> interrupted = ConcurrentHashMap.newKeySet();

        @Override
        public RunOutcome run(ProvisioningRequest request, JobProgress progress) throws InterruptedException {
            String name = request.environmentName();
            progress.enterPhase(JobPhase.PLANNING);
            if (name.startsWith("fail-")) {
                return new RunOutcome.Failed("terraform plan failed (exit 1): boom");
            }
            if (name.startsWith("bug-")) {
                throw new IllegalStateException("bug in runner");
            }
            progress.enterPhase(JobPhase.APPLYING);
            if (name.startsWith("hang-")) {
                try {
                    Thread.sleep(Duration.ofMinutes(5)); // like a child process that never exits
                } catch (InterruptedException e) {
                    interrupted.add(name);
                    throw e;
                }
            }
            progress.enterPhase(JobPhase.CONFIGURING);
            return new RunOutcome.Succeeded();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RunnerConfig {

        /** @Primary: wins over the real InProcessTerraformAnsibleRunner when the engine injects one. */
        @Bean
        @Primary
        ScriptedRunner scriptedRunner() {
            return new ScriptedRunner();
        }
    }
}

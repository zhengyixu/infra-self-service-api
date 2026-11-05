package io.github.zhengyixu.infrassa.provisioning.inprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zhengyixu.infrassa.job.JobPhase;
import io.github.zhengyixu.infrassa.job.JobType;
import io.github.zhengyixu.infrassa.provisioning.ProvisioningRequest;
import io.github.zhengyixu.infrassa.provisioning.RunOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which commands run, in which order, with which arguments, and how failures map to outcomes.
 * No real tools: a fake executor records the commands (a hand-written fake reads better than
 * Mockito here, since every test inspects the recorded list).
 */
class InProcessTerraformAnsibleRunnerTest {

    private static final UUID JOB_ID = UUID.fromString("01a1a99f-0000-7000-8000-000000000001");
    private static final UUID ENV_ID = UUID.fromString("01a1a99f-0000-7000-8000-000000000002");

    @TempDir
    Path workDir;

    private final Path repoRoot = Path.of("/repo");
    private final FakeExecutor executor = new FakeExecutor();
    private final List<JobPhase> phases = new ArrayList<>();
    private InProcessTerraformAnsibleRunner runner;

    @BeforeEach
    void setUp() {
        var properties = new InProcessRunnerProperties(
                "floci", repoRoot.toString(), workDir.toString(), "python3", "ansible-playbook", Duration.ofSeconds(1));
        runner = new InProcessTerraformAnsibleRunner(properties, executor);
    }

    @Test
    void provisionPlansAppliesTheSavedPlanThenConfigures() throws Exception {
        RunOutcome outcome = runner.run(request(JobType.PROVISION), phases::add);

        assertThat(outcome).isEqualTo(new RunOutcome.Succeeded());
        assertThat(phases).containsExactly(JobPhase.PLANNING, JobPhase.APPLYING, JobPhase.CONFIGURING);
        assertThat(executor.commands)
                .extracting(FakeExecutor::summary)
                .containsExactly(
                        "/repo/scripts/tf.sh environment floci plan",
                        "/repo/scripts/tf.sh environment floci apply",
                        "/repo/scripts/tf.sh environment floci output",
                        "python3 /repo/ansible/inventory/generate.py",
                        "ansible-playbook -i");

        Path attemptDir = workDir.resolve("jobs/" + JOB_ID + "/attempt-1");
        Path plan = attemptDir.resolve("environment.tfplan");
        assertThat(executor.commands.get(0).args())
                .contains(
                        "-out=" + plan, "job_id=" + JOB_ID, "name=web-1", "instance_type=t3.small", "instance_count=2")
                .doesNotContain("-destroy");
        // apply takes exactly the plan that was reviewed by the PLANNING phase, nothing else
        assertThat(executor.commands.get(1).args()).endsWith("apply", plan.toString());
        assertThat(executor.commands.get(2).stdout()).isEqualTo(attemptDir.resolve("outputs.json"));
        assertThat(executor.commands.get(3).stdin()).isEqualTo(attemptDir.resolve("outputs.json"));
        assertThat(executor.commands.get(3).stdout()).isEqualTo(attemptDir.resolve("inventory.yml"));
        assertThat(executor.commands.get(4).args())
                .contains(attemptDir.resolve("inventory.yml").toString());
        assertThat(executor.commands.get(4).workDir()).isEqualTo(repoRoot.resolve("ansible"));
    }

    @Test
    void everyCommandIdentifiesTheEnvironmentAndUsesTheSharedProviderCache() throws Exception {
        runner.run(request(JobType.PROVISION), phases::add);

        assertThat(executor.commands)
                .allSatisfy(c -> assertThat(c.env())
                        .containsEntry("ENVIRONMENT_ID", ENV_ID.toString())
                        .containsEntry(
                                "TF_PLUGIN_CACHE_DIR",
                                workDir.resolve("plugin-cache").toString())
                        .containsEntry("TF_INPUT", "0"));
    }

    @Test
    void destroyPlansADestroyAndSkipsAnsible() throws Exception {
        RunOutcome outcome = runner.run(request(JobType.DESTROY), phases::add);

        assertThat(outcome).isEqualTo(new RunOutcome.Succeeded());
        assertThat(phases).containsExactly(JobPhase.PLANNING, JobPhase.APPLYING);
        assertThat(executor.commands).hasSize(2);
        assertThat(executor.commands.getFirst().args()).contains("-destroy");
    }

    @Test
    void failedPlanStopsBeforeApplyAndReportsTheLogTail() throws Exception {
        executor.failOnCall(0, 1, "Error: Invalid value for variable");

        RunOutcome outcome = runner.run(request(JobType.PROVISION), phases::add);

        assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
        assertThat(((RunOutcome.Failed) outcome).reason())
                .startsWith("terraform plan failed (exit 1):")
                .contains("Error: Invalid value for variable");
        assertThat(phases).containsExactly(JobPhase.PLANNING); // never entered APPLYING
        assertThat(executor.commands).hasSize(1);
    }

    @Test
    void failedAnsibleRunFailsTheJobAfterInfrastructureWasApplied() throws Exception {
        executor.failOnCall(4, 2, "fatal: [web-1-0]: UNREACHABLE!");

        RunOutcome outcome = runner.run(request(JobType.PROVISION), phases::add);

        assertThat(((RunOutcome.Failed) outcome).reason()).startsWith("ansible-playbook failed (exit 2):");
        assertThat(phases).last().isEqualTo(JobPhase.CONFIGURING);
    }

    @Test
    void retriesGetTheirOwnDirectorySoEarlierLogsAreKept() throws Exception {
        runner.run(request(JobType.DESTROY, 2), phases::add);

        assertThat(executor.commands.getFirst().log().getParent())
                .isEqualTo(workDir.resolve("jobs/" + JOB_ID + "/attempt-2"));
    }

    private static ProvisioningRequest request(JobType type) {
        return request(type, 1);
    }

    private static ProvisioningRequest request(JobType type, int attempt) {
        return new ProvisioningRequest(JOB_ID, type, attempt, ENV_ID, "web-1", "t3.small", 2);
    }

    /** Records commands; succeeds unless told to fail a given call. */
    static class FakeExecutor implements CommandExecutor {

        final List<Command> commands = new ArrayList<>();
        private int failingCall = -1;
        private CommandResult failure;

        void failOnCall(int index, int exitCode, String logTail) {
            failingCall = index;
            failure = new CommandResult(exitCode, logTail);
        }

        @Override
        public CommandResult run(Command command) {
            commands.add(command);
            return commands.size() - 1 == failingCall ? failure : new CommandResult(0, "");
        }

        /** The executable and its first arguments, enough to identify the step. */
        static String summary(Command c) {
            List<String> args = c.args();
            return args.getFirst().endsWith("tf.sh")
                    ? String.join(" ", args.subList(0, 4))
                    : String.join(" ", args.subList(0, 2));
        }
    }
}

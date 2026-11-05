package io.github.zhengyixu.infrassa.provisioning.inprocess;

import io.github.zhengyixu.infrassa.job.JobPhase;
import io.github.zhengyixu.infrassa.job.JobType;
import io.github.zhengyixu.infrassa.provisioning.JobProgress;
import io.github.zhengyixu.infrassa.provisioning.ProvisioningRequest;
import io.github.zhengyixu.infrassa.provisioning.ProvisioningRunner;
import io.github.zhengyixu.infrassa.provisioning.RunOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a job as child processes on this host (ADR 0001):
 *
 * <ol>
 *   <li>PLANNING: {@code terraform plan -out} (no infrastructure changes)
 *   <li>APPLYING: {@code terraform apply <saved plan>}: exactly what was planned, nothing newer
 *   <li>CONFIGURING (PROVISION only): Terraform outputs, then inventory, then
 *       {@code ansible-playbook}
 * </ol>
 *
 * <p>Terraform runs through {@code scripts/tf.sh}, the same entry point a person uses, so the
 * backend, state key ({@code environments/<id>/}), data directory and account guard are identical
 * whether a human or the API runs it.
 *
 * <p>Per-job files live in {@code <workDir>/jobs/<jobId>/attempt-<n>/}: the plan, Terraform outputs,
 * the inventory and one log per step. A separate inventory per job means concurrent jobs never
 * overwrite each other's hosts.
 */
@Component
public class InProcessTerraformAnsibleRunner implements ProvisioningRunner {

    private static final Logger log = LoggerFactory.getLogger(InProcessTerraformAnsibleRunner.class);

    /** last_error is shown in the API; keep it to the useful end of the log. */
    private static final int MAX_REASON_LENGTH = 4000;

    private final InProcessRunnerProperties properties;
    private final CommandExecutor executor;

    public InProcessTerraformAnsibleRunner(InProcessRunnerProperties properties, CommandExecutor executor) {
        this.properties = properties;
        this.executor = executor;
        if (!Files.isRegularFile(tfScript())) {
            log.warn("{} not found: set infrassa.runner.repo-root to the repository checkout", tfScript());
        }
    }

    @Override
    public RunOutcome run(ProvisioningRequest request, JobProgress progress) throws InterruptedException {
        Path jobDir = properties
                .workDirPath()
                .resolve("jobs")
                .resolve(request.jobId().toString())
                .resolve("attempt-" + request.attempt());
        Path pluginCache = properties.workDirPath().resolve("plugin-cache");
        createDirectories(jobDir, pluginCache);
        var steps = new Steps(request, jobDir, pluginCache);

        progress.enterPhase(JobPhase.PLANNING);
        Path plan = jobDir.resolve("environment.tfplan");
        CommandResult result = steps.terraform("plan", planArgs(request, plan));
        if (!result.succeeded()) {
            return failed("terraform plan", result);
        }

        progress.enterPhase(JobPhase.APPLYING);
        result = steps.terraform("apply", List.of("apply", plan.toString()));
        if (!result.succeeded()) {
            return failed("terraform apply", result);
        }
        if (request.type() == JobType.DESTROY) {
            return new RunOutcome.Succeeded();
        }

        progress.enterPhase(JobPhase.CONFIGURING);
        Path outputs = jobDir.resolve("outputs.json");
        Path inventory = jobDir.resolve("inventory.yml");
        result = steps.run("terraform-output", steps.tf("output", "-json").withStdout(outputs));
        if (!result.succeeded()) {
            return failed("terraform output", result);
        }
        result = steps.run(
                "inventory",
                Command.of(
                                properties.repoRootPath(),
                                steps.env,
                                jobDir.resolve("inventory.log"),
                                properties.python(),
                                properties
                                        .repoRootPath()
                                        .resolve("ansible/inventory/generate.py")
                                        .toString())
                        .withStdin(outputs)
                        .withStdout(inventory));
        if (!result.succeeded()) {
            return failed("inventory generation", result);
        }
        result = steps.run(
                "ansible",
                Command.of(
                        properties.repoRootPath().resolve("ansible"), // picks up ansible.cfg
                        steps.env,
                        jobDir.resolve("ansible.log"),
                        properties.ansiblePlaybook(),
                        "-i",
                        inventory.toString(),
                        "site.yml"));
        if (!result.succeeded()) {
            return failed("ansible-playbook", result);
        }
        return new RunOutcome.Succeeded();
    }

    private static List<String> planArgs(ProvisioningRequest request, Path plan) {
        var args = new ArrayList<>(List.of(
                "plan",
                "-out=" + plan,
                "-var",
                "job_id=" + request.jobId(),
                "-var",
                "name=" + request.environmentName(),
                "-var",
                "instance_type=" + request.instanceType(),
                "-var",
                "instance_count=" + request.instanceCount()));
        if (request.type() == JobType.DESTROY) {
            args.add("-destroy");
        }
        return args;
    }

    private static RunOutcome failed(String step, CommandResult result) {
        String reason = "%s failed (exit %d):%n%s".formatted(step, result.exitCode(), result.logTail());
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = "..." + reason.substring(reason.length() - MAX_REASON_LENGTH);
        }
        return new RunOutcome.Failed(reason);
    }

    private Path tfScript() {
        return properties.repoRootPath().resolve("scripts/tf.sh");
    }

    private static void createDirectories(Path... dirs) {
        try {
            for (Path dir : dirs) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Command building for one job: shared environment variables and per-step log files. */
    private final class Steps {

        private final Path jobDir;
        private final Map<String, String> env;

        Steps(ProvisioningRequest request, Path jobDir, Path pluginCache) {
            this.jobDir = jobDir;
            this.env = Map.of(
                    "ENVIRONMENT_ID", request.environmentId().toString(), // tf.sh: state key, data dir
                    "TF_IN_AUTOMATION", "1", // shorter output, no "run terraform apply next" hints
                    "TF_INPUT", "0", // fail instead of prompting for a missing variable
                    "TF_CLI_ARGS", "-no-color", // plain logs and error messages
                    // Providers are downloaded once per host, not once per environment.
                    "TF_PLUGIN_CACHE_DIR", pluginCache.toString(),
                    "ANSIBLE_NOCOLOR", "1");
        }

        Command tf(String... args) {
            var all = new ArrayList<>(List.of(tfScript().toString(), "environment", properties.target()));
            all.addAll(List.of(args));
            return Command.of(
                    properties.repoRootPath(),
                    env,
                    jobDir.resolve("terraform-" + args[0] + ".log"),
                    all.toArray(String[]::new));
        }

        CommandResult terraform(String step, List<String> args) throws InterruptedException {
            return run("terraform-" + step, tf(args.toArray(String[]::new)));
        }

        CommandResult run(String step, Command command) throws InterruptedException {
            log.info("Running step {} (log: {})", step, command.log());
            CommandResult result = executor.run(command);
            log.info("Step {} exited with {}", step, result.exitCode());
            return result;
        }
    }
}

package io.github.zhengyixu.infrassa.provisioning.inprocess;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code infrassa.runner.*}: where the in-process runner finds the repository's Terraform and
 * Ansible code, and where it keeps per-job files (plans, inventories, logs).
 *
 * @param target "floci" or "aws"; passed to scripts/tf.sh, which pairs it with the right backend
 * @param repoRoot checkout containing scripts/, terraform/ and ansible/
 * @param workDir per-job files and the shared Terraform provider cache
 * @param python interpreter for ansible/inventory/generate.py
 * @param ansiblePlaybook ansible-playbook executable
 * @param terminationGracePeriod how long a stopped child process gets after SIGTERM before SIGKILL
 */
@ConfigurationProperties("infrassa.runner")
public record InProcessRunnerProperties(
        @DefaultValue("floci") String target,
        @DefaultValue("..") String repoRoot,
        String workDir,
        @DefaultValue("python3") String python,
        @DefaultValue("ansible-playbook") String ansiblePlaybook,
        @DefaultValue("30s") Duration terminationGracePeriod) {

    public InProcessRunnerProperties {
        if (!target.equals("floci") && !target.equals("aws")) {
            throw new IllegalArgumentException("infrassa.runner.target must be floci or aws: " + target);
        }
        if (workDir == null || workDir.isBlank()) {
            workDir = Path.of(System.getProperty("java.io.tmpdir"), "infra-ssa").toString();
        }
    }

    // Bound as String: Spring converts String to Path through its resource loader, which treats
    // ".." as a classpath location and rejects it.

    /** Absolute: tf.sh changes directory, so relative plan and log paths would break. */
    public Path repoRootPath() {
        return Path.of(repoRoot).toAbsolutePath().normalize();
    }

    public Path workDirPath() {
        return Path.of(workDir).toAbsolutePath().normalize();
    }
}

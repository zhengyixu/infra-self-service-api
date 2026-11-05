package io.github.zhengyixu.infrassa.provisioning.inprocess;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A child process to run. Arguments are a list, never one shell string, so values such as an
 * environment name are passed as-is and cannot inject shell syntax.
 *
 * @param log stderr (and stdout, unless {@code stdout} is set) is written here
 * @param stdin file to read standard input from, or null for none
 * @param stdout file to write standard output to, or null to send it to {@code log}
 */
public record Command(List<String> args, Path workDir, Map<String, String> env, Path log, Path stdin, Path stdout) {

    public Command {
        args = List.copyOf(args);
        env = Map.copyOf(env);
    }

    public static Command of(Path workDir, Map<String, String> env, Path log, String... args) {
        return new Command(List.of(args), workDir, env, log, null, null);
    }

    public Command withStdin(Path file) {
        return new Command(args, workDir, env, log, file, stdout);
    }

    public Command withStdout(Path file) {
        return new Command(args, workDir, env, log, stdin, file);
    }
}

package io.github.zhengyixu.infrassa.provisioning.inprocess;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * {@link ProcessBuilder}-based executor. .NET analogy: {@code System.Diagnostics.Process} with
 * redirected output.
 *
 * <p>Output goes to a log file, not through Java: nothing to drain, so a chatty process can never
 * block on a full pipe, and the full log stays on disk for debugging.
 */
@Component
public class ProcessCommandExecutor implements CommandExecutor {

    private static final int TAIL_LINES = 20;
    private static final Path DEV_NULL = Path.of("/dev/null");

    private final Duration terminationGracePeriod;

    public ProcessCommandExecutor(InProcessRunnerProperties properties) {
        this.terminationGracePeriod = properties.terminationGracePeriod();
    }

    @Override
    public CommandResult run(Command command) throws InterruptedException {
        var builder =
                new ProcessBuilder(command.args()).directory(command.workDir().toFile());
        builder.environment().putAll(command.env());
        var log = ProcessBuilder.Redirect.appendTo(command.log().toFile());
        builder.redirectError(log);
        builder.redirectOutput(
                command.stdout() != null
                        ? ProcessBuilder.Redirect.to(command.stdout().toFile())
                        : log);
        // No stdin unless given: a tool waiting for input would otherwise hang the job.
        builder.redirectInput(
                ProcessBuilder.Redirect.from((command.stdin() != null ? command.stdin() : DEV_NULL).toFile()));

        Process process;
        try {
            Files.writeString(
                    command.log(),
                    "$ " + String.join(" ", command.args()) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start " + command.args().getFirst(), e);
        }

        try {
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, tail(command.log()));
        } catch (InterruptedException e) {
            terminate(process);
            throw e;
        }
    }

    /**
     * SIGTERM first, so Terraform can finish the resource it is changing, write state and release
     * its lock; SIGKILL only after the grace period. Descendants are signalled too: a wrapper
     * script's children do not die with it.
     */
    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(terminationGracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException again) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(Path log) {
        try {
            // Lenient decoding: tool output is not guaranteed to be valid UTF-8.
            List<String> lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
            return String.join("\n", lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()));
        } catch (IOException e) {
            return "(log unavailable: " + e.getMessage() + ")";
        }
    }
}

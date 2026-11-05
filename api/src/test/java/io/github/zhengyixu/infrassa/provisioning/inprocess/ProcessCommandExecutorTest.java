package io.github.zhengyixu.infrassa.provisioning.inprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real child processes ({@code sh}), so this runs on Linux and macOS only, like the runner. */
class ProcessCommandExecutorTest {

    @TempDir
    Path dir;

    private final ProcessCommandExecutor executor =
            new ProcessCommandExecutor(new InProcessRunnerProperties("floci", ".", ".", "", "", Duration.ofSeconds(5)));

    @Test
    void returnsTheExitCodeAndTheEndOfTheLog() throws Exception {
        CommandResult result = executor.run(sh("for i in $(seq 1 30); do echo line $i; done; echo oops >&2; exit 3"));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.logTail().lines()).hasSize(20).last().isEqualTo("oops");
        assertThat(Files.readString(dir.resolve("step.log")))
                .startsWith("$ sh -c")
                .contains("line 1\n");
    }

    @Test
    void passesEnvironmentStdinAndStdout() throws Exception {
        Path in = Files.writeString(dir.resolve("in.txt"), "hello");
        Path out = dir.resolve("out.txt");
        var command = new Command(
                List.of("sh", "-c", "printf '%s %s' \"$GREETING\" \"$(cat)\""),
                dir,
                Map.of("GREETING", "say"),
                dir.resolve("step.log"),
                in,
                out);

        assertThat(executor.run(command).succeeded()).isTrue();
        assertThat(Files.readString(out)).isEqualTo("say hello");
    }

    @Test
    void interruptTerminatesTheProcessAndItsChildren() throws Exception {
        Path pidFile = dir.resolve("child.pid");
        // A parent that starts a long-running child, like tf.sh starting terraform init.
        Command command = sh("sleep 300 & echo $! > " + pidFile + "; wait");

        try (var thread = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return executor.run(command);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    },
                    thread);
            long childPid = waitForPid(pidFile);
            assertThat(ProcessHandle.of(childPid))
                    .hasValueSatisfying(p -> assertThat(p.isAlive()).isTrue());

            thread.shutdownNow(); // interrupts the thread blocked in waitFor()

            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(InterruptedException.class);
            // The child got SIGTERM too (not only the shell that started it).
            await().atMost(Duration.ofSeconds(5))
                    .until(() ->
                            ProcessHandle.of(childPid).map(p -> !p.isAlive()).orElse(true));
        }
    }

    private Command sh(String script) {
        return Command.of(dir, Map.of(), dir.resolve("step.log"), "sh", "-c", script);
    }

    private static long waitForPid(Path pidFile) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                String pid = Files.readString(pidFile).strip();
                if (!pid.isEmpty()) {
                    return Long.parseLong(pid);
                }
            } catch (IOException notYet) {
                // file not written yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("child never started");
    }
}

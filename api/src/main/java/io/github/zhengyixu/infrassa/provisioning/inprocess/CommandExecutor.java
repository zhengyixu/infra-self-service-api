package io.github.zhengyixu.infrassa.provisioning.inprocess;

/** Runs child processes. An interface so runner tests can script results without real tools. */
public interface CommandExecutor {

    /**
     * Runs the command and waits for it.
     *
     * @throws InterruptedException if the calling thread is interrupted; the process (and its
     *     children) are terminated before this is thrown
     */
    CommandResult run(Command command) throws InterruptedException;
}

package io.github.zhengyixu.infrassa.provisioning.inprocess;

/** @param logTail the last lines of the command's log, for error messages */
public record CommandResult(int exitCode, String logTail) {

    public boolean succeeded() {
        return exitCode == 0;
    }
}

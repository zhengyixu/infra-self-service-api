package io.github.zhengyixu.infrassa.provisioning;

/**
 * How a runner finished. Sealed: a {@code switch} over it must handle every case, and the compiler
 * points at each switch when a case is added (like an exhaustive match on a C# closed hierarchy).
 */
public sealed interface RunOutcome {

    record Succeeded() implements RunOutcome {}

    /** @param reason shown to the user as the job's last_error; include the useful part of the log */
    record Failed(String reason) implements RunOutcome {}
}

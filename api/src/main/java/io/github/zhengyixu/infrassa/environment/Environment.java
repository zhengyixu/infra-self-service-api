package io.github.zhengyixu.infrassa.environment;

import java.time.Instant;
import java.util.UUID;

/** An environment as stored in the {@code environments} table. */
public record Environment(
        UUID id,
        String name,
        String instanceType,
        int instanceCount,
        EnvironmentStatus status,
        Instant createdAt,
        Instant updatedAt) {}

package io.github.zhengyixu.infrassa.environment;

import java.util.UUID;

public class EnvironmentNotFoundException extends RuntimeException {

    private final UUID id;

    public EnvironmentNotFoundException(UUID id) {
        super("Environment " + id + " not found");
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}

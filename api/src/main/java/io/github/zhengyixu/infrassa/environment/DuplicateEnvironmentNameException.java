package io.github.zhengyixu.infrassa.environment;

public class DuplicateEnvironmentNameException extends RuntimeException {

    private final String name;

    public DuplicateEnvironmentNameException(String name, Throwable cause) {
        super("An environment named '" + name + "' already exists", cause);
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

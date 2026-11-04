package io.github.zhengyixu.infrassa;

import org.springframework.boot.SpringApplication;

public class TestInfraSelfServiceApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(InfraSelfServiceApiApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}

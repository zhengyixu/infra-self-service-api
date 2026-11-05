package io.github.zhengyixu.infrassa.provisioning.inprocess;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@code infrassa.runner.*}; the runner and executor beans are component-scanned. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InProcessRunnerProperties.class)
class InProcessRunnerConfig {}

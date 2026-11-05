package io.github.zhengyixu.infrassa.provisioning;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobEngineProperties.class)
class ProvisioningConfig {

    @Bean
    RecoveryPolicy recoveryPolicy(JobEngineProperties properties) {
        return new RecoveryPolicy(properties.maxPlanningAttempts());
    }
}

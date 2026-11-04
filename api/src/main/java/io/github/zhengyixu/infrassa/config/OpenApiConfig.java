package io.github.zhengyixu.infrassa.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    private static final String REPO_URL = "https://github.com/zhengyixu/infra-self-service-api";
    private final BuildProperties buildProperties;

    public OpenApiConfig(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Infra Self-Service API")
                        .description("Request, track and tear down environments provisioned with Terraform and Ansible")
                        .version(buildProperties.getVersion())
                        .license(new License().name("MIT").url(REPO_URL + "/blob/main/LICENSE")))
                .externalDocs(new ExternalDocumentation()
                        .description("Source and design decisions")
                        .url(REPO_URL));
    }
}

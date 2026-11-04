package io.github.zhengyixu.infrassa.config;

import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata shown at /swagger-ui.html and /v3/api-docs.
 *
 * <p>TODO(you): replace springdoc's default title ("OpenAPI definition", version "v0") with real
 * metadata. Add one {@code @Bean} method returning {@code io.swagger.v3.oas.models.OpenAPI}:
 *
 * <ul>
 *   <li>{@code new OpenAPI().info(new Info().title(...).description(...).version(...))}
 *   <li>Version: inject {@code org.springframework.boot.info.BuildProperties} as a method
 *       parameter and use {@code buildProperties.getVersion()}, so the API version always matches
 *       the build (it comes from the pom via the build-info goal).
 *   <li>Optional: {@code .license(new License().name("MIT"))} and an external docs link to the
 *       GitHub repo.
 * </ul>
 *
 * <p>.NET analogy: {@code services.AddSwaggerGen(c => c.SwaggerDoc("v1", new OpenApiInfo {...}))}.
 * {@code @Configuration} + {@code @Bean} is how Spring registers an object in the DI container,
 * like {@code services.AddSingleton(...)}.
 *
 * <p>Check: {@code curl -s localhost:8080/v3/api-docs | jq .info} shows your title and version.
 */
@Configuration
class OpenApiConfig {}

package io.github.zhengyixu.infrassa;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application context (with MockMvc) against PostgreSQL in a container, with the job engine
 * switched off so background workers never claim the jobs a test creates.
 *
 * <p>Every class with the same annotations shares one cached Spring context (and one container),
 * so prefer this over repeating the annotations with small differences. .NET analogy: a shared
 * xUnit collection fixture.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "infrassa.jobs.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {}

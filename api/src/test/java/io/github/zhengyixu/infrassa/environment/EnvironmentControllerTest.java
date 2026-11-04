package io.github.zhengyixu.infrassa.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.zhengyixu.infrassa.job.Job;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Web slice test: starts only the MVC layer (controllers, @RestControllerAdvice, JSON, validation)
 * with the service replaced by a Mockito mock. No database.
 */
@WebMvcTest(EnvironmentController.class)
class EnvironmentControllerTest {

    @Autowired
    MockMvcTester mvc;

    @MockitoBean
    EnvironmentService service;

    @Test
    void createReturns202WithLocationAndBody() {
        Environment env = EnvironmentServiceTest.environment("demo-1");
        Job job = EnvironmentServiceTest.pendingJob(env.id());
        when(service.request(any())).thenReturn(new EnvironmentDetails(env, Optional.of(job)));

        var result = mvc.post()
                .uri("/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "demo-1"}""")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        assertThat(result).headers().hasValue(HttpHeaders.LOCATION, "http://localhost/environments/" + env.id());
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("PROVISIONING");
        assertThat(result).bodyJson().extractingPath("$.currentJob.status").isEqualTo("PENDING");
    }

    @Test
    void invalidRequestReturns400ProblemWithFieldErrors() {
        var result = mvc.post()
                .uri("/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Bad_Name", "instanceType": "m5.24xlarge", "instanceCount": 9}""")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "instanceType", "instanceCount");
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonReturns400Problem() {
        var result = mvc.post()
                .uri("/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void duplicateNameReturns409Problem() {
        when(service.request(any()))
                .thenThrow(new DuplicateEnvironmentNameException("demo-1", new DuplicateKeyException("dup")));

        var result = mvc.post()
                .uri("/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "demo-1"}""")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.name").isEqualTo("demo-1");
    }

    @Test
    void getWithInvalidUuidReturns400Problem() {
        var result = mvc.get().uri("/environments/not-a-uuid").exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void getUnknownEnvironmentReturns404Problem() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new EnvironmentNotFoundException(id));

        var result = mvc.get().uri("/environments/{id}", id).exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Environment not found");
    }
}

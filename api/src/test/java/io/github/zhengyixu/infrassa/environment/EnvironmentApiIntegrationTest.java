package io.github.zhengyixu.infrassa.environment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zhengyixu.infrassa.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Full stack against PostgreSQL in a container: Flyway schema, real SQL, real transactions and
 * the unique index that rejects duplicate names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EnvironmentApiIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void requestedEnvironmentIsStoredWithPendingJobAndAuditEvents() {
        String name = uniqueName();

        var created = post(name);
        assertThat(created).hasStatus(HttpStatus.ACCEPTED);
        UUID id = UUID.fromString(created.getResponse().getHeader("Location").replaceAll(".*/", ""));

        var fetched = mvc.get().uri("/environments/{id}", id).exchange();
        assertThat(fetched).hasStatusOk();
        assertThat(fetched).bodyJson().extractingPath("$.name").isEqualTo(name);
        assertThat(fetched).bodyJson().extractingPath("$.instanceType").isEqualTo("t3.micro");
        assertThat(fetched).bodyJson().extractingPath("$.currentJob.type").isEqualTo("PROVISION");
        assertThat(fetched).bodyJson().extractingPath("$.currentJob.status").isEqualTo("PENDING");

        var events = jdbc.sql("""
                        SELECT e.event_type
                          FROM audit_events e
                         WHERE e.aggregate_id = :id
                            OR e.aggregate_id IN (SELECT j.id FROM jobs j WHERE j.environment_id = :id)
                         ORDER BY e.id""").param("id", id).query(String.class).list();
        assertThat(events).containsExactly("ENVIRONMENT_REQUESTED", "JOB_CREATED");
    }

    @Test
    void secondRequestWithSameNameIsRejectedAndWritesNothing() {
        String name = uniqueName();
        assertThat(post(name)).hasStatus(HttpStatus.ACCEPTED);

        assertThat(post(name)).hasStatus(HttpStatus.CONFLICT);

        long rows = jdbc.sql("SELECT count(*) FROM environments WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
        assertThat(rows).isEqualTo(1);
    }

    private MvcTestResult post(String name) {
        return mvc.post()
                .uri("/environments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"%s\"}".formatted(name))
                .exchange();
    }

    /** Tests share one database, so each test uses its own names instead of cleaning up. */
    private static String uniqueName() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }
}

package io.github.zhengyixu.infrassa.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.zhengyixu.infrassa.audit.AggregateType;
import io.github.zhengyixu.infrassa.audit.AuditEventRepository;
import io.github.zhengyixu.infrassa.job.Job;
import io.github.zhengyixu.infrassa.job.JobRepository;
import io.github.zhengyixu.infrassa.job.JobStatus;
import io.github.zhengyixu.infrassa.job.JobType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/** Pure unit test: no Spring context, no database. Transactions are covered by the integration test. */
@ExtendWith(MockitoExtension.class)
class EnvironmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Mock
    EnvironmentRepository environments;

    @Mock
    JobRepository jobs;

    @Mock
    AuditEventRepository audit;

    @InjectMocks
    EnvironmentService service;

    @Test
    void requestRecordsEnvironmentPendingJobAndAuditEvents() {
        Environment env = environment("demo-1");
        Job job = pendingJob(env.id());
        when(environments.insert("demo-1", "t3.micro", 1, EnvironmentStatus.PROVISIONING))
                .thenReturn(env);
        when(jobs.insertPending(env.id(), JobType.PROVISION)).thenReturn(job);

        EnvironmentDetails details = service.request(new CreateEnvironmentRequest("demo-1", null, null));

        assertThat(details.environment()).isEqualTo(env);
        assertThat(details.currentJob()).contains(job);
        InOrder order = inOrder(environments, jobs, audit);
        order.verify(environments).insert("demo-1", "t3.micro", 1, EnvironmentStatus.PROVISIONING);
        order.verify(jobs).insertPending(env.id(), JobType.PROVISION);
        order.verify(audit).append(eq(AggregateType.ENVIRONMENT), eq(env.id()), eq("ENVIRONMENT_REQUESTED"), anyMap());
        order.verify(audit).append(eq(AggregateType.JOB), eq(job.id()), eq("JOB_CREATED"), anyMap());
    }

    @Test
    void duplicateNameIsReportedAndNothingElseIsWritten() {
        when(environments.insert(anyString(), anyString(), anyInt(), any()))
                .thenThrow(new DuplicateKeyException("environments_name_live_uq"));

        assertThatThrownBy(() -> service.request(new CreateEnvironmentRequest("demo-1", null, null)))
                .isInstanceOf(DuplicateEnvironmentNameException.class)
                .hasMessageContaining("demo-1");
        verifyNoInteractions(jobs, audit);
    }

    @Test
    void getUnknownEnvironmentThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(environments.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id))
                .isInstanceOf(EnvironmentNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void getReturnsEnvironmentWithLatestJob() {
        Environment env = environment("demo-2");
        Job job = pendingJob(env.id());
        when(environments.findById(env.id())).thenReturn(Optional.of(env));
        when(jobs.findLatestForEnvironment(env.id())).thenReturn(Optional.of(job));

        assertThat(service.get(env.id())).isEqualTo(new EnvironmentDetails(env, Optional.of(job)));
    }

    static Environment environment(String name) {
        return new Environment(UUID.randomUUID(), name, "t3.micro", 1, EnvironmentStatus.PROVISIONING, NOW, NOW);
    }

    static Job pendingJob(UUID environmentId) {
        return new Job(UUID.randomUUID(), environmentId, JobType.PROVISION, JobStatus.PENDING, null, 0, NOW, NOW);
    }
}

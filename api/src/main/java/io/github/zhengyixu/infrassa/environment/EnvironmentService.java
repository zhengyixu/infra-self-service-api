package io.github.zhengyixu.infrassa.environment;

import io.github.zhengyixu.infrassa.audit.AggregateType;
import io.github.zhengyixu.infrassa.audit.AuditEventRepository;
import io.github.zhengyixu.infrassa.job.Job;
import io.github.zhengyixu.infrassa.job.JobRepository;
import io.github.zhengyixu.infrassa.job.JobType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases for environments. Owns the transaction boundary: the environment, its job and the
 * audit events are committed together or not at all (ADR 0003).
 *
 * <p>Controllers never start work; they only record a PENDING job. Workers pick it up (ADR 0001).
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentRepository environments;
    private final JobRepository jobs;
    private final AuditEventRepository audit;

    public EnvironmentService(EnvironmentRepository environments, JobRepository jobs, AuditEventRepository audit) {
        this.environments = environments;
        this.jobs = jobs;
        this.audit = audit;
    }

    /**
     * Records an environment request and its PROVISION job.
     *
     * @throws DuplicateEnvironmentNameException if a live environment already has this name
     */
    @Transactional
    public EnvironmentDetails request(CreateEnvironmentRequest request) {
        Environment env;
        try {
            env = environments.insert(
                    request.name(), request.instanceType(), request.instanceCount(), EnvironmentStatus.PROVISIONING);
        } catch (DuplicateKeyException e) {
            // Rethrowing an unchecked exception rolls the transaction back.
            throw new DuplicateEnvironmentNameException(request.name(), e);
        }
        Job job = jobs.insertPending(env.id(), JobType.PROVISION);

        audit.append(
                AggregateType.ENVIRONMENT,
                env.id(),
                "ENVIRONMENT_REQUESTED",
                Map.of(
                        "name", env.name(),
                        "instanceType", env.instanceType(),
                        "instanceCount", env.instanceCount(),
                        "status", env.status().name()));
        audit.append(
                AggregateType.JOB,
                job.id(),
                "JOB_CREATED",
                Map.of(
                        "environmentId", env.id().toString(),
                        "type", job.type().name(),
                        "status", job.status().name()));

        log.info("Environment requested: name={} environmentId={} jobId={}", env.name(), env.id(), job.id());
        return new EnvironmentDetails(env, Optional.of(job));
    }

    @Transactional(readOnly = true)
    public EnvironmentDetails get(UUID id) {
        Environment env = environments.findById(id).orElseThrow(() -> new EnvironmentNotFoundException(id));
        return new EnvironmentDetails(env, jobs.findLatestForEnvironment(id));
    }
}

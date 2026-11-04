package io.github.zhengyixu.infrassa.audit;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the transactional outbox (ADR 0003). An audit event is only meaningful together with
 * the state change it describes, so {@link #append} refuses to run outside a transaction.
 */
@Repository
public class AuditEventRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditEventRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Records an event in the caller's transaction.
     *
     * @param eventType past tense, e.g. ENVIRONMENT_REQUESTED, JOB_CREATED
     * @param payload small JSON-serializable snapshot of what changed
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AggregateType aggregateType, UUID aggregateId, String eventType, Map<String, ?> payload) {
        jdbc.sql("""
                        INSERT INTO audit_events (aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))""")
                .param("aggregateType", aggregateType.name())
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}

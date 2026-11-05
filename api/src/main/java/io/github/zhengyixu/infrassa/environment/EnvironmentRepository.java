package io.github.zhengyixu.infrassa.environment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Plain SQL through {@link JdbcClient} (closest .NET equivalent: Dapper). No ORM, so the SQL that
 * matters for correctness (unique indexes, and later SKIP LOCKED and fencing) stays visible.
 */
@Repository
public class EnvironmentRepository {

    private static final String COLUMNS = "id, name, instance_type, instance_count, status, created_at, updated_at";

    private static final RowMapper<Environment> MAPPER = EnvironmentRepository::map;

    private final JdbcClient jdbc;

    public EnvironmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new environment. Fails with {@link org.springframework.dao.DuplicateKeyException}
     * if a live environment already has this name (unique index environments_name_live_uq).
     */
    public Environment insert(String name, String instanceType, int instanceCount, EnvironmentStatus status) {
        return jdbc.sql("""
                        INSERT INTO environments (name, instance_type, instance_count, status)
                        VALUES (:name, :instanceType, :instanceCount, :status)
                        RETURNING %s""".formatted(COLUMNS))
                .param("name", name)
                .param("instanceType", instanceType)
                .param("instanceCount", instanceCount)
                .param("status", status.name())
                .query(MAPPER)
                .single();
    }

    public Optional<Environment> findById(UUID id) {
        return jdbc.sql("SELECT %s FROM environments WHERE id = :id".formatted(COLUMNS))
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Sets the status. Called by the job engine in the same transaction as the job update. */
    public boolean updateStatus(UUID id, EnvironmentStatus status) {
        return jdbc.sql("UPDATE environments SET status = :status, updated_at = now() WHERE id = :id")
                        .param("status", status.name())
                        .param("id", id)
                        .update()
                == 1;
    }

    private static Environment map(ResultSet rs, int rowNum) throws SQLException {
        return new Environment(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("instance_type"),
                rs.getInt("instance_count"),
                EnvironmentStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}

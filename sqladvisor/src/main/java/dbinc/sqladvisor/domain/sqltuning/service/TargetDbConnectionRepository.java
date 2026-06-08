package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class TargetDbConnectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public TargetDbConnectionRepository(@Qualifier("primaryDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS target_db_connection (
                    id BIGSERIAL PRIMARY KEY,
                    owner_user_id BIGINT,
                    name TEXT NOT NULL,
                    db_type TEXT NOT NULL,
                    jdbc_url TEXT NOT NULL,
                    username TEXT NOT NULL,
                    encrypted_password TEXT NOT NULL,
                    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
                    monitoring_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    monitoring_interval_sec INTEGER NOT NULL DEFAULT 600,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_target_db_connection_owner ON target_db_connection(owner_user_id, updated_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_target_db_connection_visibility ON target_db_connection(visibility, updated_at DESC)");
    }

    public TargetDbConnectionRecord save(
            Long ownerUserId,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String encryptedPassword,
            String visibility,
            boolean monitoringEnabled,
            int monitoringIntervalSec
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO target_db_connection(
                                owner_user_id, name, db_type, jdbc_url, username, encrypted_password,
                                visibility, monitoring_enabled, monitoring_interval_sec
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, new String[]{"id"});
            statement.setObject(1, ownerUserId);
            statement.setString(2, name);
            statement.setString(3, dbType);
            statement.setString(4, jdbcUrl);
            statement.setString(5, username);
            statement.setString(6, encryptedPassword);
            statement.setString(7, visibility);
            statement.setBoolean(8, monitoringEnabled);
            statement.setInt(9, monitoringIntervalSec);
            return statement;
        }, keyHolder);
        return findById(keyHolder.getKey().longValue()).orElseThrow();
    }

    public TargetDbConnectionRecord update(
            long id,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String encryptedPassword,
            String visibility,
            boolean monitoringEnabled,
            int monitoringIntervalSec
    ) {
        jdbcTemplate.update("""
                        UPDATE target_db_connection
                           SET name = ?,
                               db_type = ?,
                               jdbc_url = ?,
                               username = ?,
                               encrypted_password = ?,
                               visibility = ?,
                               monitoring_enabled = ?,
                               monitoring_interval_sec = ?,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                        """,
                name,
                dbType,
                jdbcUrl,
                username,
                encryptedPassword,
                visibility,
                monitoringEnabled,
                monitoringIntervalSec,
                id
        );
        return findById(id).orElseThrow();
    }

    public List<TargetDbConnectionRecord> findVisible(Long userId, boolean includeAll) {
        return jdbcTemplate.query("""
                        SELECT *
                          FROM target_db_connection
                         WHERE ? OR visibility = 'SHARED' OR ((?::bigint IS NULL AND owner_user_id IS NULL) OR owner_user_id = ?::bigint)
                         ORDER BY updated_at DESC, id DESC
                        """,
                rowMapper(),
                includeAll,
                userId,
                userId
        );
    }

    public Optional<TargetDbConnectionRecord> findVisibleById(long id, Long userId, boolean includeAll) {
        List<TargetDbConnectionRecord> rows = jdbcTemplate.query("""
                        SELECT *
                          FROM target_db_connection
                         WHERE id = ?
                           AND (? OR visibility = 'SHARED' OR ((?::bigint IS NULL AND owner_user_id IS NULL) OR owner_user_id = ?::bigint))
                        """,
                rowMapper(),
                id,
                includeAll,
                userId,
                userId
        );
        return rows.stream().findFirst();
    }

    public Optional<TargetDbConnectionRecord> findById(long id) {
        List<TargetDbConnectionRecord> rows = jdbcTemplate.query("""
                        SELECT *
                          FROM target_db_connection
                         WHERE id = ?
                        """,
                rowMapper(),
                id
        );
        return rows.stream().findFirst();
    }

    public void delete(long id) {
        jdbcTemplate.update("DELETE FROM target_db_connection WHERE id = ?", id);
    }

    private RowMapper<TargetDbConnectionRecord> rowMapper() {
        return (rs, rowNum) -> new TargetDbConnectionRecord(
                rs.getLong("id"),
                rs.getObject("owner_user_id", Long.class),
                rs.getString("name"),
                rs.getString("db_type"),
                rs.getString("jdbc_url"),
                rs.getString("username"),
                rs.getString("encrypted_password"),
                rs.getString("visibility"),
                rs.getBoolean("monitoring_enabled"),
                rs.getInt("monitoring_interval_sec"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    public record TargetDbConnectionRecord(
            Long id,
            Long ownerUserId,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String encryptedPassword,
            String visibility,
            boolean monitoringEnabled,
            int monitoringIntervalSec,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public SqlTuningDtos.TargetDbConnectionResponse toResponse() {
            return new SqlTuningDtos.TargetDbConnectionResponse(
                    id,
                    name,
                    dbType,
                    jdbcUrl,
                    username,
                    visibility,
                    monitoringEnabled,
                    monitoringIntervalSec,
                    createdAt,
                    updatedAt
            );
        }
    }
}

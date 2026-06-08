package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Service
@RequiredArgsConstructor
public class TargetDbConnectionService {

    private static final int DEFAULT_MONITORING_INTERVAL_SEC = 600;

    private record CapabilityProbe(List<String> capabilities, List<String> warnings) {
    }

    private final TargetDbConnectionRepository repository;
    private final TargetDbPasswordCrypto passwordCrypto;
    private final CurrentUserService currentUserService;

    public List<SqlTuningDtos.TargetDbConnectionResponse> listConnections() {
        return repository.findVisible(
                        currentUserService.currentUserIdOrNull(),
                        currentUserService.isCurrentUserAdmin()
                )
                .stream()
                .map(TargetDbConnectionRepository.TargetDbConnectionRecord::toResponse)
                .toList();
    }

    public SqlTuningDtos.TargetDbConnectionResponse createConnection(SqlTuningDtos.TargetDbConnectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Target DB connection request is required.");
        }
        String password = required(request.password(), "DB password");
        TargetDbConnectionRepository.TargetDbConnectionRecord saved = repository.save(
                currentUserService.currentUserIdOrNull(),
                required(request.name(), "Connection name"),
                normalizeDbType(request.dbType()),
                required(request.jdbcUrl(), "JDBC URL"),
                required(request.username(), "DB username"),
                passwordCrypto.encrypt(password),
                normalizeVisibility(request.visibility()),
                Boolean.TRUE.equals(request.monitoringEnabled()),
                normalizeInterval(request.monitoringIntervalSec())
        );
        return saved.toResponse();
    }

    public SqlTuningDtos.TargetDbConnectionResponse updateConnection(long id, SqlTuningDtos.TargetDbConnectionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Target DB connection request is required.");
        }
        TargetDbConnectionRepository.TargetDbConnectionRecord current = getVisibleRecord(id);
        requireManagePermission(current);
        String encryptedPassword = request.password() == null || request.password().isBlank()
                ? current.encryptedPassword()
                : passwordCrypto.encrypt(request.password());
        TargetDbConnectionRepository.TargetDbConnectionRecord updated = repository.update(
                id,
                required(request.name(), "Connection name"),
                normalizeDbType(request.dbType()),
                required(request.jdbcUrl(), "JDBC URL"),
                required(request.username(), "DB username"),
                encryptedPassword,
                normalizeVisibility(request.visibility()),
                Boolean.TRUE.equals(request.monitoringEnabled()),
                normalizeInterval(request.monitoringIntervalSec())
        );
        return updated.toResponse();
    }

    public void deleteConnection(long id) {
        TargetDbConnectionRepository.TargetDbConnectionRecord current = getVisibleRecord(id);
        requireManagePermission(current);
        repository.delete(id);
    }

    public SqlTuningDtos.TargetDbConnectionTestResponse testConnection(SqlTuningDtos.TargetDbConnectionTestRequest request) {
        normalizeDbType(request == null ? null : request.dbType());
        try (Connection connection = openConnection(
                required(request.jdbcUrl(), "JDBC URL"),
                required(request.username(), "DB username"),
                required(request.password(), "DB password")
        )) {
            return successfulTestResponse(connection);
        } catch (SQLException exception) {
            return failedTestResponse(exception);
        }
    }

    public SqlTuningDtos.TargetDbConnectionTestResponse testSavedConnection(long id) {
        TargetDbConnectionRepository.TargetDbConnectionRecord record = getVisibleRecord(id);
        try (Connection connection = openConnection(record)) {
            return successfulTestResponse(connection);
        } catch (SQLException exception) {
            return failedTestResponse(exception);
        }
    }

    public TargetDbConnectionRepository.TargetDbConnectionRecord getVisibleRecord(long id) {
        return repository.findVisibleById(
                        id,
                        currentUserService.currentUserIdOrNull(),
                        currentUserService.isCurrentUserAdmin()
                )
                .orElseThrow(() -> new IllegalArgumentException("Target DB connection not found: " + id));
    }

    public String decryptPassword(TargetDbConnectionRepository.TargetDbConnectionRecord record) {
        return passwordCrypto.decrypt(record.encryptedPassword());
    }

    public Connection openConnection(TargetDbConnectionRepository.TargetDbConnectionRecord record) throws SQLException {
        return openConnection(record.jdbcUrl(), record.username(), decryptPassword(record));
    }

    private Connection openConnection(String jdbcUrl, String username, String password) throws SQLException {
        DriverManager.setLoginTimeout(5);
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "5000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "10000");
        Connection connection = DriverManager.getConnection(jdbcUrl, properties);
        connection.setReadOnly(true);
        return connection;
    }

    private CapabilityProbe probeCapabilities(Connection connection) {
        List<String> capabilities = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        addCapability(connection, capabilities, warnings, "v$sqlarea", "SELECT 1 FROM v$sqlarea WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "v$sqlstats", "SELECT 1 FROM v$sqlstats WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "v$sql", "SELECT 1 FROM v$sql WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "v$sql_plan", "SELECT 1 FROM v$sql_plan WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "v$sql_plan_statistics_all", "SELECT 1 FROM v$sql_plan_statistics_all WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "v$sql_bind_capture", "SELECT 1 FROM v$sql_bind_capture WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "all_tables", "SELECT 1 FROM all_tables WHERE ROWNUM < 1");
        addCapability(connection, capabilities, warnings, "all_indexes", "SELECT 1 FROM all_indexes WHERE ROWNUM < 1");
        return new CapabilityProbe(capabilities, warnings);
    }

    private SqlTuningDtos.TargetDbConnectionTestResponse successfulTestResponse(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        CapabilityProbe capabilityProbe = probeCapabilities(connection);
        return new SqlTuningDtos.TargetDbConnectionTestResponse(
                true,
                "Connection succeeded.",
                metaData.getDatabaseProductName(),
                metaData.getDatabaseProductVersion(),
                capabilityProbe.capabilities(),
                capabilityProbe.warnings()
        );
    }

    private SqlTuningDtos.TargetDbConnectionTestResponse failedTestResponse(SQLException exception) {
        return new SqlTuningDtos.TargetDbConnectionTestResponse(
                false,
                exception.getMessage(),
                null,
                null,
                List.of(),
                List.of(exception.getMessage())
        );
    }

    private void addCapability(
            Connection connection,
            List<String> capabilities,
            List<String> warnings,
            String name,
            String sql
    ) {
        try {
            executeProbe(connection, sql);
            capabilities.add(name);
        } catch (SQLException exception) {
            warnings.add(name + " unavailable: " + exception.getMessage());
        }
    }

    private void executeProbe(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.executeQuery();
        }
    }

    private void requireManagePermission(TargetDbConnectionRepository.TargetDbConnectionRecord record) {
        if (currentUserService.isCurrentUserAdmin()) {
            return;
        }
        Long currentUserId = currentUserService.currentUserIdOrNull();
        if (currentUserId == null && record.ownerUserId() == null) {
            return;
        }
        if (currentUserId != null && currentUserId.equals(record.ownerUserId())) {
            return;
        }
        throw new AccessDeniedException("Target DB connection can only be changed by its owner or admin.");
    }

    private String normalizeDbType(String value) {
        String dbType = required(value, "DB type").toUpperCase();
        if (!"ORACLE".equals(dbType)) {
            throw new IllegalArgumentException("Only ORACLE target DB is supported in phase 3.");
        }
        return dbType;
    }

    private String normalizeVisibility(String value) {
        String visibility = value == null || value.isBlank() ? "PRIVATE" : value.trim().toUpperCase();
        if (!"PRIVATE".equals(visibility) && !"SHARED".equals(visibility)) {
            throw new IllegalArgumentException("Visibility must be PRIVATE or SHARED.");
        }
        return visibility;
    }

    private int normalizeInterval(Integer value) {
        if (value == null) {
            return DEFAULT_MONITORING_INTERVAL_SEC;
        }
        if (value < 60) {
            throw new IllegalArgumentException("Monitoring interval must be at least 60 seconds.");
        }
        return value;
    }

    private String required(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return value.trim();
    }
}

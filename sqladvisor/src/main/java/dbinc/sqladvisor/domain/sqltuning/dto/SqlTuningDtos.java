package dbinc.sqladvisor.domain.sqltuning.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;

import java.time.LocalDateTime;
import java.util.List;

public class SqlTuningDtos {

    public record TargetDbConnectionRequest(
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String password,
            String visibility,
            Boolean monitoringEnabled,
            Integer monitoringIntervalSec
    ) {
    }

    public record TargetDbConnectionResponse(
            Long id,
            String name,
            String dbType,
            String jdbcUrl,
            String username,
            String visibility,
            boolean monitoringEnabled,
            int monitoringIntervalSec,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetDbConnectionTestRequest(
            String dbType,
            String jdbcUrl,
            String username,
            String password
    ) {
    }

    public record TargetDbConnectionTestResponse(
            boolean success,
            String message,
            String databaseProductName,
            String databaseProductVersion,
            List<String> capabilities,
            List<String> warnings
    ) {
    }

    public record DirectTuningRequest(
            Long connectionId,
            String sqlId,
            String sqlText
    ) {
    }

    public record DirectTopSqlRequest(
            Long connectionId,
            String source,
            Integer limit,
            String sortBy,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String schema,
            String module,
            String program
    ) {
    }

    public record DirectDbContextResponse(
            Long connectionId,
            String connectionName,
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest input,
            List<String> warnings,
            LocalDateTime collectedAt
    ) {
    }
}

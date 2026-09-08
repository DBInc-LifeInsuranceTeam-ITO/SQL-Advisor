package dbinc.sqladvisor.domain.monitoring.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class MonitoringDtos {

    private MonitoringDtos() {
    }

    public record DashboardResponse(
            ConnectionSummary connection,
            Summary summary,
            Activity activity,
            List<PrioritySql> prioritySql,
            IssueSummary issues
    ) {
    }

    public record ConnectionSummary(
            long id,
            String name,
            String status,
            LocalDateTime collectedAt,
            String message
    ) {
    }

    public record Summary(
            long activeSqlCount,
            long longRunningSqlCount,
            long warningSqlCount,
            long blockingSessionCount
    ) {
    }

    public record Activity(
            List<ActivityPoint> points
    ) {
    }

    public record ActivityPoint(
            LocalDateTime collectedAt,
            long activeSessions,
            long lockSessions,
            double cpu,
            long io
    ) {
    }

    public record PrioritySql(
            String riskLevel,
            String riskLabel,
            String sqlId,
            String username,
            String module,
            long elapsedSec,
            double cpuPercent,
            long bufferGets,
            long diskReads,
            long executions,
            String issueType,
            String issueLabel,
            String waitEvent,
            String sqlText
    ) {
    }

    public record IssueSummary(
            long longRunning,
            long logicalReads,
            long physicalReads,
            long blocking
    ) {
    }
}

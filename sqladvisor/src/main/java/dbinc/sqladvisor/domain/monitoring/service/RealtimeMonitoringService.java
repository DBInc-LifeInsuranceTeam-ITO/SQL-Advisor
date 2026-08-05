package dbinc.sqladvisor.domain.monitoring.service;

import dbinc.sqladvisor.domain.monitoring.dto.MonitoringDtos;
import dbinc.sqladvisor.domain.sqltuning.service.TargetDbConnectionRepository;
import dbinc.sqladvisor.domain.sqltuning.service.TargetDbConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class RealtimeMonitoringService {

    private static final int LONG_RUNNING_SEC = 30;
    private static final long LOGICAL_READ_WARNING = 100_000L;
    private static final long PHYSICAL_READ_WARNING = 10_000L;
    private static final int MAX_POINTS = 12;

    private static final String ACTIVE_COUNT_SQL = """
            SELECT COUNT(*)
              FROM v$session s
             WHERE s.status = 'ACTIVE'
               AND s.type = 'USER'
               AND s.sql_id IS NOT NULL
               AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
            """;

    private static final String LONG_RUNNING_COUNT_SQL = """
            SELECT COUNT(*)
              FROM v$session s
             WHERE s.status = 'ACTIVE'
               AND s.type = 'USER'
               AND s.sql_id IS NOT NULL
               AND s.last_call_et >= ?
               AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
            """;

    private static final String BLOCKING_COUNT_SQL = """
            SELECT COUNT(*)
              FROM v$session s
             WHERE s.blocking_session IS NOT NULL
            """;

    private static final String TOTAL_METRIC_SQL = """
            SELECT NVL(SUM(executions), 0) executions,
                   NVL(SUM(cpu_time), 0) cpu_time,
                   NVL(SUM(disk_reads), 0) disk_reads
              FROM v$sqlstats
            """;

    private static final String PRIORITY_SQL = """
            SELECT *
              FROM (
                    SELECT s.sql_id,
                           NVL(s.username, '-') username,
                           NVL(s.module, NVL(s.program, '-')) module,
                           s.last_call_et elapsed_sec,
                           NVL(s.event, '-') wait_event,
                           s.blocking_session,
                           NVL(q.executions, 0) executions,
                           NVL(q.buffer_gets, 0) buffer_gets,
                           NVL(q.disk_reads, 0) disk_reads,
                           CASE
                               WHEN NVL(q.elapsed_time, 0) > 0
                               THEN ROUND((NVL(q.cpu_time, 0) / q.elapsed_time) * 100, 1)
                               ELSE 0
                           END cpu_percent,
                           SUBSTR(q.sql_text, 1, 500) sql_text
                      FROM v$session s
                      JOIN v$sqlstats q ON q.sql_id = s.sql_id
                     WHERE s.status = 'ACTIVE'
                       AND s.type = 'USER'
                       AND s.sql_id IS NOT NULL
                       AND s.sid <> SYS_CONTEXT('USERENV', 'SID')
                     ORDER BY CASE WHEN s.blocking_session IS NOT NULL THEN 1 ELSE 2 END,
                              s.last_call_et DESC,
                              q.buffer_gets DESC
                   )
             WHERE ROWNUM <= 20
            """;

    private final TargetDbConnectionService connectionService;
    private final Map<Long, Deque<MonitoringDtos.ActivityPoint>> history = new ConcurrentHashMap<>();
    private final Map<Long, RawTotals> previousTotals = new ConcurrentHashMap<>();

    public MonitoringDtos.DashboardResponse collect(long connectionId) {
        TargetDbConnectionRepository.TargetDbConnectionRecord target = connectionService.getVisibleRecord(connectionId);
        LocalDateTime collectedAt = LocalDateTime.now();

        try (Connection connection = connectionService.openConnection(target)) {
            long activeCount = scalar(connection, ACTIVE_COUNT_SQL);
            long longRunningCount = scalar(connection, LONG_RUNNING_COUNT_SQL, LONG_RUNNING_SEC);
            long blockingCount = scalar(connection, BLOCKING_COUNT_SQL);
            RawTotals totals = readTotals(connection);
            List<MonitoringDtos.PrioritySql> prioritySql = readPrioritySql(connection);

            long warningCount = prioritySql.stream()
                    .filter(item -> !"NORMAL".equals(item.riskLevel()))
                    .count();

            long logicalReadCount = prioritySql.stream()
                    .filter(item -> item.bufferGets() >= LOGICAL_READ_WARNING)
                    .count();
            long physicalReadCount = prioritySql.stream()
                    .filter(item -> item.diskReads() >= PHYSICAL_READ_WARNING)
                    .count();

            MonitoringDtos.ActivityPoint point = buildActivityPoint(connectionId, collectedAt, activeCount, totals);
            List<MonitoringDtos.ActivityPoint> points = appendHistory(connectionId, point);

            return new MonitoringDtos.DashboardResponse(
                    new MonitoringDtos.ConnectionSummary(
                            target.id(), target.name(), "NORMAL", collectedAt, "수집 정상"
                    ),
                    new MonitoringDtos.Summary(activeCount, longRunningCount, warningCount, blockingCount),
                    new MonitoringDtos.Activity(points),
                    prioritySql,
                    new MonitoringDtos.IssueSummary(
                            longRunningCount, logicalReadCount, physicalReadCount, blockingCount
                    )
            );
        } catch (SQLException exception) {
            throw new IllegalStateException("실시간 DB 모니터링 조회 실패: " + exception.getMessage(), exception);
        }
    }

    private MonitoringDtos.ActivityPoint buildActivityPoint(
            long connectionId,
            LocalDateTime collectedAt,
            long activeCount,
            RawTotals current
    ) {
        RawTotals previous = previousTotals.put(connectionId, current);
        if (previous == null) {
            return new MonitoringDtos.ActivityPoint(collectedAt, activeCount, 0, 0, 0);
        }

        long executionsDelta = nonNegative(current.executions() - previous.executions());
        long cpuMicrosDelta = nonNegative(current.cpuTimeMicros() - previous.cpuTimeMicros());
        long diskReadsDelta = nonNegative(current.diskReads() - previous.diskReads());

        double cpuSeconds = Math.round((cpuMicrosDelta / 1_000_000.0) * 10.0) / 10.0;
        return new MonitoringDtos.ActivityPoint(
                collectedAt,
                activeCount,
                executionsDelta,
                cpuSeconds,
                diskReadsDelta
        );
    }

    private List<MonitoringDtos.ActivityPoint> appendHistory(
            long connectionId,
            MonitoringDtos.ActivityPoint point
    ) {
        Deque<MonitoringDtos.ActivityPoint> points = history.computeIfAbsent(
                connectionId,
                ignored -> new ArrayDeque<>()
        );
        synchronized (points) {
            points.addLast(point);
            while (points.size() > MAX_POINTS) {
                points.removeFirst();
            }
            return List.copyOf(points);
        }
    }

    private List<MonitoringDtos.PrioritySql> readPrioritySql(Connection connection) throws SQLException {
        List<MonitoringDtos.PrioritySql> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PRIORITY_SQL)) {
            statement.setQueryTimeout(5);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long elapsed = rs.getLong("elapsed_sec");
                    long bufferGets = rs.getLong("buffer_gets");
                    long diskReads = rs.getLong("disk_reads");
                    boolean blocking = rs.getObject("blocking_session") != null;
                    Risk risk = classify(elapsed, bufferGets, diskReads, blocking);

                    result.add(new MonitoringDtos.PrioritySql(
                            risk.level(),
                            risk.label(),
                            rs.getString("sql_id"),
                            rs.getString("username"),
                            rs.getString("module"),
                            elapsed,
                            rs.getDouble("cpu_percent"),
                            bufferGets,
                            diskReads,
                            rs.getLong("executions"),
                            risk.issueType(),
                            risk.issueLabel(),
                            rs.getString("wait_event"),
                            rs.getString("sql_text")
                    ));
                }
            }
        }
        return result.stream()
                .sorted(Comparator.comparingInt(item -> riskOrder(item.riskLevel())))
                .limit(10)
                .toList();
    }

    private Risk classify(long elapsed, long bufferGets, long diskReads, boolean blocking) {
        if (blocking) {
            return new Risk("CRITICAL", "긴급", "BLOCKING", "Blocking 발생");
        }
        if (elapsed >= 180) {
            return new Risk("CRITICAL", "긴급", "LONG_RUNNING", "장기 실행");
        }
        if (bufferGets >= 200_000) {
            return new Risk("HIGH", "높음", "LOGICAL_READ", "Logical Read 과다");
        }
        if (diskReads >= PHYSICAL_READ_WARNING) {
            return new Risk("HIGH", "높음", "PHYSICAL_IO", "Physical I/O 과다");
        }
        if (elapsed >= LONG_RUNNING_SEC || bufferGets >= LOGICAL_READ_WARNING) {
            return new Risk("MEDIUM", "주의", "WARNING", elapsed >= LONG_RUNNING_SEC ? "장기 실행" : "Logical Read 증가");
        }
        return new Risk("NORMAL", "정상", "NORMAL", "관찰 대상");
    }

    private int riskOrder(String level) {
        return switch (level) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    private RawTotals readTotals(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TOTAL_METRIC_SQL)) {
            statement.setQueryTimeout(5);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new RawTotals(0, 0, 0);
                }
                return new RawTotals(
                        rs.getLong("executions"),
                        rs.getLong("cpu_time"),
                        rs.getLong("disk_reads")
                );
            }
        }
    }

    private long scalar(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private long nonNegative(long value) {
        return Math.max(value, 0);
    }

    private record RawTotals(long executions, long cpuTimeMicros, long diskReads) {
    }

    private record Risk(String level, String label, String issueType, String issueLabel) {
    }
}

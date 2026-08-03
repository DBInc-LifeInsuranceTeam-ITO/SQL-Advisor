package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.DirectSqlMetricDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class DirectSqlMetricService {

    private static final List<String> SYSTEM_SCHEMAS = List.of(
            "SYS", "SYSTEM", "DBSNMP", "SYSMAN", "OUTLN", "ORACLE_OCM"
    );

    private final TargetDbConnectionService connectionService;

    public DirectSqlMetricDtos.DirectSqlMetricListResponse topSql(
            long connectionId,
            Integer requestedLimit,
            String requestedSortBy,
            boolean includeSystemSql
    ) {
        int limit = normalizeLimit(requestedLimit);
        String orderColumn = normalizeSortColumn(requestedSortBy);
        TargetDbConnectionRepository.TargetDbConnectionRecord target = connectionService.getVisibleRecord(connectionId);
        List<String> warnings = new ArrayList<>();

        try (Connection connection = connectionService.openConnection(target)) {
            markAdvisorSession(connection, warnings);
            try {
                return query(connection, "gv$sql", limit, orderColumn, includeSystemSql, warnings);
            } catch (SQLException gvException) {
                warnings.add("gv$sql 조회 실패로 v$sql로 대체했습니다: " + gvException.getMessage());
                return query(connection, "v$sql", limit, orderColumn, includeSystemSql, warnings);
            }
        } catch (SQLException exception) {
            throw new IllegalArgumentException("상세 SQL 성능 지표 조회에 실패했습니다: " + exception.getMessage(), exception);
        }
    }

    private void markAdvisorSession(Connection connection, List<String> warnings) {
        try (CallableStatement statement = connection.prepareCall("BEGIN DBMS_APPLICATION_INFO.SET_MODULE(?, ?); END;")) {
            statement.setString(1, "SQL_ADVISOR");
            statement.setString(2, "TOP_SQL_METRICS");
            statement.execute();
        } catch (SQLException exception) {
            warnings.add("SQL Advisor 세션 태깅에 실패했습니다: " + exception.getMessage());
        }
    }

    private DirectSqlMetricDtos.DirectSqlMetricListResponse query(
            Connection connection,
            String viewName,
            int limit,
            String orderColumn,
            boolean includeSystemSql,
            List<String> warnings
    ) throws SQLException {
        String systemFilter = includeSystemSql
                ? ""
                : "AND parsing_schema_name NOT IN ('" + String.join("','", SYSTEM_SCHEMAS) + "')";

        String sql = """
                SELECT *
                  FROM (
                        SELECT sql_id,
                               plan_hash_value,
                               child_number,
                               parsing_schema_name,
                               module,
                               action,
                               service service_name,
                               executions,
                               elapsed_time / 1000000 total_elapsed_time_sec,
                               CASE WHEN executions > 0
                                    THEN elapsed_time / executions / 1000000
                               END average_elapsed_time_sec,
                               cpu_time / 1000000 total_cpu_time_sec,
                               CASE WHEN executions > 0
                                    THEN cpu_time / executions / 1000000
                               END average_cpu_time_sec,
                               buffer_gets,
                               CASE WHEN executions > 0
                                    THEN buffer_gets / executions
                               END average_buffer_gets,
                               disk_reads,
                               CASE WHEN executions > 0
                                    THEN disk_reads / executions
                               END average_disk_reads,
                               rows_processed,
                               first_load_time,
                               TO_CHAR(last_active_time, 'YYYY-MM-DD HH24:MI:SS') last_active_time,
                               sql_text
                          FROM %s
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           AND executions > 0
                           AND command_type IN (2, 3, 6, 7, 189)
                           %s
                           AND NVL(module, '-') NOT LIKE 'SQL_ADVISOR%%'
                           AND NVL(module, '-') NOT LIKE 'DBMS_SCHEDULER%%'
                           AND NOT (
                               NVL(module, '-') LIKE 'DBeaver%%'
                               AND NVL(module, '-') NOT LIKE 'DBeaver%%SQLEditor%%'
                           )
                           AND LOWER(sql_text) NOT LIKE '%% from gv$%%'
                           AND LOWER(sql_text) NOT LIKE '%% from v$%%'
                           AND LOWER(sql_text) NOT LIKE '%% join gv$%%'
                           AND LOWER(sql_text) NOT LIKE '%% join v$%%'
                           AND NOT (
                               LOWER(sql_text) LIKE '%%average_elapsed_time_sec%%'
                               AND LOWER(sql_text) LIKE '%%average_buffer_gets%%'
                           )
                           AND LOWER(sql_text) NOT LIKE '%%dbms_xplan%%'
                           AND LOWER(sql_text) NOT LIKE '%%exec_from_dbms_xplan%%'
                           AND LOWER(sql_text) NOT LIKE '%%parallel_execution_enabled%%'
                           AND LOWER(sql_text) NOT LIKE '%%sys.dbms_xplan_type_table%%'
                           AND LOWER(sql_text) NOT LIKE '%% from dba_%%'
                           AND LOWER(sql_text) NOT LIKE '%% join dba_%%'
                           AND LOWER(sql_text) NOT LIKE '%% from all_%%'
                           AND LOWER(sql_text) NOT LIKE '%% join all_%%'
                           AND LOWER(sql_text) NOT LIKE '%%xmlsequence%%'
                           AND NOT (
                               LOWER(sql_text) LIKE '%%extract(xmlval%%'
                               AND LOWER(sql_text) LIKE '%%from sys.dual%%'
                           )
                           AND NOT (
                               LOWER(sql_text) LIKE '%%sys_context%%'
                               AND LOWER(sql_text) LIKE '%%userenv%%'
                           )
                         ORDER BY %s DESC, last_active_time DESC NULLS LAST
                       )
                 WHERE ROWNUM <= ?
                """.formatted(viewName, systemFilter, orderColumn);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<DirectSqlMetricDtos.DirectSqlMetricResponse> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(map(resultSet));
                }
                return new DirectSqlMetricDtos.DirectSqlMetricListResponse(viewName, rows, List.copyOf(warnings));
            }
        }
    }

    private DirectSqlMetricDtos.DirectSqlMetricResponse map(ResultSet resultSet) throws SQLException {
        return new DirectSqlMetricDtos.DirectSqlMetricResponse(
                resultSet.getString("sql_id"),
                nullableLong(resultSet, "plan_hash_value"),
                nullableInteger(resultSet, "child_number"),
                resultSet.getString("parsing_schema_name"),
                resultSet.getString("module"),
                resultSet.getString("action"),
                resultSet.getString("service_name"),
                nullableLong(resultSet, "executions"),
                nullableDouble(resultSet, "total_elapsed_time_sec"),
                nullableDouble(resultSet, "average_elapsed_time_sec"),
                nullableDouble(resultSet, "total_cpu_time_sec"),
                nullableDouble(resultSet, "average_cpu_time_sec"),
                nullableLong(resultSet, "buffer_gets"),
                nullableDouble(resultSet, "average_buffer_gets"),
                nullableLong(resultSet, "disk_reads"),
                nullableDouble(resultSet, "average_disk_reads"),
                nullableLong(resultSet, "rows_processed"),
                resultSet.getString("first_load_time"),
                resultSet.getString("last_active_time"),
                resultSet.getString("sql_text")
        );
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit == 20 || limit == 50 || limit == 100) {
            return limit;
        }
        throw new IllegalArgumentException("조회 건수는 20, 50, 100만 사용할 수 있습니다.");
    }

    private String normalizeSortColumn(String sortBy) {
        String normalized = sortBy == null || sortBy.isBlank()
                ? "TOTAL_ELAPSED_TIME"
                : sortBy.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "AVERAGE_ELAPSED_TIME" -> "average_elapsed_time_sec";
            case "TOTAL_CPU_TIME" -> "total_cpu_time_sec";
            case "AVERAGE_CPU_TIME" -> "average_cpu_time_sec";
            case "BUFFER_GETS" -> "buffer_gets";
            case "AVERAGE_BUFFER_GETS" -> "average_buffer_gets";
            case "DISK_READS" -> "disk_reads";
            case "AVERAGE_DISK_READS" -> "average_disk_reads";
            case "EXECUTIONS" -> "executions";
            case "TOTAL_ELAPSED_TIME" -> "total_elapsed_time_sec";
            default -> throw new IllegalArgumentException("지원하지 않는 SQL 정렬 기준입니다: " + sortBy);
        };
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }
}

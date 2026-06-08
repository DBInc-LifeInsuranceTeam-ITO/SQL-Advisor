package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TargetDbContextCollector {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_.$#\"]+)"
    );
    private static final Set<String> SKIP_TABLE_TOKENS = Set.of(
            "select", "where", "group", "order", "having", "connect", "union", "minus", "intersect"
    );

    private record SqlChildCursor(Integer childNumber, Long planHashValue, String module, String lastActiveTime) {
    }

    private record TopSqlOptions(
            String source,
            int limit,
            String sortBy,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String schema,
            String module,
            String program
    ) {
        private static TopSqlOptions from(SqlTuningDtos.DirectTopSqlRequest request) {
            String source = normalizeSource(request == null ? null : request.source());
            int limit = normalizeLimit(request == null ? null : request.limit());
            String sortBy = normalizeSortBy(request == null ? null : request.sortBy());
            LocalDateTime startTime = request == null ? null : request.startTime();
            LocalDateTime endTime = request == null ? null : request.endTime();
            if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
                throw new IllegalArgumentException("Top SQL start time must be before end time.");
            }
            return new TopSqlOptions(
                    source,
                    limit,
                    sortBy,
                    startTime,
                    endTime,
                    blankToNull(request == null ? null : request.schema()),
                    blankToNull(request == null ? null : request.module()),
                    blankToNull(request == null ? null : request.program())
            );
        }

        private boolean historical() {
            return "HISTORY".equals(source);
        }

        private boolean hasCurrentMetadataFilters() {
            return schema != null || module != null || program != null;
        }

        private static String normalizeSource(String value) {
            String normalized = value == null || value.isBlank() ? "CURRENT" : value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "CURRENT", "HISTORY" -> normalized;
                default -> throw new IllegalArgumentException("Top SQL source must be CURRENT or HISTORY.");
            };
        }

        private static int normalizeLimit(Integer value) {
            if (value == null) {
                return 20;
            }
            if (value == 20 || value == 50 || value == 100) {
                return value;
            }
            throw new IllegalArgumentException("Top SQL limit must be 20, 50, or 100.");
        }

        private static String normalizeSortBy(String value) {
            String normalized = value == null || value.isBlank() ? "ELAPSED" : value.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "ELAPSED", "BUFFER_GETS", "DISK_READS", "EXECUTIONS" -> normalized;
                default -> throw new IllegalArgumentException("Top SQL sort must be ELAPSED, BUFFER_GETS, DISK_READS, or EXECUTIONS.");
            };
        }
    }

    private final TargetDbConnectionService connectionService;

    public SqlTuningDtos.DirectDbContextResponse collect(
            TargetDbConnectionRepository.TargetDbConnectionRecord target,
            SqlTuningDtos.DirectTuningRequest request
    ) {
        if (request == null || request.connectionId() == null) {
            throw new IllegalArgumentException("Target DB connection is required.");
        }
        String sqlId = blankToNull(request.sqlId());
        String requestedSqlText = blankToNull(request.sqlText());
        List<String> warnings = new ArrayList<>();

        try (Connection connection = connectionService.openConnection(target)) {
            AwrDtos.SqlMetricResponse metric = sqlId == null
                    ? null
                    : findSqlMetric(connection, sqlId, warnings);
            String sqlText = metric == null || metric.sqlText() == null || metric.sqlText().isBlank()
                    ? requestedSqlText
                    : metric.sqlText();
            if (sqlText == null || sqlText.isBlank()) {
                throw new IllegalArgumentException("SQL_ID did not resolve to SQL text. Provide SQL text fallback.");
            }
            if (metric == null) {
                metric = manualMetric(sqlId == null ? "direct-" + Math.abs(sqlText.hashCode()) : sqlId, sqlText);
            }

            List<String> tables = extractTableNames(sqlText);
            SqlChildCursor childCursor = sqlId == null ? null : chooseChildCursor(connection, sqlId, warnings);
            String executionPlan = sqlId == null
                    ? null
                    : executionPlanContext(connection, sqlId, childCursor, warnings);
            String schemaDdl = joinSections(
                    schemaDdl(connection, tables, warnings),
                    tableStatistics(connection, tables, warnings),
                    tableLoadStatistics(connection, tables, warnings)
            );
            String existingIndexes = existingIndexes(connection, tables, warnings);
            String bindSamples = sqlId == null ? null : bindSamples(connection, sqlId, warnings);
            AwrDtos.SqlTuningRequest input = new AwrDtos.SqlTuningRequest(
                    sqlText,
                    "Tune SQL from direct database context and recommend safe index candidates considering table volume and load/write volume.",
                    executionPlan,
                    schemaDdl,
                    existingIndexes,
                    bindSamples
            );
            return new SqlTuningDtos.DirectDbContextResponse(
                    target.id(),
                    target.name(),
                    metric,
                    input,
                    warnings,
                    LocalDateTime.now()
            );
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Target DB context collection failed: " + exception.getMessage(), exception);
        }
    }

    public List<AwrDtos.SqlMetricResponse> topSql(TargetDbConnectionRepository.TargetDbConnectionRecord target) {
        return topSql(target, null);
    }

    public List<AwrDtos.SqlMetricResponse> topSql(
            TargetDbConnectionRepository.TargetDbConnectionRecord target,
            SqlTuningDtos.DirectTopSqlRequest request
    ) {
        TopSqlOptions options = TopSqlOptions.from(request);
        try (Connection connection = connectionService.openConnection(target)) {
            if (options.historical()) {
                return topSqlFromHistory(connection, options);
            }
            SQLException currentSourceException = null;
            try {
                List<AwrDtos.SqlMetricResponse> sqlAreaRows = topSqlFromSqlArea(connection, options);
                if (!sqlAreaRows.isEmpty()) {
                    return sqlAreaRows;
                }
            } catch (SQLException sqlAreaException) {
                currentSourceException = sqlAreaException;
            }
            if (!options.hasCurrentMetadataFilters()) {
                try {
                    List<AwrDtos.SqlMetricResponse> sqlStatsRows = topSqlFromSqlStats(connection, options);
                    if (!sqlStatsRows.isEmpty()) {
                        return sqlStatsRows;
                    }
                } catch (SQLException sqlStatsException) {
                    currentSourceException = sqlStatsException;
                }
            }
            try {
                List<AwrDtos.SqlMetricResponse> sqlRows = topSqlFromSql(connection, options);
                if (!sqlRows.isEmpty()) {
                    return sqlRows;
                }
            } catch (SQLException sqlException) {
                if (currentSourceException != null) {
                    throw currentSourceException;
                }
                throw sqlException;
            }
            return List.of();
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Target DB Top SQL collection failed: " + exception.getMessage(), exception);
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSqlArea(Connection connection, TopSqlOptions options) throws SQLException {
        List<Object> params = new ArrayList<>();
        String metadataFilters = currentMetadataFilters(options, params, "parsing_schema_name", "module", "action");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT sql_id,
                               parsing_schema_name,
                               elapsed_time / 1000000 elapsed_time_sec,
                               cpu_time / 1000000 cpu_time_sec,
                               buffer_gets,
                               disk_reads,
                               executions,
                               rows_processed,
                               plan_hash_value,
                               module,
                               sql_text
                          FROM v$sqlarea
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           AND parsing_schema_name IS NOT NULL
                           AND parsing_schema_name NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN', 'OUTLN')
                           AND command_type IN (2, 3, 6, 7, 189)
                           AND LOWER(sql_text) NOT LIKE '%%v$sql%%'
                           AND LOWER(sql_text) NOT LIKE '%%dbms_xplan%%'
                           AND LOWER(sql_text) NOT LIKE '%%all_tab_columns%%'
                           AND LOWER(sql_text) NOT LIKE '%%all_ind_columns%%'
                           AND LOWER(sql_text) NOT LIKE '%%v$sql_bind_capture%%'
                           %s
                         ORDER BY %s DESC
                       )
                 WHERE ROWNUM <= %d
                """.formatted(metadataFilters, currentOrderExpression(options.sortBy(), ""), options.limit()))) {
            bindParams(statement, params);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Top SQL", "Collected from target database v$sqlarea aggregate, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
            }
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSqlStats(Connection connection, TopSqlOptions options) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT sql_id,
                               elapsed_time / 1000000 elapsed_time_sec,
                               cpu_time / 1000000 cpu_time_sec,
                               buffer_gets,
                               disk_reads,
                               executions,
                               rows_processed,
                               plan_hash_value,
                               sql_text
                          FROM v$sqlstats
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           AND LOWER(sql_text) NOT LIKE '%%v$sql%%'
                           AND LOWER(sql_text) NOT LIKE '%%dbms_xplan%%'
                           AND LOWER(sql_text) NOT LIKE '%%all_tab_columns%%'
                           AND LOWER(sql_text) NOT LIKE '%%all_ind_columns%%'
                           AND LOWER(sql_text) NOT LIKE '%%v$sql_bind_capture%%'
                         ORDER BY %s DESC
                       )
                 WHERE ROWNUM <= %d
                """.formatted(currentOrderExpression(options.sortBy(), ""), options.limit()))) {
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Top SQL", "Collected from target database v$sqlstats aggregate, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
            }
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSql(Connection connection, TopSqlOptions options) throws SQLException {
        List<Object> params = new ArrayList<>();
        String metadataFilters = currentMetadataFilters(options, params, "parsing_schema_name", "module", "action");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT sql_id,
                               parsing_schema_name,
                               SUM(elapsed_time) / 1000000 elapsed_time_sec,
                               SUM(cpu_time) / 1000000 cpu_time_sec,
                               SUM(buffer_gets) buffer_gets,
                               SUM(disk_reads) disk_reads,
                               SUM(executions) executions,
                               SUM(rows_processed) rows_processed,
                               MAX(plan_hash_value) plan_hash_value,
                               MAX(module) KEEP (DENSE_RANK LAST ORDER BY last_active_time) module,
                               MAX(sql_text) KEEP (DENSE_RANK LAST ORDER BY last_active_time) sql_text
                          FROM v$sql
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           AND parsing_schema_name IS NOT NULL
                           AND parsing_schema_name NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN', 'OUTLN')
                           AND command_type IN (2, 3, 6, 7, 189)
                           AND LOWER(sql_text) NOT LIKE '%%v$sql%%'
                           AND LOWER(sql_text) NOT LIKE '%%dbms_xplan%%'
                           AND LOWER(sql_text) NOT LIKE '%%all_tab_columns%%'
                           AND LOWER(sql_text) NOT LIKE '%%all_ind_columns%%'
                           AND LOWER(sql_text) NOT LIKE '%%v$sql_bind_capture%%'
                           %s
                         GROUP BY sql_id, parsing_schema_name
                         ORDER BY %s DESC
                       )
                 WHERE ROWNUM <= %d
                """.formatted(metadataFilters, currentOrderExpression(options.sortBy(), "SUM"), options.limit()))) {
            bindParams(statement, params);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Top SQL", "Collected from target database v$sql child cursor aggregate, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
            }
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromHistory(Connection connection, TopSqlOptions options) throws SQLException {
        List<Object> params = new ArrayList<>();
        String historyFilters = historyFilters(options, params);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.sql_id,
                       MAX(s.parsing_schema_name) KEEP (DENSE_RANK LAST ORDER BY sn.end_interval_time) parsing_schema_name,
                       SUM(s.elapsed_time_delta) / 1000000 elapsed_time_sec,
                       SUM(s.cpu_time_delta) / 1000000 cpu_time_sec,
                       SUM(s.buffer_gets_delta) buffer_gets,
                       SUM(s.disk_reads_delta) disk_reads,
                       SUM(s.executions_delta) executions,
                       SUM(s.rows_processed_delta) rows_processed,
                       MAX(s.plan_hash_value) KEEP (DENSE_RANK LAST ORDER BY s.elapsed_time_delta) plan_hash_value,
                       MAX(s.module) KEEP (DENSE_RANK LAST ORDER BY sn.end_interval_time) module,
                       DBMS_LOB.SUBSTR(t.sql_text, 1000, 1) sql_text
                  FROM dba_hist_sqlstat s
                  JOIN dba_hist_snapshot sn
                    ON sn.dbid = s.dbid
                   AND sn.instance_number = s.instance_number
                   AND sn.snap_id = s.snap_id
                  JOIN dba_hist_sqltext t
                    ON t.dbid = s.dbid
                   AND t.sql_id = s.sql_id
                 WHERE s.sql_id IS NOT NULL
                   AND t.sql_text IS NOT NULL
                   AND s.parsing_schema_name IS NOT NULL
                   AND s.parsing_schema_name NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN', 'OUTLN')
                   AND t.command_type IN (2, 3, 6, 7, 189)
                   AND LOWER(DBMS_LOB.SUBSTR(t.sql_text, 1000, 1)) NOT LIKE '%%dba_hist_sqlstat%%'
                   AND LOWER(DBMS_LOB.SUBSTR(t.sql_text, 1000, 1)) NOT LIKE '%%v$sql%%'
                   AND LOWER(DBMS_LOB.SUBSTR(t.sql_text, 1000, 1)) NOT LIKE '%%dbms_xplan%%'
                   %s
                 GROUP BY s.sql_id,
                          DBMS_LOB.SUBSTR(t.sql_text, 1000, 1)
                 ORDER BY %s DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(historyFilters, historyOrderExpression(options.sortBy()), options.limit()))) {
            bindParams(statement, params);
            statement.setQueryTimeout(20);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Historical SQL", "Collected from DBA_HIST_SQLSTAT, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
            }
        }
    }

    private List<AwrDtos.SqlMetricResponse> mapTopSql(ResultSet rs, String sectionName, String hint) throws SQLException {
        List<AwrDtos.SqlMetricResponse> results = new ArrayList<>();
        int rank = 1;
        while (rs.next()) {
            Double elapsedTimeSec = getDouble(rs, "elapsed_time_sec");
            Long bufferGets = getLong(rs, "buffer_gets");
            Long diskReads = getLong(rs, "disk_reads");
            results.add(new AwrDtos.SqlMetricResponse(
                    rs.getString("sql_id"),
                    sectionName,
                    rank++,
                    elapsedTimeSec,
                    getDouble(rs, "cpu_time_sec"),
                    bufferGets,
                    diskReads,
                    getLong(rs, "executions"),
                    getLong(rs, "rows_processed"),
                    getLong(rs, "plan_hash_value"),
                    getStringIfPresent(rs, "module"),
                    rs.getString("sql_text"),
                    score(elapsedTimeSec, bufferGets, diskReads),
                    hint
            ));
        }
        return results;
    }

    private String currentOrderExpression(String sortBy, String aggregateFunction) {
        String column = switch (sortBy) {
            case "BUFFER_GETS" -> "buffer_gets";
            case "DISK_READS" -> "disk_reads";
            case "EXECUTIONS" -> "executions";
            default -> "elapsed_time";
        };
        return hasText(aggregateFunction) ? aggregateFunction + "(" + column + ")" : column;
    }

    private String historyOrderExpression(String sortBy) {
        return switch (sortBy) {
            case "BUFFER_GETS" -> "SUM(s.buffer_gets_delta)";
            case "DISK_READS" -> "SUM(s.disk_reads_delta)";
            case "EXECUTIONS" -> "SUM(s.executions_delta)";
            default -> "SUM(s.elapsed_time_delta)";
        };
    }

    private String currentMetadataFilters(
            TopSqlOptions options,
            List<Object> params,
            String schemaColumn,
            String moduleColumn,
            String actionColumn
    ) {
        StringBuilder filters = new StringBuilder();
        appendTextFilter(filters, params, schemaColumn, options.schema());
        appendTextFilter(filters, params, moduleColumn, options.module());
        appendTextFilter(filters, params, actionColumn, options.program());
        return filters.toString();
    }

    private String historyFilters(TopSqlOptions options, List<Object> params) {
        StringBuilder filters = new StringBuilder();
        if (options.startTime() != null) {
            filters.append(System.lineSeparator()).append("                   AND sn.end_interval_time >= ?");
            params.add(Timestamp.valueOf(options.startTime()));
        }
        if (options.endTime() != null) {
            filters.append(System.lineSeparator()).append("                   AND sn.begin_interval_time <= ?");
            params.add(Timestamp.valueOf(options.endTime()));
        }
        appendTextFilter(filters, params, "s.parsing_schema_name", options.schema());
        appendTextFilter(filters, params, "s.module", options.module());
        appendTextFilter(filters, params, "s.action", options.program());
        return filters.toString();
    }

    private void appendTextFilter(StringBuilder filters, List<Object> params, String column, String value) {
        if (!hasText(value)) {
            return;
        }
        filters.append(System.lineSeparator()).append("                   AND UPPER(").append(column).append(") LIKE ?");
        params.add("%" + value.toUpperCase(Locale.ROOT) + "%");
    }

    private void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Timestamp timestamp) {
                statement.setTimestamp(i + 1, timestamp);
            } else {
                statement.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private AwrDtos.SqlMetricResponse findSqlMetric(Connection connection, String sqlId, List<String> warnings) {
        List<String> lookupWarnings = new ArrayList<>();
        AwrDtos.SqlMetricResponse metric = findSqlAreaMetric(connection, sqlId, lookupWarnings);
        if (metric != null) {
            return metric;
        }
        metric = findSqlStatsMetric(connection, sqlId, lookupWarnings);
        if (metric != null) {
            return metric;
        }
        metric = findSqlChildMetric(connection, sqlId, lookupWarnings);
        if (metric != null) {
            return metric;
        }
        warnings.addAll(lookupWarnings);
        return null;
    }

    private AwrDtos.SqlMetricResponse findSqlAreaMetric(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sql_id,
                       parsing_schema_name,
                       elapsed_time / 1000000 elapsed_time_sec,
                       cpu_time / 1000000 cpu_time_sec,
                       buffer_gets,
                       disk_reads,
                       executions,
                       rows_processed,
                       plan_hash_value,
                       module,
                       sql_text
                  FROM v$sqlarea
                 WHERE sql_id = ?
                   AND ROWNUM <= 1
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add("v$sqlarea did not contain SQL_ID " + sqlId + ".");
                    return null;
                }
                Double elapsedTimeSec = getDouble(rs, "elapsed_time_sec");
                Long bufferGets = getLong(rs, "buffer_gets");
                Long diskReads = getLong(rs, "disk_reads");
                return new AwrDtos.SqlMetricResponse(
                        rs.getString("sql_id"),
                        "Direct DB SQL",
                        0,
                        elapsedTimeSec,
                        getDouble(rs, "cpu_time_sec"),
                        bufferGets,
                        diskReads,
                        getLong(rs, "executions"),
                        getLong(rs, "rows_processed"),
                        getLong(rs, "plan_hash_value"),
                        rs.getString("module"),
                        rs.getString("sql_text"),
                        score(elapsedTimeSec, bufferGets, diskReads),
                        "Collected from target database v$sqlarea aggregate."
                );
            }
        } catch (SQLException exception) {
            warnings.add("v$sqlarea query failed: " + exception.getMessage());
            return null;
        }
    }

    private AwrDtos.SqlMetricResponse findSqlStatsMetric(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sql_id,
                       elapsed_time / 1000000 elapsed_time_sec,
                       cpu_time / 1000000 cpu_time_sec,
                       buffer_gets,
                       disk_reads,
                       executions,
                       rows_processed,
                       plan_hash_value,
                       sql_text
                  FROM v$sqlstats
                 WHERE sql_id = ?
                   AND ROWNUM <= 1
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add("v$sqlstats did not contain SQL_ID " + sqlId + ".");
                    return null;
                }
                Double elapsedTimeSec = getDouble(rs, "elapsed_time_sec");
                Long bufferGets = getLong(rs, "buffer_gets");
                Long diskReads = getLong(rs, "disk_reads");
                return new AwrDtos.SqlMetricResponse(
                        rs.getString("sql_id"),
                        "Direct DB SQL",
                        0,
                        elapsedTimeSec,
                        getDouble(rs, "cpu_time_sec"),
                        bufferGets,
                        diskReads,
                        getLong(rs, "executions"),
                        getLong(rs, "rows_processed"),
                        getLong(rs, "plan_hash_value"),
                        null,
                        rs.getString("sql_text"),
                        score(elapsedTimeSec, bufferGets, diskReads),
                        "Collected from target database v$sqlstats aggregate."
                );
            }
        } catch (SQLException exception) {
            warnings.add("v$sqlstats query failed: " + exception.getMessage());
            return null;
        }
    }

    private AwrDtos.SqlMetricResponse findSqlChildMetric(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT sql_id,
                               parsing_schema_name,
                               elapsed_time / 1000000 elapsed_time_sec,
                               cpu_time / 1000000 cpu_time_sec,
                               buffer_gets,
                               disk_reads,
                               executions,
                               rows_processed,
                               plan_hash_value,
                               module,
                               sql_text
                          FROM v$sql
                         WHERE sql_id = ?
                         ORDER BY elapsed_time DESC, last_active_time DESC NULLS LAST
                       )
                 WHERE ROWNUM <= 1
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add("v$sql did not contain SQL_ID " + sqlId + ".");
                    return null;
                }
                Double elapsedTimeSec = getDouble(rs, "elapsed_time_sec");
                Long bufferGets = getLong(rs, "buffer_gets");
                Long diskReads = getLong(rs, "disk_reads");
                return new AwrDtos.SqlMetricResponse(
                        rs.getString("sql_id"),
                        "Direct DB SQL",
                        0,
                        elapsedTimeSec,
                        getDouble(rs, "cpu_time_sec"),
                        bufferGets,
                        diskReads,
                        getLong(rs, "executions"),
                        getLong(rs, "rows_processed"),
                        getLong(rs, "plan_hash_value"),
                        rs.getString("module"),
                        rs.getString("sql_text"),
                        score(elapsedTimeSec, bufferGets, diskReads),
                        "Collected from target database v$sql child cursor."
                );
            }
        } catch (SQLException exception) {
            warnings.add("v$sql query failed: " + exception.getMessage());
            return null;
        }
    }

    private AwrDtos.SqlMetricResponse manualMetric(String sqlId, String sqlText) {
        return new AwrDtos.SqlMetricResponse(
                sqlId,
                "Direct DB SQL",
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sqlText,
                null,
                "SQL text supplied by user; runtime metrics were not collected."
        );
    }

    private SqlChildCursor chooseChildCursor(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT child_number,
                               plan_hash_value,
                               module,
                               TO_CHAR(last_active_time, 'YYYY-MM-DD HH24:MI:SS') last_active_time
                          FROM v$sql
                         WHERE sql_id = ?
                         ORDER BY elapsed_time DESC, last_active_time DESC NULLS LAST, child_number DESC
                       )
                 WHERE ROWNUM <= 1
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add("No child cursor found in v$sql for SQL_ID " + sqlId + ".");
                    return null;
                }
                return new SqlChildCursor(
                        rs.getInt("child_number"),
                        getLong(rs, "plan_hash_value"),
                        rs.getString("module"),
                        rs.getString("last_active_time")
                );
            }
        } catch (SQLException exception) {
            warnings.add("Child cursor selection failed: " + exception.getMessage());
            return null;
        }
    }

    private String executionPlanContext(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        return joinSections(
                childCursor == null
                        ? null
                        : section("Chosen child cursor", "child_number=" + childCursor.childNumber()
                        + ", plan_hash_value=" + childCursor.planHashValue()
                        + ", module=" + stringValue(childCursor.module())
                        + ", last_active_time=" + stringValue(childCursor.lastActiveTime())),
                section("DBMS_XPLAN.DISPLAY_CURSOR", displayCursor(connection, sqlId, childCursor, warnings)),
                section("V$SQL_PLAN", sqlPlan(connection, sqlId, childCursor, warnings)),
                section("V$SQL_PLAN_STATISTICS_ALL", planStatistics(connection, sqlId, childCursor, warnings))
        );
    }

    private String displayCursor(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT plan_table_output
                  FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'ALLSTATS LAST +PREDICATE +PEEKED_BINDS +ALIAS'))
                """)) {
            statement.setString(1, sqlId);
            if (childCursor == null || childCursor.childNumber() == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setInt(2, childCursor.childNumber());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(rs.getString(1));
                }
                return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
            }
        } catch (SQLException exception) {
            warnings.add("DBMS_XPLAN query failed: " + exception.getMessage());
            return null;
        }
    }

    private String sqlPlan(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        String childClause = childCursor == null || childCursor.childNumber() == null ? "" : " AND child_number = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,
                       parent_id,
                       operation,
                       options,
                       object_owner,
                       object_name,
                       access_predicates,
                       filter_predicates
                  FROM v$sql_plan
                 WHERE sql_id = ?
                %s
                 ORDER BY child_number, id
                """.formatted(childClause))) {
            statement.setString(1, sqlId);
            if (!childClause.isBlank()) {
                statement.setInt(2, childCursor.childNumber());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    List<String> parts = new ArrayList<>();
                    parts.add("#" + rs.getInt("id"));
                    parts.add("parent=" + rs.getInt("parent_id"));
                    parts.add(stringValue(rs.getString("operation")) + " " + stringValue(rs.getString("options")));
                    if (hasText(rs.getString("object_name"))) {
                        parts.add("object=" + stringValue(rs.getString("object_owner")) + "." + rs.getString("object_name"));
                    }
                    if (hasText(rs.getString("access_predicates"))) {
                        parts.add("access=" + rs.getString("access_predicates"));
                    }
                    if (hasText(rs.getString("filter_predicates"))) {
                        parts.add("filter=" + rs.getString("filter_predicates"));
                    }
                    lines.add(String.join(" | ", parts));
                }
                return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
            }
        } catch (SQLException exception) {
            warnings.add("v$sql_plan query failed: " + exception.getMessage());
            return null;
        }
    }

    private String planStatistics(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        String childClause = childCursor == null || childCursor.childNumber() == null ? "" : " AND child_number = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,
                       operation,
                       options,
                       object_name,
                       starts,
                       output_rows,
                       cr_buffer_gets,
                       disk_reads,
                       last_starts,
                       last_output_rows,
                       last_cr_buffer_gets,
                       last_disk_reads
                  FROM v$sql_plan_statistics_all
                 WHERE sql_id = ?
                %s
                 ORDER BY child_number, id
                """.formatted(childClause))) {
            statement.setString(1, sqlId);
            if (!childClause.isBlank()) {
                statement.setInt(2, childCursor.childNumber());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add("#" + rs.getInt("id")
                            + " " + stringValue(rs.getString("operation")) + " " + stringValue(rs.getString("options"))
                            + " object=" + stringValue(rs.getString("object_name"))
                            + " starts=" + stringValue(getLong(rs, "starts"))
                            + " rows=" + stringValue(getLong(rs, "output_rows"))
                            + " cr_gets=" + stringValue(getLong(rs, "cr_buffer_gets"))
                            + " disk_reads=" + stringValue(getLong(rs, "disk_reads"))
                            + " last_starts=" + stringValue(getLong(rs, "last_starts"))
                            + " last_rows=" + stringValue(getLong(rs, "last_output_rows"))
                            + " last_cr_gets=" + stringValue(getLong(rs, "last_cr_buffer_gets"))
                            + " last_disk_reads=" + stringValue(getLong(rs, "last_disk_reads")));
                }
                return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
            }
        } catch (SQLException exception) {
            warnings.add("v$sql_plan_statistics_all query failed: " + exception.getMessage());
            return null;
        }
    }

    private String schemaDdl(Connection connection, List<String> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner,
                       table_name,
                       column_name,
                       data_type,
                       data_length,
                       data_precision,
                       data_scale,
                       nullable,
                       num_distinct,
                       num_nulls,
                       histogram,
                       last_analyzed
                  FROM all_tab_columns
                 WHERE table_name IN (%s)
                 ORDER BY owner, table_name, column_id
                """.formatted(placeholders(tables.size())))) {
            bindTables(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
                while (rs.next()) {
                    String table = rs.getString("owner") + "." + rs.getString("table_name");
                    List<String> notes = new ArrayList<>();
                    if (getLong(rs, "num_distinct") != null) {
                        notes.add("num_distinct=" + getLong(rs, "num_distinct"));
                    }
                    if (getLong(rs, "num_nulls") != null) {
                        notes.add("num_nulls=" + getLong(rs, "num_nulls"));
                    }
                    if (hasText(rs.getString("histogram"))) {
                        notes.add("histogram=" + rs.getString("histogram"));
                    }
                    if (rs.getObject("last_analyzed") != null) {
                        notes.add("last_analyzed=" + rs.getObject("last_analyzed"));
                    }
                    String columnLine = "  " + rs.getString("column_name") + " " + formatDataType(rs)
                            + ("N".equals(rs.getString("nullable")) ? " NOT NULL" : "");
                    if (!notes.isEmpty()) {
                        columnLine += " -- " + String.join(", ", notes);
                    }
                    columnsByTable.computeIfAbsent(table, ignored -> new ArrayList<>()).add(columnLine);
                }
                if (columnsByTable.isEmpty()) {
                    return null;
                }
                List<String> blocks = new ArrayList<>();
                columnsByTable.forEach((table, columns) ->
                        blocks.add("CREATE TABLE " + table + " (" + System.lineSeparator()
                                + String.join("," + System.lineSeparator(), columns)
                                + System.lineSeparator() + ");")
                );
                return String.join(System.lineSeparator() + System.lineSeparator(), blocks);
            }
        } catch (SQLException exception) {
            warnings.add("Table column metadata query failed: " + exception.getMessage());
            return null;
        }
    }

    private String tableStatistics(Connection connection, List<String> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner,
                       table_name,
                       num_rows,
                       blocks,
                       avg_row_len,
                       sample_size,
                       last_analyzed,
                       partitioned,
                       temporary
                  FROM all_tables
                 WHERE table_name IN (%s)
                 ORDER BY owner, table_name
                """.formatted(placeholders(tables.size())))) {
            bindTables(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(rs.getString("owner") + "." + rs.getString("table_name")
                            + " num_rows=" + stringValue(getLong(rs, "num_rows"))
                            + ", blocks=" + stringValue(getLong(rs, "blocks"))
                            + ", avg_row_len=" + stringValue(getLong(rs, "avg_row_len"))
                            + ", sample_size=" + stringValue(getLong(rs, "sample_size"))
                            + ", last_analyzed=" + stringValue(rs.getObject("last_analyzed"))
                            + ", partitioned=" + stringValue(rs.getString("partitioned"))
                            + ", temporary=" + stringValue(rs.getString("temporary")));
                }
                return lines.isEmpty() ? null : section("Table statistics", String.join(System.lineSeparator(), lines));
            }
        } catch (SQLException exception) {
            warnings.add("all_tables statistics query failed: " + exception.getMessage());
            return null;
        }
    }

    private String existingIndexes(Connection connection, List<String> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.index_owner,
                       c.table_name,
                       c.index_name,
                       i.uniqueness,
                       i.status,
                       i.blevel,
                       i.leaf_blocks,
                       i.distinct_keys,
                       i.clustering_factor,
                       i.num_rows,
                       i.last_analyzed,
                       LISTAGG(c.column_name, ', ') WITHIN GROUP (ORDER BY c.column_position) columns
                  FROM all_ind_columns c
                  LEFT JOIN all_indexes i
                    ON i.owner = c.index_owner
                   AND i.index_name = c.index_name
                   AND i.table_name = c.table_name
                 WHERE c.table_name IN (%s)
                 GROUP BY c.index_owner,
                          c.table_name,
                          c.index_name,
                          i.uniqueness,
                          i.status,
                          i.blevel,
                          i.leaf_blocks,
                          i.distinct_keys,
                          i.clustering_factor,
                          i.num_rows,
                          i.last_analyzed
                 ORDER BY c.table_name, c.index_name
                """.formatted(placeholders(tables.size())))) {
            bindTables(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> indexes = new ArrayList<>();
                while (rs.next()) {
                    String uniqueness = rs.getString("uniqueness");
                    String prefix = "UNIQUE".equals(uniqueness) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ";
                    indexes.add(prefix + rs.getString("index_owner") + "." + rs.getString("index_name")
                            + " ON " + rs.getString("table_name")
                            + "(" + rs.getString("columns") + ");"
                            + " -- status=" + stringValue(rs.getString("status"))
                            + ", blevel=" + stringValue(getLong(rs, "blevel"))
                            + ", leaf_blocks=" + stringValue(getLong(rs, "leaf_blocks"))
                            + ", distinct_keys=" + stringValue(getLong(rs, "distinct_keys"))
                            + ", clustering_factor=" + stringValue(getLong(rs, "clustering_factor"))
                            + ", num_rows=" + stringValue(getLong(rs, "num_rows"))
                            + ", last_analyzed=" + stringValue(rs.getObject("last_analyzed")));
                }
                return indexes.isEmpty() ? null : String.join(System.lineSeparator(), indexes);
            }
        } catch (SQLException exception) {
            warnings.add("Index metadata query failed: " + exception.getMessage());
            return null;
        }
    }

    private String tableLoadStatistics(Connection connection, List<String> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_owner,
                       table_name,
                       inserts,
                       updates,
                       deletes,
                       timestamp,
                       truncated
                  FROM all_tab_modifications
                 WHERE table_name IN (%s)
                 ORDER BY table_owner, table_name
                """.formatted(placeholders(tables.size())))) {
            bindTables(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    Long inserts = getLong(rs, "inserts");
                    Long updates = getLong(rs, "updates");
                    Long deletes = getLong(rs, "deletes");
                    lines.add(rs.getString("table_owner") + "." + rs.getString("table_name")
                            + " inserts=" + stringValue(inserts)
                            + ", updates=" + stringValue(updates)
                            + ", deletes=" + stringValue(deletes)
                            + ", changed_rows=" + stringValue(sum(inserts, updates, deletes))
                            + ", last_dml=" + stringValue(rs.getObject("timestamp"))
                            + ", truncated=" + stringValue(rs.getString("truncated")));
                }
                return lines.isEmpty() ? null : section("Table load statistics", String.join(System.lineSeparator(), lines));
            }
        } catch (SQLException exception) {
            warnings.add("all_tab_modifications query failed: " + exception.getMessage());
            return null;
        }
    }

    private String bindSamples(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name,
                       datatype_string,
                       value_string,
                       last_captured
                  FROM v$sql_bind_capture
                 WHERE sql_id = ?
                 ORDER BY position
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> binds = new ArrayList<>();
                while (rs.next()) {
                    binds.add(rs.getString("name")
                            + " " + stringValue(rs.getString("datatype_string"))
                            + " = " + stringValue(rs.getString("value_string"))
                            + " -- last_captured=" + stringValue(rs.getObject("last_captured")));
                }
                return binds.isEmpty() ? null : String.join(System.lineSeparator(), binds);
            }
        } catch (SQLException exception) {
            warnings.add("Bind capture query failed: " + exception.getMessage());
            return null;
        }
    }

    private List<String> extractTableNames(String sqlText) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sqlText);
        while (matcher.find()) {
            String token = matcher.group(1).replace("\"", "").trim();
            if (token.startsWith("(")) {
                continue;
            }
            String table = token.contains(".")
                    ? token.substring(token.lastIndexOf('.') + 1)
                    : token;
            String normalized = table.toLowerCase(Locale.ROOT);
            if (!SKIP_TABLE_TOKENS.contains(normalized)) {
                tables.add(table.toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(tables);
    }

    private String placeholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
    }

    private void bindTables(PreparedStatement statement, List<String> tables) throws SQLException {
        for (int i = 0; i < tables.size(); i++) {
            statement.setString(i + 1, tables.get(i));
        }
    }

    private String formatDataType(ResultSet rs) throws SQLException {
        String dataType = rs.getString("data_type");
        Long precision = getLong(rs, "data_precision");
        Long scale = getLong(rs, "data_scale");
        if ("NUMBER".equals(dataType) && precision != null) {
            return scale != null && scale > 0
                    ? dataType + "(" + precision + "," + scale + ")"
                    : dataType + "(" + precision + ")";
        }
        if (dataType != null && dataType.contains("CHAR")) {
            return dataType + "(" + rs.getInt("data_length") + ")";
        }
        return dataType;
    }

    private String joinSections(String... sections) {
        List<String> present = new ArrayList<>();
        for (String value : sections) {
            if (hasText(value)) {
                present.add(value);
            }
        }
        return present.isEmpty() ? null : String.join(System.lineSeparator() + System.lineSeparator(), present);
    }

    private String section(String title, String body) {
        return hasText(body) ? "-- " + title + System.lineSeparator() + body : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Double score(Double elapsedTimeSec, Long bufferGets, Long diskReads) {
        double value = 0;
        if (elapsedTimeSec != null) {
            value += elapsedTimeSec;
        }
        if (bufferGets != null) {
            value += bufferGets / 100_000d;
        }
        if (diskReads != null) {
            value += diskReads / 1_000d;
        }
        return Math.round(value * 100d) / 100d;
    }

    private Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String stringValue(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private Long sum(Long... values) {
        long total = 0;
        boolean hasValue = false;
        for (Long value : values) {
            if (value != null) {
                total += value;
                hasValue = true;
            }
        }
        return hasValue ? total : null;
    }

    private String getStringIfPresent(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

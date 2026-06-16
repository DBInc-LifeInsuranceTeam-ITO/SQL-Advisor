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

    private static final String SQL_COMMENT_OR_HINT = "(?:/\\*[\\s\\S]*?\\*/\\s*)*";
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_.$#\"]+)"
    );
    private static final Pattern DML_TARGET_PATTERN = Pattern.compile(
            "(?i)\\b(?:insert\\s+" + SQL_COMMENT_OR_HINT + "into|update\\s+" + SQL_COMMENT_OR_HINT
                    + "|merge\\s+" + SQL_COMMENT_OR_HINT + "into|delete\\s+" + SQL_COMMENT_OR_HINT
                    + "from)\\s+([a-zA-Z0-9_.$#\"]+)"
    );
    private static final Set<String> SKIP_TABLE_TOKENS = Set.of(
            "select", "where", "group", "order", "having", "connect", "union", "minus", "intersect"
    );

    private record SqlChildCursor(Integer instId, Integer childNumber, Long planHashValue, String module, String lastActiveTime) {
    }

    private record TableReference(String owner, String tableName) {
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
            String collectedSqlText = sqlId == null ? null : fullSqlText(connection, sqlId, warnings);
            String sqlText = hasText(collectedSqlText)
                    ? collectedSqlText
                    : metric == null || metric.sqlText() == null || metric.sqlText().isBlank()
                    ? requestedSqlText
                    : metric.sqlText();
            if (sqlText == null || sqlText.isBlank()) {
                throw new IllegalArgumentException("SQL_ID did not resolve to SQL text. Provide SQL text fallback.");
            }
            if (metric == null) {
                metric = manualMetric(sqlId == null ? "direct-" + Math.abs(sqlText.hashCode()) : sqlId, sqlText);
            } else if (!sqlText.equals(metric.sqlText())) {
                metric = withSqlText(metric, sqlText);
            }

            SqlChildCursor childCursor = sqlId == null ? null : chooseChildCursor(connection, sqlId, warnings);
            List<TableReference> tableRefs = sqlId == null
                    ? List.of()
                    : tableReferencesFromExecutionPlan(connection, sqlId, childCursor, warnings);
            tableRefs = mergeTableReferences(tableRefs, extractDmlTargetReferences(sqlText));
            if (tableRefs.isEmpty()) {
                tableRefs = extractTableReferences(sqlText);
            }
            tableRefs = enrichTableReferences(connection, tableRefs, warnings);
            String executionPlan = sqlId == null
                    ? null
                    : executionPlanContext(connection, sqlId, childCursor, warnings);
            String planUsedIndexes = sqlId == null
                    ? null
                    : planUsedIndexes(connection, sqlId, childCursor, warnings);
            String schemaDdl = joinSections(
                    schemaDdl(connection, tableRefs, warnings),
                    tableStatistics(connection, tableRefs, warnings),
                    columnStatistics(connection, tableRefs, warnings),
                    constraintMetadata(connection, tableRefs, warnings),
                    tableLoadStatistics(connection, tableRefs, warnings)
            );
            String existingIndexes = existingIndexes(connection, tableRefs, planUsedIndexes, warnings);
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
        try {
            return topSqlFromSqlAreaView(connection, options, "gv$sqlarea");
        } catch (SQLException gvException) {
            return topSqlFromSqlAreaView(connection, options, "v$sqlarea");
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSqlAreaView(
            Connection connection,
            TopSqlOptions options,
            String viewName
    ) throws SQLException {
        List<Object> params = new ArrayList<>();
        String metadataFilters = currentMetadataFilters(options, params, "parsing_schema_name", "module", "action");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT sql_id,
                               MAX(parsing_schema_name) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) parsing_schema_name,
                               SUM(elapsed_time) / 1000000 elapsed_time_sec,
                               SUM(cpu_time) / 1000000 cpu_time_sec,
                               SUM(buffer_gets) buffer_gets,
                               SUM(disk_reads) disk_reads,
                               SUM(executions) executions,
                               SUM(rows_processed) rows_processed,
                               MAX(plan_hash_value) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) plan_hash_value,
                               MAX(module) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) module,
                               MAX(sql_text) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) sql_text
                          FROM %s
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           AND parsing_schema_name IS NOT NULL
                           AND parsing_schema_name NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN', 'OUTLN')
                           AND command_type IN (2, 3, 6, 7, 189)
                           %s
                           %s
                         GROUP BY sql_id
                         ORDER BY %s DESC
                       )
                 WHERE ROWNUM <= %d
                """.formatted(viewName, selfQueryExclusions("sql_text"), metadataFilters, currentOrderExpression(options.sortBy(), "SUM"), options.limit()))) {
            bindParams(statement, params);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Top SQL", "Collected from target database " + viewName + " aggregate, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
            }
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSqlStats(Connection connection, TopSqlOptions options) throws SQLException {
        try {
            return topSqlFromSqlStatsView(connection, options, "gv$sqlstats");
        } catch (SQLException gvException) {
            return topSqlFromSqlStatsView(connection, options, "v$sqlstats");
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSqlStatsView(
            Connection connection,
            TopSqlOptions options,
            String viewName
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT sql_id,
                               SUM(elapsed_time) / 1000000 elapsed_time_sec,
                               SUM(cpu_time) / 1000000 cpu_time_sec,
                               SUM(buffer_gets) buffer_gets,
                               SUM(disk_reads) disk_reads,
                               SUM(executions) executions,
                               SUM(rows_processed) rows_processed,
                               MAX(plan_hash_value) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) plan_hash_value,
                               MAX(sql_text) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) sql_text
                          FROM %s
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           %s
                         GROUP BY sql_id
                         ORDER BY %s DESC
                       )
                 WHERE ROWNUM <= %d
                """.formatted(viewName, selfQueryExclusions("sql_text"), currentOrderExpression(options.sortBy(), "SUM"), options.limit()))) {
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Top SQL", "Collected from target database " + viewName + " aggregate, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
            }
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSql(Connection connection, TopSqlOptions options) throws SQLException {
        try {
            return topSqlFromSqlView(connection, options, "gv$sql");
        } catch (SQLException gvException) {
            return topSqlFromSqlView(connection, options, "v$sql");
        }
    }

    private List<AwrDtos.SqlMetricResponse> topSqlFromSqlView(
            Connection connection,
            TopSqlOptions options,
            String viewName
    ) throws SQLException {
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
                          FROM %s
                         WHERE sql_id IS NOT NULL
                           AND sql_text IS NOT NULL
                           AND parsing_schema_name IS NOT NULL
                           AND parsing_schema_name NOT IN ('SYS', 'SYSTEM', 'DBSNMP', 'SYSMAN', 'OUTLN')
                           AND command_type IN (2, 3, 6, 7, 189)
                           %s
                           %s
                         GROUP BY sql_id, parsing_schema_name
                         ORDER BY %s DESC
                       )
                 WHERE ROWNUM <= %d
                """.formatted(viewName, selfQueryExclusions("sql_text"), metadataFilters, currentOrderExpression(options.sortBy(), "SUM"), options.limit()))) {
            bindParams(statement, params);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return mapTopSql(rs, "Direct DB Top SQL", "Collected from target database " + viewName + " child cursor aggregate, sorted by " + options.sortBy().toLowerCase(Locale.ROOT) + ".");
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
                       DBMS_LOB.SUBSTR(t.sql_text, 4000, 1) sql_text
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
                   %s
                   %s
                 GROUP BY s.sql_id,
                          DBMS_LOB.SUBSTR(t.sql_text, 4000, 1)
                 ORDER BY %s DESC
                 FETCH FIRST %d ROWS ONLY
                """.formatted(selfQueryExclusions("DBMS_LOB.SUBSTR(t.sql_text, 4000, 1)"), historyFilters, historyOrderExpression(options.sortBy()), options.limit()))) {
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

    private String selfQueryExclusions(String sqlExpression) {
        return """
                           AND LOWER(%s) NOT LIKE '%%lower(sql_text) not like%%'
                           AND LOWER(%s) NOT LIKE '%%dbms_xplan.display_cursor%%'
                """.formatted(sqlExpression, sqlExpression).stripTrailing();
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
        AwrDtos.SqlMetricResponse metric = findSqlAreaMetricFromView(connection, sqlId, "gv$sqlarea", warnings);
        if (metric != null) {
            return metric;
        }
        return findSqlAreaMetricFromView(connection, sqlId, "v$sqlarea", warnings);
    }

    private AwrDtos.SqlMetricResponse findSqlAreaMetricFromView(
            Connection connection,
            String sqlId,
            String viewName,
            List<String> warnings
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sql_id,
                       MAX(parsing_schema_name) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) parsing_schema_name,
                       SUM(elapsed_time) / 1000000 elapsed_time_sec,
                       SUM(cpu_time) / 1000000 cpu_time_sec,
                       SUM(buffer_gets) buffer_gets,
                       SUM(disk_reads) disk_reads,
                       SUM(executions) executions,
                       SUM(rows_processed) rows_processed,
                       MAX(plan_hash_value) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) plan_hash_value,
                       MAX(module) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) module,
                       MAX(sql_text) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) sql_text
                  FROM %s
                 WHERE sql_id = ?
                 GROUP BY sql_id
                """.formatted(viewName))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add(viewName + " did not contain SQL_ID " + sqlId + ".");
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
                        "Collected from target database " + viewName + " aggregate."
                );
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private AwrDtos.SqlMetricResponse findSqlStatsMetric(Connection connection, String sqlId, List<String> warnings) {
        AwrDtos.SqlMetricResponse metric = findSqlStatsMetricFromView(connection, sqlId, "gv$sqlstats", warnings);
        if (metric != null) {
            return metric;
        }
        return findSqlStatsMetricFromView(connection, sqlId, "v$sqlstats", warnings);
    }

    private AwrDtos.SqlMetricResponse findSqlStatsMetricFromView(
            Connection connection,
            String sqlId,
            String viewName,
            List<String> warnings
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT sql_id,
                       SUM(elapsed_time) / 1000000 elapsed_time_sec,
                       SUM(cpu_time) / 1000000 cpu_time_sec,
                       SUM(buffer_gets) buffer_gets,
                       SUM(disk_reads) disk_reads,
                       SUM(executions) executions,
                       SUM(rows_processed) rows_processed,
                       MAX(plan_hash_value) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) plan_hash_value,
                       MAX(sql_text) KEEP (DENSE_RANK LAST ORDER BY elapsed_time) sql_text
                  FROM %s
                 WHERE sql_id = ?
                 GROUP BY sql_id
                """.formatted(viewName))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add(viewName + " did not contain SQL_ID " + sqlId + ".");
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
                        "Collected from target database " + viewName + " aggregate."
                );
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private AwrDtos.SqlMetricResponse findSqlChildMetric(Connection connection, String sqlId, List<String> warnings) {
        AwrDtos.SqlMetricResponse metric = findSqlChildMetricFromView(connection, sqlId, "gv$sql", warnings);
        if (metric != null) {
            return metric;
        }
        return findSqlChildMetricFromView(connection, sqlId, "v$sql", warnings);
    }

    private AwrDtos.SqlMetricResponse findSqlChildMetricFromView(
            Connection connection,
            String sqlId,
            String viewName,
            List<String> warnings
    ) {
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
                          FROM %s
                         WHERE sql_id = ?
                         ORDER BY elapsed_time DESC, last_active_time DESC NULLS LAST
                       )
                 WHERE ROWNUM <= 1
                """.formatted(viewName))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add(viewName + " did not contain SQL_ID " + sqlId + ".");
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
                        "Collected from target database " + viewName + " child cursor."
                );
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " query failed: " + exception.getMessage());
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

    private AwrDtos.SqlMetricResponse withSqlText(AwrDtos.SqlMetricResponse metric, String sqlText) {
        return new AwrDtos.SqlMetricResponse(
                metric.sqlId(),
                metric.sectionName(),
                metric.rankNo(),
                metric.elapsedTimeSec(),
                metric.cpuTimeSec(),
                metric.bufferGets(),
                metric.diskReads(),
                metric.executions(),
                metric.rowsProcessed(),
                metric.planHashValue(),
                metric.module(),
                sqlText,
                metric.score(),
                metric.interpretationHint()
        );
    }

    private String fullSqlText(Connection connection, String sqlId, List<String> warnings) {
        List<String> localWarnings = new ArrayList<>();
        String currentSql = fullSqlTextFromCurrentView(connection, sqlId, "gv$sql", localWarnings);
        if (hasText(currentSql)) {
            return currentSql;
        }
        currentSql = fullSqlTextFromCurrentView(connection, sqlId, "v$sql", localWarnings);
        if (hasText(currentSql)) {
            return currentSql;
        }
        String historySql = fullSqlTextFromHistory(connection, sqlId, localWarnings);
        if (hasText(historySql)) {
            return historySql;
        }
        warnings.addAll(localWarnings);
        return null;
    }

    private String fullSqlTextFromCurrentView(Connection connection, String sqlId, String viewName, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT DBMS_LOB.SUBSTR(sql_fulltext, 4000, 1) sql_text
                          FROM %s
                         WHERE sql_id = ?
                         ORDER BY last_active_time DESC NULLS LAST, child_number DESC
                       )
                 WHERE ROWNUM <= 1
                """.formatted(viewName))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("sql_text") : null;
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " SQL_FULLTEXT query failed: " + exception.getMessage());
            return null;
        }
    }

    private String fullSqlTextFromHistory(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DBMS_LOB.SUBSTR(sql_text, 4000, 1) sql_text
                  FROM dba_hist_sqltext
                 WHERE sql_id = ?
                   AND sql_text IS NOT NULL
                   AND ROWNUM <= 1
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("sql_text") : null;
            }
        } catch (SQLException exception) {
            warnings.add("dba_hist_sqltext query failed: " + exception.getMessage());
            return null;
        }
    }

    private SqlChildCursor chooseChildCursor(Connection connection, String sqlId, List<String> warnings) {
        List<String> gvWarnings = new ArrayList<>();
        SqlChildCursor cursor = chooseChildCursorFromView(connection, sqlId, "gv$sql", "inst_id", gvWarnings);
        if (cursor != null) {
            return cursor;
        }
        cursor = chooseChildCursorFromView(connection, sqlId, "v$sql", "CAST(NULL AS NUMBER)", warnings);
        if (cursor == null) {
            warnings.addAll(gvWarnings);
        }
        return cursor;
    }

    private SqlChildCursor chooseChildCursorFromView(
            Connection connection,
            String sqlId,
            String viewName,
            String instExpression,
            List<String> warnings
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT %s inst_id,
                               child_number,
                               plan_hash_value,
                               module,
                               TO_CHAR(last_active_time, 'YYYY-MM-DD HH24:MI:SS') last_active_time
                          FROM %s
                         WHERE sql_id = ?
                         ORDER BY elapsed_time DESC, last_active_time DESC NULLS LAST, child_number DESC
                       )
                 WHERE ROWNUM <= 1
                """.formatted(instExpression, viewName))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    warnings.add("No child cursor found in " + viewName + " for SQL_ID " + sqlId + ".");
                    return null;
                }
                return new SqlChildCursor(
                        getLong(rs, "inst_id") == null ? null : getLong(rs, "inst_id").intValue(),
                        rs.getInt("child_number"),
                        getLong(rs, "plan_hash_value"),
                        rs.getString("module"),
                        rs.getString("last_active_time")
                );
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " child cursor selection failed: " + exception.getMessage());
            return null;
        }
    }

    private String executionPlanContext(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        return joinSections(
                childCursor == null
                        ? null
                        : section("Chosen child cursor", "inst_id=" + stringValue(childCursor.instId())
                        + ", child_number=" + childCursor.childNumber()
                        + ", plan_hash_value=" + childCursor.planHashValue()
                        + ", module=" + stringValue(childCursor.module())
                        + ", last_active_time=" + stringValue(childCursor.lastActiveTime())),
                section("DBMS_XPLAN.DISPLAY_CURSOR", displayCursor(connection, sqlId, childCursor, warnings)),
                sqlPlan(connection, sqlId, childCursor, warnings),
                planStatistics(connection, sqlId, childCursor, warnings),
                activeSessionHistory(connection, sqlId, warnings),
                section("DBA_HIST_ACTIVE_SESS_HISTORY", historicalActiveSessionHistory(connection, sqlId, warnings)),
                section("DBA_HIST_SQLSTAT", historicalSqlStat(connection, sqlId, warnings)),
                section("DBA_HIST_SQL_PLAN", historicalSqlPlan(connection, sqlId, childCursor, warnings))
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
        List<String> gvWarnings = new ArrayList<>();
        String gvPlan = sqlPlanFromView(connection, sqlId, childCursor, "gv$sql_plan", true, gvWarnings);
        if (hasText(gvPlan)) {
            return section("GV$SQL_PLAN", gvPlan);
        }
        String vPlan = sqlPlanFromView(connection, sqlId, childCursor, "v$sql_plan", false, warnings);
        if (!hasText(vPlan)) {
            warnings.addAll(gvWarnings);
        }
        return hasText(vPlan) ? section("V$SQL_PLAN", vPlan) : null;
    }

    private String sqlPlanFromView(
            Connection connection,
            String sqlId,
            SqlChildCursor childCursor,
            String viewName,
            boolean includeInstance,
            List<String> warnings
    ) {
        String childClause = childCursor == null || childCursor.childNumber() == null ? "" : " AND child_number = ?";
        String instanceClause = includeInstance && childCursor != null && childCursor.instId() != null ? " AND inst_id = ?" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,
                       %s inst_id,
                       parent_id,
                       operation,
                       options,
                       object_owner,
                       object_name,
                       access_predicates,
                       filter_predicates
                  FROM %s
                 WHERE sql_id = ?
                %s
                %s
                 ORDER BY %s child_number, id
                """.formatted(includeInstance ? "inst_id" : "CAST(NULL AS NUMBER)", viewName, childClause, instanceClause, includeInstance ? "inst_id, " : ""))) {
            int index = 1;
            statement.setString(index++, sqlId);
            if (!childClause.isBlank()) {
                statement.setInt(index++, childCursor.childNumber());
            }
            if (!instanceClause.isBlank()) {
                statement.setInt(index, childCursor.instId());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    List<String> parts = new ArrayList<>();
                    if (getLong(rs, "inst_id") != null) {
                        parts.add("inst=" + getLong(rs, "inst_id"));
                    }
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
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private String planStatistics(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        List<String> gvWarnings = new ArrayList<>();
        String gvStats = planStatisticsFromView(connection, sqlId, childCursor, "gv$sql_plan_statistics_all", true, gvWarnings);
        if (hasText(gvStats)) {
            return section("GV$SQL_PLAN_STATISTICS_ALL", gvStats);
        }
        String vStats = planStatisticsFromView(connection, sqlId, childCursor, "v$sql_plan_statistics_all", false, warnings);
        if (!hasText(vStats)) {
            warnings.addAll(gvWarnings);
        }
        return hasText(vStats) ? section("V$SQL_PLAN_STATISTICS_ALL", vStats) : null;
    }

    private String planStatisticsFromView(
            Connection connection,
            String sqlId,
            SqlChildCursor childCursor,
            String viewName,
            boolean includeInstance,
            List<String> warnings
    ) {
        String childClause = childCursor == null || childCursor.childNumber() == null ? "" : " AND child_number = ?";
        String instanceClause = includeInstance && childCursor != null && childCursor.instId() != null ? " AND inst_id = ?" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id,
                       %s inst_id,
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
                  FROM %s
                 WHERE sql_id = ?
                %s
                %s
                 ORDER BY %s child_number, id
                """.formatted(includeInstance ? "inst_id" : "CAST(NULL AS NUMBER)", viewName, childClause, instanceClause, includeInstance ? "inst_id, " : ""))) {
            int index = 1;
            statement.setString(index++, sqlId);
            if (!childClause.isBlank()) {
                statement.setInt(index++, childCursor.childNumber());
            }
            if (!instanceClause.isBlank()) {
                statement.setInt(index, childCursor.instId());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    String instance = getLong(rs, "inst_id") == null ? "" : "inst=" + getLong(rs, "inst_id") + " ";
                    lines.add(instance + "#" + rs.getInt("id")
                            + " " + stringValue(rs.getString("operation")) + " " + stringValue(rs.getString("options"))
                            + " object=" + stringValue(rs.getString("object_name"))
                            + System.lineSeparator()
                            + "  total: starts=" + stringValue(getLong(rs, "starts"))
                            + ", rows=" + stringValue(getLong(rs, "output_rows"))
                            + ", cr_gets=" + stringValue(getLong(rs, "cr_buffer_gets"))
                            + ", disk_reads=" + stringValue(getLong(rs, "disk_reads"))
                            + System.lineSeparator()
                            + "  last:  starts=" + stringValue(getLong(rs, "last_starts"))
                            + ", rows=" + stringValue(getLong(rs, "last_output_rows"))
                            + ", cr_gets=" + stringValue(getLong(rs, "last_cr_buffer_gets"))
                            + ", disk_reads=" + stringValue(getLong(rs, "last_disk_reads")));
                }
                return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private List<TableReference> tableReferencesFromExecutionPlan(
            Connection connection,
            String sqlId,
            SqlChildCursor childCursor,
            List<String> warnings
    ) {
        Set<String> seen = new LinkedHashSet<>();
        List<TableReference> tables = new ArrayList<>();
        List<String> gvWarnings = new ArrayList<>();
        mergeTableReferencesInto(tables, tableReferencesFromPlanView(connection, sqlId, childCursor, "gv$sql_plan", true, gvWarnings), seen);
        mergeTableReferencesInto(tables, tableReferencesFromPlanView(connection, sqlId, childCursor, "v$sql_plan", false, warnings), seen);
        mergeTableReferencesInto(tables, tableReferencesFromHistoricalPlan(connection, sqlId, childCursor, warnings), seen);
        if (tables.isEmpty() && !gvWarnings.isEmpty()) {
            warnings.addAll(gvWarnings);
        }
        return tables;
    }

    private List<TableReference> tableReferencesFromPlanView(
            Connection connection,
            String sqlId,
            SqlChildCursor childCursor,
            String viewName,
            boolean includeInstance,
            List<String> warnings
    ) {
        String childClause = childCursor == null || childCursor.childNumber() == null ? "" : " AND p.child_number = ?";
        String instanceClause = includeInstance && childCursor != null && childCursor.instId() != null ? " AND p.inst_id = ?" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT
                       CASE
                           WHEN UPPER(p.operation) LIKE 'INDEX%%' THEN i.table_owner
                           ELSE p.object_owner
                       END table_owner,
                       CASE
                           WHEN UPPER(p.operation) LIKE 'INDEX%%' THEN i.table_name
                           ELSE p.object_name
                       END table_name
                  FROM %s p
                  LEFT JOIN dba_indexes i
                    ON i.owner = p.object_owner
                   AND i.index_name = p.object_name
                 WHERE p.sql_id = ?
                %s
                %s
                   AND p.object_name IS NOT NULL
                   AND (UPPER(p.operation) LIKE 'TABLE ACCESS%%'
                        OR UPPER(p.operation) LIKE 'INDEX%%')
                """.formatted(viewName, childClause, instanceClause))) {
            int index = 1;
            statement.setString(index++, sqlId);
            if (!childClause.isBlank()) {
                statement.setInt(index++, childCursor.childNumber());
            }
            if (!instanceClause.isBlank()) {
                statement.setInt(index, childCursor.instId());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return tableReferencesFromResultSet(rs);
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " table object query failed: " + exception.getMessage());
            return List.of();
        }
    }

    private List<TableReference> tableReferencesFromHistoricalPlan(
            Connection connection,
            String sqlId,
            SqlChildCursor childCursor,
            List<String> warnings
    ) {
        String planHashClause = childCursor == null || childCursor.planHashValue() == null ? "" : " AND p.plan_hash_value = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT
                       CASE
                           WHEN UPPER(p.operation) LIKE 'INDEX%%' THEN i.table_owner
                           ELSE p.object_owner
                       END table_owner,
                       CASE
                           WHEN UPPER(p.operation) LIKE 'INDEX%%' THEN i.table_name
                           ELSE p.object_name
                       END table_name
                  FROM dba_hist_sql_plan p
                  LEFT JOIN dba_indexes i
                    ON i.owner = p.object_owner
                   AND i.index_name = p.object_name
                 WHERE p.sql_id = ?
                %s
                   AND p.object_name IS NOT NULL
                   AND (UPPER(p.operation) LIKE 'TABLE ACCESS%%'
                        OR UPPER(p.operation) LIKE 'INDEX%%')
                """.formatted(planHashClause))) {
            statement.setString(1, sqlId);
            if (!planHashClause.isBlank()) {
                statement.setLong(2, childCursor.planHashValue());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return tableReferencesFromResultSet(rs);
            }
        } catch (SQLException exception) {
            warnings.add("dba_hist_sql_plan table object query failed: " + exception.getMessage());
            return List.of();
        }
    }

    private List<TableReference> tableReferencesFromResultSet(ResultSet rs) throws SQLException {
        Set<String> seen = new LinkedHashSet<>();
        List<TableReference> tables = new ArrayList<>();
        while (rs.next()) {
            addTableReference(rs.getString("table_owner"), rs.getString("table_name"), seen, tables);
        }
        return tables;
    }

    private String planUsedIndexes(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        List<String> gvWarnings = new ArrayList<>();
        String gvIndexes = planUsedIndexesFromView(connection, sqlId, childCursor, "gv$sql_plan", true, gvWarnings);
        if (hasText(gvIndexes)) {
            return gvIndexes;
        }
        String vIndexes = planUsedIndexesFromView(connection, sqlId, childCursor, "v$sql_plan", false, warnings);
        if (hasText(vIndexes)) {
            return vIndexes;
        }
        if (!gvWarnings.isEmpty()) {
            warnings.addAll(gvWarnings);
        }
        return planUsedIndexesFromHistory(connection, sqlId, childCursor, warnings);
    }

    private String planUsedIndexesFromView(
            Connection connection,
            String sqlId,
            SqlChildCursor childCursor,
            String viewName,
            boolean includeInstance,
            List<String> warnings
    ) {
        String childClause = childCursor == null || childCursor.childNumber() == null ? "" : " AND p.child_number = ?";
        String instanceClause = includeInstance && childCursor != null && childCursor.instId() != null ? " AND p.inst_id = ?" : "";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT
                       p.object_owner index_owner,
                       p.object_name index_name,
                       i.table_owner,
                       i.table_name,
                       i.uniqueness,
                       i.status,
                       i.visibility,
                       p.operation,
                       p.options
                  FROM %s p
                  LEFT JOIN dba_indexes i
                    ON i.owner = p.object_owner
                   AND i.index_name = p.object_name
                 WHERE p.sql_id = ?
                %s
                %s
                   AND p.object_name IS NOT NULL
                   AND UPPER(p.operation) LIKE 'INDEX%%'
                 ORDER BY p.object_owner, p.object_name
                """.formatted(viewName, childClause, instanceClause))) {
            int index = 1;
            statement.setString(index++, sqlId);
            if (!childClause.isBlank()) {
                statement.setInt(index++, childCursor.childNumber());
            }
            if (!instanceClause.isBlank()) {
                statement.setInt(index, childCursor.instId());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return planUsedIndexLines(rs);
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " used index query failed: " + exception.getMessage());
            return null;
        }
    }

    private String planUsedIndexesFromHistory(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        String planHashClause = childCursor == null || childCursor.planHashValue() == null ? "" : " AND p.plan_hash_value = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT
                       p.object_owner index_owner,
                       p.object_name index_name,
                       i.table_owner,
                       i.table_name,
                       i.uniqueness,
                       i.status,
                       i.visibility,
                       p.operation,
                       p.options
                  FROM dba_hist_sql_plan p
                  LEFT JOIN dba_indexes i
                    ON i.owner = p.object_owner
                   AND i.index_name = p.object_name
                 WHERE p.sql_id = ?
                %s
                   AND p.object_name IS NOT NULL
                   AND UPPER(p.operation) LIKE 'INDEX%%'
                 ORDER BY p.object_owner, p.object_name
                """.formatted(planHashClause))) {
            statement.setString(1, sqlId);
            if (!planHashClause.isBlank()) {
                statement.setLong(2, childCursor.planHashValue());
            }
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return planUsedIndexLines(rs);
            }
        } catch (SQLException exception) {
            warnings.add("dba_hist_sql_plan used index query failed: " + exception.getMessage());
            return null;
        }
    }

    private String planUsedIndexLines(ResultSet rs) throws SQLException {
        List<String> indexes = new ArrayList<>();
        while (rs.next()) {
            indexes.add(stringValue(rs.getString("table_owner")) + "." + stringValue(rs.getString("table_name"))
                    + " | " + stringValue(rs.getString("index_owner")) + "." + rs.getString("index_name")
                    + " | access=" + stringValue(rs.getString("operation")) + " " + stringValue(rs.getString("options"))
                    + " | uniqueness=" + stringValue(rs.getString("uniqueness"))
                    + " | status=" + stringValue(rs.getString("status"))
                    + " | visibility=" + stringValue(rs.getString("visibility")));
        }
        return indexes.isEmpty() ? null : String.join(System.lineSeparator(), indexes);
    }

    private String activeSessionHistory(Connection connection, String sqlId, List<String> warnings) {
        List<String> gvWarnings = new ArrayList<>();
        String gvAsh = activeSessionHistoryFromView(connection, sqlId, "gv$active_session_history", "sample_time", true, gvWarnings);
        if (hasText(gvAsh)) {
            return section("GV$ACTIVE_SESSION_HISTORY", gvAsh);
        }
        String vAsh = activeSessionHistoryFromView(connection, sqlId, "v$active_session_history", "sample_time", false, warnings);
        if (!hasText(vAsh)) {
            warnings.addAll(gvWarnings);
        }
        return hasText(vAsh) ? section("V$ACTIVE_SESSION_HISTORY", vAsh) : null;
    }

    private String historicalActiveSessionHistory(Connection connection, String sqlId, List<String> warnings) {
        return activeSessionHistoryFromView(connection, sqlId, "dba_hist_active_sess_history", "sample_time", false, warnings);
    }

    private String activeSessionHistoryFromView(
            Connection connection,
            String sqlId,
            String viewName,
            String sampleTimeColumn,
            boolean includeInstance,
            List<String> warnings
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT %s inst_id,
                               session_state,
                               wait_class,
                               event,
                               sql_plan_hash_value,
                               sql_plan_line_id,
                               sql_plan_operation,
                               sql_plan_options,
                               COUNT(*) samples,
                               MIN(%s) first_sample,
                               MAX(%s) last_sample
                          FROM %s
                         WHERE sql_id = ?
                         GROUP BY session_state,
                                  %s
                                  wait_class,
                                  event,
                                  sql_plan_hash_value,
                                  sql_plan_line_id,
                                  sql_plan_operation,
                                  sql_plan_options
                         ORDER BY COUNT(*) DESC
                       )
                 WHERE ROWNUM <= 10
                """.formatted(
                        includeInstance ? "inst_id" : "CAST(NULL AS NUMBER)",
                        sampleTimeColumn,
                        sampleTimeColumn,
                        viewName,
                        includeInstance ? "inst_id," : ""
                ))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add("inst=" + stringValue(getLong(rs, "inst_id"))
                            + ", samples=" + stringValue(getLong(rs, "samples"))
                            + ", state=" + stringValue(rs.getString("session_state"))
                            + ", wait_class=" + stringValue(rs.getString("wait_class"))
                            + ", event=" + stringValue(rs.getString("event"))
                            + ", plan_hash=" + stringValue(getLong(rs, "sql_plan_hash_value"))
                            + ", line_id=" + stringValue(getLong(rs, "sql_plan_line_id"))
                            + ", operation=" + stringValue(rs.getString("sql_plan_operation"))
                            + " " + stringValue(rs.getString("sql_plan_options"))
                            + ", first_sample=" + stringValue(rs.getObject("first_sample"))
                            + ", last_sample=" + stringValue(rs.getObject("last_sample")));
                }
                return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private String historicalSqlStat(Connection connection, String sqlId, List<String> warnings) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT TO_CHAR(sn.begin_interval_time, 'YYYY-MM-DD HH24:MI') begin_time,
                               TO_CHAR(sn.end_interval_time, 'YYYY-MM-DD HH24:MI') end_time,
                               s.instance_number,
                               s.plan_hash_value,
                               s.elapsed_time_delta / 1000000 elapsed_sec,
                               s.cpu_time_delta / 1000000 cpu_sec,
                               s.buffer_gets_delta,
                               s.disk_reads_delta,
                               s.executions_delta,
                               s.rows_processed_delta,
                               s.parsing_schema_name,
                               s.module
                          FROM dba_hist_sqlstat s
                          JOIN dba_hist_snapshot sn
                            ON sn.dbid = s.dbid
                           AND sn.instance_number = s.instance_number
                           AND sn.snap_id = s.snap_id
                         WHERE s.sql_id = ?
                         ORDER BY sn.end_interval_time DESC
                       )
                 WHERE ROWNUM <= 12
                """)) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(15);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(rs.getString("begin_time") + " - " + rs.getString("end_time")
                            + " inst=" + stringValue(getLong(rs, "instance_number"))
                            + ", plan_hash=" + stringValue(getLong(rs, "plan_hash_value"))
                            + ", elapsed_sec=" + stringValue(getDouble(rs, "elapsed_sec"))
                            + ", cpu_sec=" + stringValue(getDouble(rs, "cpu_sec"))
                            + ", buffer_gets_delta=" + stringValue(getLong(rs, "buffer_gets_delta"))
                            + ", disk_reads_delta=" + stringValue(getLong(rs, "disk_reads_delta"))
                            + ", executions_delta=" + stringValue(getLong(rs, "executions_delta"))
                            + ", rows_processed_delta=" + stringValue(getLong(rs, "rows_processed_delta"))
                            + ", schema=" + stringValue(rs.getString("parsing_schema_name"))
                            + ", module=" + stringValue(rs.getString("module")));
                }
                return lines.isEmpty() ? null : String.join(System.lineSeparator(), lines);
            }
        } catch (SQLException exception) {
            warnings.add("dba_hist_sqlstat query failed: " + exception.getMessage());
            return null;
        }
    }

    private String historicalSqlPlan(Connection connection, String sqlId, SqlChildCursor childCursor, List<String> warnings) {
        String planHashClause = childCursor == null || childCursor.planHashValue() == null ? "" : " AND plan_hash_value = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                  FROM (
                        SELECT plan_hash_value,
                               id,
                               parent_id,
                               operation,
                               options,
                               object_owner,
                               object_name,
                               cardinality,
                               bytes,
                               cost,
                               access_predicates,
                               filter_predicates
                          FROM dba_hist_sql_plan
                         WHERE sql_id = ?
                        %s
                         ORDER BY plan_hash_value, id
                       )
                 WHERE ROWNUM <= 80
                """.formatted(planHashClause))) {
            statement.setString(1, sqlId);
            if (!planHashClause.isBlank()) {
                statement.setLong(2, childCursor.planHashValue());
            }
            statement.setQueryTimeout(15);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    List<String> parts = new ArrayList<>();
                    parts.add("plan_hash=" + stringValue(getLong(rs, "plan_hash_value")));
                    parts.add("#" + rs.getInt("id"));
                    parts.add("parent=" + rs.getInt("parent_id"));
                    parts.add(stringValue(rs.getString("operation")) + " " + stringValue(rs.getString("options")));
                    if (hasText(rs.getString("object_name"))) {
                        parts.add("object=" + stringValue(rs.getString("object_owner")) + "." + rs.getString("object_name"));
                    }
                    parts.add("cardinality=" + stringValue(getLong(rs, "cardinality")));
                    parts.add("bytes=" + stringValue(getLong(rs, "bytes")));
                    parts.add("cost=" + stringValue(getLong(rs, "cost")));
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
            warnings.add("dba_hist_sql_plan query failed: " + exception.getMessage());
            return null;
        }
    }

    private String schemaDdl(Connection connection, List<TableReference> tables, List<String> warnings) {
        return schemaDdlFromView(connection, tables, "dba_tab_columns", warnings);
    }

    private String schemaDdlFromView(Connection connection, List<TableReference> tables, String viewName, List<String> warnings) {
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
                  FROM %s
                 WHERE %s
                 ORDER BY owner, table_name, column_id
                """.formatted(viewName, tableReferencePredicate("owner", "table_name", tables)))) {
            bindTableReferences(statement, tables);
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
            warnings.add(viewName + " column metadata query failed: " + exception.getMessage());
            return null;
        }
    }

    private String tableStatistics(Connection connection, List<TableReference> tables, List<String> warnings) {
        return tableStatisticsFromView(connection, tables, "dba_tables", warnings);
    }

    private String tableStatisticsFromView(Connection connection, List<TableReference> tables, String viewName, List<String> warnings) {
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
                  FROM %s
                 WHERE %s
                 ORDER BY owner, table_name
                """.formatted(viewName, tableReferencePredicate("owner", "table_name", tables)))) {
            bindTableReferences(statement, tables);
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
            warnings.add(viewName + " statistics query failed: " + exception.getMessage());
            return null;
        }
    }

    private String columnStatistics(Connection connection, List<TableReference> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner,
                       table_name,
                       column_name,
                       num_distinct,
                       density,
                       num_nulls,
                       num_buckets,
                       sample_size,
                       last_analyzed,
                       histogram
                  FROM dba_tab_col_statistics
                 WHERE %s
                 ORDER BY owner, table_name, column_name
                """.formatted(tableReferencePredicate("owner", "table_name", tables)))) {
            bindTableReferences(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(rs.getString("owner") + "." + rs.getString("table_name") + "." + rs.getString("column_name")
                            + " num_distinct=" + stringValue(getLong(rs, "num_distinct"))
                            + ", density=" + stringValue(getDouble(rs, "density"))
                            + ", num_nulls=" + stringValue(getLong(rs, "num_nulls"))
                            + ", num_buckets=" + stringValue(getLong(rs, "num_buckets"))
                            + ", sample_size=" + stringValue(getLong(rs, "sample_size"))
                            + ", last_analyzed=" + stringValue(rs.getObject("last_analyzed"))
                            + ", histogram=" + stringValue(rs.getString("histogram")));
                }
                return lines.isEmpty() ? null : section("Column statistics", String.join(System.lineSeparator(), lines));
            }
        } catch (SQLException exception) {
            warnings.add("dba_tab_col_statistics query failed: " + exception.getMessage());
            return null;
        }
    }

    private String constraintMetadata(Connection connection, List<TableReference> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.owner,
                       c.table_name,
                       c.constraint_name,
                       c.constraint_type,
                       c.r_owner,
                       c.r_constraint_name,
                       c.status,
                       c.deferrable,
                       c.deferred,
                       c.validated,
                       LISTAGG(col.column_name, ', ') WITHIN GROUP (ORDER BY col.position) columns
                  FROM dba_constraints c
                  LEFT JOIN dba_cons_columns col
                    ON col.owner = c.owner
                   AND col.constraint_name = c.constraint_name
                   AND col.table_name = c.table_name
                 WHERE %s
                 GROUP BY c.owner,
                          c.table_name,
                          c.constraint_name,
                          c.constraint_type,
                          c.r_owner,
                          c.r_constraint_name,
                          c.status,
                          c.deferrable,
                          c.deferred,
                          c.validated
                 ORDER BY c.owner, c.table_name, c.constraint_name
                """.formatted(tableReferencePredicate("c.owner", "c.table_name", tables)))) {
            bindTableReferences(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(rs.getString("owner") + "." + rs.getString("table_name")
                            + " | constraint=" + rs.getString("constraint_name")
                            + " | type=" + stringValue(rs.getString("constraint_type"))
                            + " | columns=(" + stringValue(rs.getString("columns")) + ")"
                            + " | references=" + stringValue(rs.getString("r_owner")) + "." + stringValue(rs.getString("r_constraint_name"))
                            + " | status=" + stringValue(rs.getString("status"))
                            + " | deferrable=" + stringValue(rs.getString("deferrable"))
                            + " | deferred=" + stringValue(rs.getString("deferred"))
                            + " | validated=" + stringValue(rs.getString("validated")));
                }
                return lines.isEmpty() ? null : section("Constraints", String.join(System.lineSeparator(), lines));
            }
        } catch (SQLException exception) {
            warnings.add("dba_constraints query failed: " + exception.getMessage());
            return null;
        }
    }

    private String existingIndexes(Connection connection, List<TableReference> tables, String planUsedIndexes, List<String> warnings) {
        String diagnostics = indexCollectionDiagnostics(tables, planUsedIndexes, null);
        if (tables.isEmpty()) {
            return joinSections(
                    section("Plan Used Indexes", planUsedIndexes),
                    section("Index Collection Diagnostics", diagnostics)
            );
        }
        String metadataSource = "DBA_IND_COLUMNS/DBA_INDEXES";
        String relatedIndexes = indexMetadataFromViews(connection, tables, "dba_ind_columns", "dba_indexes", warnings);
        if (!hasText(relatedIndexes)) {
            metadataSource = "ALL_IND_COLUMNS/ALL_INDEXES";
            relatedIndexes = indexMetadataFromViews(connection, tables, "all_ind_columns", "all_indexes", warnings);
        }
        return joinSections(
                section("Related Table Indexes", relatedIndexes),
                section("Plan Used Indexes", planUsedIndexes),
                section("Index Collection Diagnostics", indexCollectionDiagnostics(tables, planUsedIndexes, metadataSource))
        );
    }

    private String indexMetadataFromViews(
            Connection connection,
            List<TableReference> tables,
            String columnsViewName,
            String indexesViewName,
            List<String> warnings
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.table_owner,
                       c.index_owner,
                       c.table_name,
                       c.index_name,
                       i.uniqueness,
                       i.status,
                       i.logging,
                       i.visibility,
                       i.blevel,
                       i.leaf_blocks,
                       i.distinct_keys,
                       i.clustering_factor,
                       i.num_rows,
                       i.last_analyzed,
                       LISTAGG(c.column_name, ', ') WITHIN GROUP (ORDER BY c.column_position) columns
                  FROM %s c
                  LEFT JOIN %s i
                    ON i.owner = c.index_owner
                   AND i.index_name = c.index_name
                   AND i.table_owner = c.table_owner
                   AND i.table_name = c.table_name
                 WHERE %s
                 GROUP BY c.table_owner,
                          c.index_owner,
                          c.table_name,
                          c.index_name,
                          i.uniqueness,
                          i.status,
                          i.logging,
                          i.visibility,
                          i.blevel,
                          i.leaf_blocks,
                          i.distinct_keys,
                          i.clustering_factor,
                          i.num_rows,
                          i.last_analyzed
                 ORDER BY c.table_owner, c.table_name, c.index_name
                """.formatted(columnsViewName, indexesViewName, tableReferencePredicate("c.table_owner", "c.table_name", tables)))) {
            bindTableReferences(statement, tables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                return indexLines(rs);
            }
        } catch (SQLException exception) {
            warnings.add(indexesViewName + " metadata query failed: " + exception.getMessage());
            return null;
        }
    }

    private String indexLines(ResultSet rs) throws SQLException {
        List<String> indexes = new ArrayList<>();
        while (rs.next()) {
            indexes.add(rs.getString("table_owner") + "." + rs.getString("table_name")
                    + " | " + rs.getString("index_owner") + "." + rs.getString("index_name")
                    + " | columns=(" + rs.getString("columns") + ")"
                    + " | uniqueness=" + stringValue(rs.getString("uniqueness"))
                    + " | status=" + stringValue(rs.getString("status"))
                    + " | logging=" + stringValue(rs.getString("logging"))
                    + " | visibility=" + stringValue(rs.getString("visibility"))
                    + " | blevel=" + stringValue(getLong(rs, "blevel"))
                    + " | leaf_blocks=" + stringValue(getLong(rs, "leaf_blocks"))
                    + " | distinct_keys=" + stringValue(getLong(rs, "distinct_keys"))
                    + " | clustering_factor=" + stringValue(getLong(rs, "clustering_factor"))
                    + " | num_rows=" + stringValue(getLong(rs, "num_rows"))
                    + " | last_analyzed=" + stringValue(rs.getObject("last_analyzed")));
        }
        return indexes.isEmpty() ? null : String.join(System.lineSeparator(), indexes);
    }

    private String tableLoadStatistics(Connection connection, List<TableReference> tables, List<String> warnings) {
        return tableLoadStatisticsFromView(connection, tables, "dba_tab_modifications", warnings);
    }

    private String tableLoadStatisticsFromView(Connection connection, List<TableReference> tables, String viewName, List<String> warnings) {
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
                  FROM %s
                 WHERE %s
                 ORDER BY table_owner, table_name
                """.formatted(viewName, tableReferencePredicate("table_owner", "table_name", tables)))) {
            bindTableReferences(statement, tables);
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
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private String bindSamples(Connection connection, String sqlId, List<String> warnings) {
        List<String> gvWarnings = new ArrayList<>();
        String gvBinds = bindSamplesFromView(connection, sqlId, "gv$sql_bind_capture", true, gvWarnings);
        if (hasText(gvBinds)) {
            return section("GV$SQL_BIND_CAPTURE", gvBinds);
        }
        String vBinds = bindSamplesFromView(connection, sqlId, "v$sql_bind_capture", false, warnings);
        if (!hasText(vBinds)) {
            warnings.addAll(gvWarnings);
        }
        return hasText(vBinds) ? section("V$SQL_BIND_CAPTURE", vBinds) : null;
    }

    private String bindSamplesFromView(
            Connection connection,
            String sqlId,
            String viewName,
            boolean includeInstance,
            List<String> warnings
    ) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s inst_id,
                       name,
                       datatype_string,
                       value_string,
                       last_captured
                  FROM %s
                 WHERE sql_id = ?
                 ORDER BY %s position
                """.formatted(includeInstance ? "inst_id" : "CAST(NULL AS NUMBER)", viewName, includeInstance ? "inst_id, " : ""))) {
            statement.setString(1, sqlId);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                List<String> binds = new ArrayList<>();
                while (rs.next()) {
                    binds.add("inst=" + stringValue(getLong(rs, "inst_id"))
                            + " " + rs.getString("name")
                            + " " + stringValue(rs.getString("datatype_string"))
                            + " = " + stringValue(rs.getString("value_string"))
                            + " -- last_captured=" + stringValue(rs.getObject("last_captured")));
                }
                return binds.isEmpty() ? null : String.join(System.lineSeparator(), binds);
            }
        } catch (SQLException exception) {
            warnings.add(viewName + " query failed: " + exception.getMessage());
            return null;
        }
    }

    private List<TableReference> extractTableReferences(String sqlText) {
        Set<String> seen = new LinkedHashSet<>();
        List<TableReference> tables = new ArrayList<>();
        collectTableReferences(sqlText, DML_TARGET_PATTERN, seen, tables);
        collectTableReferences(sqlText, TABLE_PATTERN, seen, tables);
        return tables;
    }

    private List<TableReference> extractDmlTargetReferences(String sqlText) {
        Set<String> seen = new LinkedHashSet<>();
        List<TableReference> tables = new ArrayList<>();
        collectTableReferences(sqlText, DML_TARGET_PATTERN, seen, tables);
        return tables;
    }

    private List<TableReference> mergeTableReferences(List<TableReference> primary, List<TableReference> additional) {
        if (primary.isEmpty()) {
            return additional;
        }
        if (additional.isEmpty()) {
            return primary;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<TableReference> merged = new ArrayList<>();
        primary.forEach(table -> addTableReference(table.owner(), table.tableName(), seen, merged));
        additional.forEach(table -> addTableReference(table.owner(), table.tableName(), seen, merged));
        return merged;
    }

    private void mergeTableReferencesInto(List<TableReference> target, List<TableReference> source, Set<String> seen) {
        source.forEach(table -> addTableReference(table.owner(), table.tableName(), seen, target));
    }

    private List<TableReference> enrichTableReferences(Connection connection, List<TableReference> tables, List<String> warnings) {
        if (tables.isEmpty()) {
            return tables;
        }
        Set<String> seen = new LinkedHashSet<>();
        List<TableReference> enriched = new ArrayList<>();
        tables.forEach(table -> addTableReference(table.owner(), table.tableName(), seen, enriched));
        resolveSynonymTargets(connection, tables, seen, enriched, warnings);
        resolveViewDependencies(connection, tables, seen, enriched, warnings);
        return enriched;
    }

    private void resolveSynonymTargets(
            Connection connection,
            List<TableReference> tables,
            Set<String> seen,
            List<TableReference> enriched,
            List<String> warnings
    ) {
        List<TableReference> ownedTables = tables.stream()
                .filter(table -> table.owner() != null)
                .toList();
        if (ownedTables.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_owner,
                       table_name
                  FROM dba_synonyms
                 WHERE table_owner IS NOT NULL
                   AND table_name IS NOT NULL
                   AND %s
                """.formatted(tableReferencePredicate("owner", "synonym_name", ownedTables)))) {
            bindTableReferences(statement, ownedTables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    addTableReference(rs.getString("table_owner"), rs.getString("table_name"), seen, enriched);
                }
            }
        } catch (SQLException exception) {
            warnings.add("dba_synonyms query failed: " + exception.getMessage());
        }
    }

    private void resolveViewDependencies(
            Connection connection,
            List<TableReference> tables,
            Set<String> seen,
            List<TableReference> enriched,
            List<String> warnings
    ) {
        List<TableReference> ownedTables = tables.stream()
                .filter(table -> table.owner() != null)
                .toList();
        if (ownedTables.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT referenced_owner,
                       referenced_name
                  FROM dba_dependencies
                 WHERE referenced_owner IS NOT NULL
                   AND referenced_name IS NOT NULL
                   AND referenced_type IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW')
                   AND %s
                """.formatted(tableReferencePredicate("owner", "name", ownedTables)))) {
            bindTableReferences(statement, ownedTables);
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    addTableReference(rs.getString("referenced_owner"), rs.getString("referenced_name"), seen, enriched);
                }
            }
        } catch (SQLException exception) {
            warnings.add("dba_dependencies query failed: " + exception.getMessage());
        }
    }

    private void collectTableReferences(
            String sqlText,
            Pattern pattern,
            Set<String> seen,
            List<TableReference> tables
    ) {
        if (!hasText(sqlText)) {
            return;
        }
        Matcher matcher = pattern.matcher(sqlText);
        while (matcher.find()) {
            String token = matcher.group(1).replace("\"", "").trim();
            if (token.startsWith("(")) {
                continue;
            }
            String owner = null;
            String table = token;
            if (token.contains(".")) {
                owner = token.substring(0, token.lastIndexOf('.')).toUpperCase(Locale.ROOT);
                table = token.substring(token.lastIndexOf('.') + 1);
            }
            String normalized = table.toLowerCase(Locale.ROOT);
            if (!SKIP_TABLE_TOKENS.contains(normalized)) {
                addTableReference(owner, table, seen, tables);
            }
        }
    }

    private void addTableReference(String owner, String table, Set<String> seen, List<TableReference> tables) {
        if (!hasText(table)) {
            return;
        }
        String normalizedOwner = hasText(owner) ? owner.replace("\"", "").trim().toUpperCase(Locale.ROOT) : null;
        String normalizedTable = table.replace("\"", "").trim().toUpperCase(Locale.ROOT);
        if (!hasText(normalizedTable) || SKIP_TABLE_TOKENS.contains(normalizedTable.toLowerCase(Locale.ROOT))) {
            return;
        }
        TableReference reference = new TableReference(normalizedOwner, normalizedTable);
        String key = stringValue(reference.owner()) + "." + reference.tableName();
        if (seen.add(key)) {
            tables.add(reference);
        }
    }

    private String indexCollectionDiagnostics(List<TableReference> tables, String planUsedIndexes, String metadataSource) {
        List<String> lines = new ArrayList<>();
        lines.add("target_tables=" + (tables.isEmpty()
                ? "-"
                : tables.stream()
                .map(table -> stringValue(table.owner()) + "." + table.tableName())
                .collect(java.util.stream.Collectors.joining(", "))));
        lines.add("related_index_source=" + stringValue(metadataSource));
        lines.add("plan_used_indexes_collected=" + (hasText(planUsedIndexes) ? "YES" : "NO"));
        lines.add("table_reference_sources=GV$SQL_PLAN,V$SQL_PLAN,DBA_HIST_SQL_PLAN,DML_TARGET,SQL_TEXT_FALLBACK");
        return String.join(System.lineSeparator(), lines);
    }

    private String tableReferencePredicate(String ownerColumn, String tableColumn, List<TableReference> tables) {
        return tables.stream()
                .map(table -> table.owner() == null
                        ? tableColumn + " = ?"
                        : "(" + ownerColumn + " = ? AND " + tableColumn + " = ?)")
                .collect(java.util.stream.Collectors.joining(" OR ", "(", ")"));
    }

    private void bindTableReferences(PreparedStatement statement, List<TableReference> tables) throws SQLException {
        int index = 1;
        for (TableReference table : tables) {
            if (table.owner() != null) {
                statement.setString(index++, table.owner());
            }
            statement.setString(index++, table.tableName());
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

package dbinc.sqladvisor.domain.awr.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import org.springframework.stereotype.Component;

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

@Component
public class AwrSqlTuningAdvisor {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_.$#\"]+)(?:\\s+(?:as\\s+)?([a-zA-Z][a-zA-Z0-9_$#]*))?"
    );
    private static final Pattern PREDICATE_PATTERN = Pattern.compile(
            "(?i)\\b([a-zA-Z][a-zA-Z0-9_$#]*)(?:\\.([a-zA-Z][a-zA-Z0-9_$#]*))?\\s*(=|>=|<=|<>|!=|>|<|like\\b|in\\s*\\()"
    );
    private static final Pattern TABLE_STATS_PATTERN = Pattern.compile(
            "(?im)^([a-zA-Z0-9_$#]+\\.)?([a-zA-Z0-9_$#]+)\\s+num_rows=([^,\\r\\n]+),\\s+blocks=([^,\\r\\n]+).*?last_analyzed=([^,\\r\\n]+)"
    );
    private static final Pattern TABLE_LOAD_PATTERN = Pattern.compile(
            "(?im)^([a-zA-Z0-9_$#]+\\.)?([a-zA-Z0-9_$#]+)\\s+inserts=([^,\\r\\n]+),\\s+updates=([^,\\r\\n]+),\\s+deletes=([^,\\r\\n]+),\\s+changed_rows=([^,\\r\\n]+),\\s+last_dml=([^,\\r\\n]+)"
    );
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "join", "inner", "left", "right", "full", "cross", "on", "group",
            "order", "having", "connect", "start", "union", "minus", "intersect"
    );
    private static final Set<String> ORACLE_DICTIONARY_OBJECTS = Set.of(
            "ALL_CONSTRAINTS", "ALL_CONS_COLUMNS", "ALL_TABLES", "ALL_TAB_COLUMNS", "ALL_INDEXES", "ALL_IND_COLUMNS",
            "DBA_CONSTRAINTS", "DBA_CONS_COLUMNS", "DBA_TABLES", "DBA_TAB_COLUMNS", "DBA_INDEXES", "DBA_IND_COLUMNS",
            "USER_CONSTRAINTS", "USER_CONS_COLUMNS", "USER_TABLES", "USER_TAB_COLUMNS", "USER_INDEXES", "USER_IND_COLUMNS",
            "V$SQL", "V$SQLAREA", "V$SQLSTATS", "V$SQL_PLAN", "V$SQL_PLAN_STATISTICS_ALL",
            "GV$SQL", "GV$SQLAREA", "GV$SQLSTATS", "GV$SQL_PLAN", "GV$SQL_PLAN_STATISTICS_ALL"
    );

    private record TableVolumeContext(
            String tableName,
            Long numRows,
            Long blocks,
            Long changedRows,
            String lastAnalyzed,
            String lastDml
    ) {
        boolean hasAnyEvidence() {
            return numRows != null || blocks != null || changedRows != null
                    || hasValue(lastAnalyzed) || hasValue(lastDml);
        }

        String evidenceText() {
            List<String> parts = new ArrayList<>();
            if (numRows != null) {
                parts.add("num_rows=" + numRows);
            }
            if (blocks != null) {
                parts.add("blocks=" + blocks);
            }
            if (changedRows != null) {
                parts.add("recent_changed_rows=" + changedRows);
            }
            if (hasValue(lastAnalyzed)) {
                parts.add("last_analyzed=" + lastAnalyzed);
            }
            if (hasValue(lastDml)) {
                parts.add("last_dml=" + lastDml);
            }
            return String.join(", ", parts);
        }

        private static boolean hasValue(String value) {
            return value != null && !value.isBlank() && !"-".equals(value.trim());
        }
    }

    public AwrDtos.SqlTuningResponse tune(
            Long reportId,
            String sqlId,
            String question,
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest request,
            List<String> citations
    ) {
        List<String> symptoms = symptoms(metric);
        List<String> missingInputs = missingInputs(metric, request);
        List<AwrDtos.IndexRecommendationResponse> indexRecommendations = indexRecommendations(sqlId, metric, request);
        List<String> rewriteRecommendations = rewriteRecommendations(metric);
        List<String> validationSteps = validationSteps(sqlId, metric, indexRecommendations);

        return new AwrDtos.SqlTuningResponse(
                null,
                reportId,
                sqlId,
                question,
                request,
                metric,
                summary(metric, indexRecommendations),
                symptoms,
                indexRecommendations,
                rewriteRecommendations,
                validationSteps,
                missingInputs,
                citations,
                "rule-based-local-advisor",
                confidence(metric, request, indexRecommendations),
                LocalDateTime.now()
        );
    }

    private List<String> symptoms(AwrDtos.SqlMetricResponse metric) {
        List<String> symptoms = new ArrayList<>();
        if (metric.bufferGets() != null && metric.bufferGets() > 10_000_000) {
            symptoms.add("High buffer gets: review predicate selectivity, join order, and index access paths.");
        }
        if (metric.diskReads() != null && metric.diskReads() > 100_000) {
            symptoms.add("High disk reads: check full scans, partition pruning, and physical I/O wait evidence.");
        }
        if (metric.executions() != null && metric.executions() > 10_000) {
            symptoms.add("High execution count: small per-execution cost can become a large cumulative load.");
        }
        if (metric.cpuTimeSec() != null && metric.elapsedTimeSec() != null
                && metric.elapsedTimeSec() > 0
                && metric.cpuTimeSec() > metric.elapsedTimeSec() * 0.65) {
            symptoms.add("CPU-heavy elapsed time: inspect sorts, hash joins, filters, and function calls.");
        }
        if (hasText(metric.interpretationHint())) {
            symptoms.add(metric.interpretationHint());
        }
        if (symptoms.isEmpty()) {
            symptoms.add("This SQL appears in the AWR Top SQL set; validate execution plan and object statistics before changing it.");
        }
        return symptoms;
    }

    private List<AwrDtos.IndexRecommendationResponse> indexRecommendations(
            String sqlId,
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest request
    ) {
        if (!hasText(metric.sqlText()) || !isIndexCandidateMetric(metric, request)) {
            return List.of();
        }

        Map<String, LinkedHashSet<String>> columnsByTable = predicateColumnsByTable(metric.sqlText());
        Map<String, TableVolumeContext> volumeByTable = tableVolumeContexts(request);
        List<AwrDtos.IndexRecommendationResponse> recommendations = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : columnsByTable.entrySet()) {
            List<String> columns = entry.getValue().stream().limit(3).toList();
            if (columns.isEmpty()) {
                continue;
            }
            String tableName = entry.getKey();
            if (isOracleDictionaryObject(tableName)) {
                continue;
            }
            TableVolumeContext volumeContext = volumeByTable.get(normalizeTableKey(tableName));
            String indexName = indexName(tableName, columns);
            String ddl = "CREATE INDEX " + indexName + " ON " + tableName
                    + " (" + String.join(", ", columns) + ");";
            recommendations.add(new AwrDtos.IndexRecommendationResponse(
                    tableName,
                    columns,
                    ddl,
                    buildSteps(ddl, indexName, volumeContext),
                    postCreateSteps(volumeContext),
                    recommendationReason(metric, request, volumeContext),
                    expectedBenefit(metric, volumeContext),
                    risk(volumeContext),
                    "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR('" + sqlId + "', NULL, 'ALLSTATS LAST +PEEKED_BINDS'));"
            ));
        }
        return recommendations.stream().limit(3).toList();
    }

    private Map<String, LinkedHashSet<String>> predicateColumnsByTable(String sqlText) {
        Map<String, String> aliasToTable = tableAliases(sqlText);
        String singleTable = aliasToTable.values().stream().distinct().limit(2).count() == 1
                ? aliasToTable.values().stream().findFirst().orElse(null)
                : null;

        Map<String, LinkedHashSet<String>> columnsByTable = new LinkedHashMap<>();
        Matcher matcher = PREDICATE_PATTERN.matcher(sqlText);
        while (matcher.find()) {
            String ownerOrColumn = cleanIdentifier(matcher.group(1));
            String column = cleanIdentifier(matcher.group(2));
            String tableName;
            if (hasText(column)) {
                tableName = aliasToTable.getOrDefault(ownerOrColumn.toLowerCase(Locale.ROOT), ownerOrColumn);
            } else {
                column = ownerOrColumn;
                tableName = singleTable;
            }
            if (!hasText(tableName) || !hasText(column) || isKeyword(column)) {
                continue;
            }
            columnsByTable.computeIfAbsent(tableName, key -> new LinkedHashSet<>()).add(column);
        }
        return columnsByTable;
    }

    private Map<String, String> tableAliases(String sqlText) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = TABLE_PATTERN.matcher(sqlText);
        while (matcher.find()) {
            String tableName = cleanIdentifier(matcher.group(1));
            String alias = cleanIdentifier(matcher.group(2));
            if (!hasText(tableName) || isKeyword(tableName)) {
                continue;
            }
            aliases.put(simpleName(tableName).toLowerCase(Locale.ROOT), tableName);
            if (hasText(alias) && !isKeyword(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), tableName);
            }
        }
        return aliases;
    }

    private List<String> rewriteRecommendations(AwrDtos.SqlMetricResponse metric) {
        List<String> recommendations = new ArrayList<>();
        recommendations.add("Compare the current plan hash with the same SQL_ID across nearby AWR snapshots.");
        if (containsOracleDictionaryObject(metric.sqlText())) {
            recommendations.add("Oracle data dictionary or dynamic performance views are detected; DBA_*/V$/GV$ evidence can be used for diagnosis, but do not create user indexes on these views.");
            recommendations.add("For dictionary-view SQL, tune by reducing scope predicates, checking dictionary statistics, narrowing AWR/ASH windows, and avoiding repeated polling rather than adding index DDL.");
        }
        if (metric.executions() != null && metric.executions() > 10_000) {
            recommendations.add("Review whether the caller can batch work, reduce looped executions, or reuse bind variables more effectively.");
        }
        if (metric.bufferGets() != null && metric.bufferGets() > 10_000_000) {
            recommendations.add("Check join order and filter placement before adding an index.");
        }
        if (metric.diskReads() != null && metric.diskReads() > 100_000) {
            recommendations.add("Verify partition pruning and avoid functions on indexed filter columns where possible.");
        }
        if (!hasText(metric.sqlText())) {
            recommendations.add("Load full SQL text before proposing SQL rewrites.");
        }
        return recommendations;
    }

    private List<String> validationSteps(
            String sqlId,
            AwrDtos.SqlMetricResponse metric,
            List<AwrDtos.IndexRecommendationResponse> indexRecommendations
    ) {
        List<String> steps = new ArrayList<>();
        steps.add("SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR('" + sqlId + "', NULL, 'ALLSTATS LAST +PEEKED_BINDS'));");
        steps.add("Compare elapsed_delta, cpu_delta, buffer_gets_delta, disk_reads_delta, and executions_delta in DBA_HIST_SQLSTAT for the same snapshot range.");
        steps.add("Check DBA_TAB_STATISTICS and DBA_IND_STATISTICS for stale stats and current index coverage.");
        if (containsOracleDictionaryObject(metric.sqlText())) {
            steps.add("For dictionary-view SQL, validate DBA_* predicates, V$/GV$ instance scope, ASH/AWR time range, and dictionary statistics before considering hints or application-side caching.");
        }
        if (!indexRecommendations.isEmpty()) {
            steps.add("Test the candidate index in a non-production environment and compare plan hash, logical reads, and elapsed time before applying it.");
            steps.add("Before applying candidate indexes, compare table NUM_ROWS, BLOCKS, LAST_ANALYZED, and recent INSERTS/UPDATES/DELETES to estimate build time and DML/load overhead.");
            if (indexRecommendations.stream().anyMatch(item -> item.buildSteps() != null && !item.buildSteps().isEmpty())) {
                steps.add("If a large-table build uses NOLOGGING for speed, treat it as a temporary build option and immediately switch the index back to LOGGING after creation.");
            }
        }
        if (metric.planHashValue() != null) {
            steps.add("Use plan_hash_value=" + metric.planHashValue() + " as the baseline plan during validation.");
        }
        return steps;
    }

    private List<String> missingInputs(AwrDtos.SqlMetricResponse metric, AwrDtos.SqlTuningRequest request) {
        List<String> missing = new ArrayList<>();
        if (!hasText(metric.sqlText())) {
            missing.add("full SQL text");
        }
        if (request == null || !hasText(request.executionPlan())) {
            missing.add("actual execution plan from DBMS_XPLAN or SQL Monitor");
        }
        if (request == null || !hasText(request.schemaDdl())) {
            missing.add("table/object metadata and column statistics");
        }
        if (request == null || !hasText(request.existingIndexes())) {
            missing.add("existing index definitions");
        }
        if (request == null || !hasText(request.bindSamples())) {
            missing.add("bind variable samples");
        }
        if (tableVolumeContexts(request).isEmpty()) {
            missing.add("table data volume/load statistics such as num_rows, blocks, last_analyzed, and recent inserts/updates/deletes");
        }
        return missing;
    }

    private String summary(AwrDtos.SqlMetricResponse metric, List<AwrDtos.IndexRecommendationResponse> indexRecommendations) {
        StringBuilder builder = new StringBuilder();
        builder.append("SQL_ID ").append(metric.sqlId())
                .append(" is ranked ").append(metric.rankNo())
                .append(" in ").append(metric.sectionName()).append(".");
        if (metric.elapsedTimeSec() != null) {
            builder.append(" elapsed_time_sec=").append(metric.elapsedTimeSec()).append(".");
        }
        if (metric.bufferGets() != null) {
            builder.append(" buffer_gets=").append(metric.bufferGets()).append(".");
        }
        if (metric.diskReads() != null) {
            builder.append(" disk_reads=").append(metric.diskReads()).append(".");
        }
        if (indexRecommendations.isEmpty()) {
            if (containsOracleDictionaryObject(metric.sqlText())) {
                builder.append(" No index DDL candidate is emitted because the SQL references Oracle data dictionary or dynamic performance views.");
            } else {
                builder.append(" No concrete index DDL candidate is emitted until SQL text, plan, and object metadata are sufficient.");
            }
        } else {
            builder.append(" Candidate index recommendations are heuristic and must be validated before use.");
        }
        return builder.toString();
    }

    private String confidence(
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest request,
            List<AwrDtos.IndexRecommendationResponse> indexRecommendations
    ) {
        if (!hasText(metric.sqlText())) {
            return "low";
        }
        if (!indexRecommendations.isEmpty()
                && request != null
                && hasText(request.executionPlan())
                && hasText(request.schemaDdl())
                && hasText(request.existingIndexes())
                && !tableVolumeContexts(request).isEmpty()) {
            return "high";
        }
        return indexRecommendations.isEmpty() ? "low" : "medium";
    }

    private boolean isIndexCandidateMetric(AwrDtos.SqlMetricResponse metric, AwrDtos.SqlTuningRequest request) {
        if (containsOracleDictionaryObject(metric.sqlText())) {
            return false;
        }
        return "Manual SQL".equals(metric.sectionName())
                || ("Direct DB SQL".equals(metric.sectionName()) && hasFullTableScan(request))
                || (metric.bufferGets() != null && metric.bufferGets() > 1_000_000)
                || (metric.diskReads() != null && metric.diskReads() > 10_000);
    }

    private boolean hasFullTableScan(AwrDtos.SqlTuningRequest request) {
        return request != null
                && hasText(request.executionPlan())
                && request.executionPlan().toUpperCase(Locale.ROOT).contains("TABLE ACCESS FULL");
    }

    private String recommendationReason(
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest request,
            TableVolumeContext volumeContext
    ) {
        String base;
        if ("Direct DB SQL".equals(metric.sectionName()) && hasFullTableScan(request)) {
            base = "Direct DB execution plan shows TABLE ACCESS FULL and predicate columns were found in SQL text.";
        } else {
            base = "Predicate columns were found in SQL text and the AWR metric suggests high logical or physical read cost.";
        }
        if (volumeContext != null && volumeContext.hasAnyEvidence()) {
            return base + " Table volume/load evidence considered: " + volumeContext.evidenceText() + ".";
        }
        return base + " Table volume/load statistics were not available, so validate data size before creating the index.";
    }

    private String expectedBenefit(AwrDtos.SqlMetricResponse metric, TableVolumeContext volumeContext) {
        if (volumeContext != null && volumeContext.numRows() != null && volumeContext.numRows() < 10_000) {
            return "Benefit may be limited because the table is small; full scan can be cheaper than extra index access.";
        }
        if (metric.diskReads() != null && metric.diskReads() > 100_000) {
            return "May reduce physical reads if the predicates are selective and the current plan is scanning too much data.";
        }
        return "May reduce logical reads and CPU if the predicates are selective and the optimizer can use the access path.";
    }

    private List<String> buildSteps(String ddl, String indexName, TableVolumeContext volumeContext) {
        if (!isLargeTable(volumeContext)) {
            return List.of();
        }
        String noLoggingDdl = ddl.replaceFirst(";\\s*$", " NOLOGGING;");
        return List.of(
                noLoggingDdl,
                "ALTER INDEX " + indexName + " LOGGING;"
        );
    }

    private List<String> postCreateSteps(TableVolumeContext volumeContext) {
        if (!isLargeTable(volumeContext)) {
            return List.of();
        }
        return List.of(
                "Gather index statistics after creation.",
                "Confirm backup/Data Guard recovery policy before using NOLOGGING."
        );
    }

    private boolean isLargeTable(TableVolumeContext volumeContext) {
        return volumeContext != null
                && ((volumeContext.numRows() != null && volumeContext.numRows() >= 1_000_000)
                || (volumeContext.blocks() != null && volumeContext.blocks() >= 100_000));
    }

    private String risk(TableVolumeContext volumeContext) {
        List<String> risks = new ArrayList<>();
        risks.add("Adds DML/storage overhead and can cause plan regression; test with the current plan, binds, and object statistics first.");
        if (volumeContext == null || !volumeContext.hasAnyEvidence()) {
            risks.add("Data volume/load evidence is missing, so index maintenance cost is unknown.");
            return String.join(" ", risks);
        }
        if (volumeContext.numRows() != null && volumeContext.numRows() > 10_000_000) {
            risks.add("Large table volume (" + volumeContext.evidenceText() + ") can make index build time, segment size, and stats gathering material.");
        }
        if (volumeContext.changedRows() != null && volumeContext.changedRows() > 100_000) {
            risks.add("Recent load/DML volume changed_rows=" + volumeContext.changedRows()
                    + " means extra index maintenance can slow ETL or write-heavy windows.");
        }
        if (volumeContext.numRows() != null && volumeContext.numRows() < 10_000) {
            risks.add("Small table volume may not justify another index unless the SQL runs very frequently.");
        }
        return String.join(" ", risks);
    }

    private Map<String, TableVolumeContext> tableVolumeContexts(AwrDtos.SqlTuningRequest request) {
        if (request == null || !hasText(request.schemaDdl())) {
            return Map.of();
        }
        Map<String, TableVolumeContext> contexts = new LinkedHashMap<>();
        Matcher statsMatcher = TABLE_STATS_PATTERN.matcher(request.schemaDdl());
        while (statsMatcher.find()) {
            String tableName = cleanIdentifier(statsMatcher.group(2));
            contexts.put(normalizeTableKey(tableName), new TableVolumeContext(
                    tableName,
                    parseLong(statsMatcher.group(3)),
                    parseLong(statsMatcher.group(4)),
                    null,
                    cleanOptional(statsMatcher.group(5)),
                    null
            ));
        }
        Matcher loadMatcher = TABLE_LOAD_PATTERN.matcher(request.schemaDdl());
        while (loadMatcher.find()) {
            String tableName = cleanIdentifier(loadMatcher.group(2));
            String key = normalizeTableKey(tableName);
            TableVolumeContext current = contexts.getOrDefault(key, new TableVolumeContext(tableName, null, null, null, null, null));
            contexts.put(key, new TableVolumeContext(
                    current.tableName(),
                    current.numRows(),
                    current.blocks(),
                    parseLong(loadMatcher.group(6)),
                    current.lastAnalyzed(),
                    cleanOptional(loadMatcher.group(7))
            ));
        }
        return contexts;
    }

    private String indexName(String tableName, List<String> columns) {
        String base = "idx_" + simpleName(tableName) + "_" + String.join("_", columns);
        String normalized = base.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
    }

    private String normalizeTableKey(String tableName) {
        return simpleName(tableName).toUpperCase(Locale.ROOT);
    }

    private boolean containsOracleDictionaryObject(String sqlText) {
        if (!hasText(sqlText)) {
            return false;
        }
        return tableAliases(sqlText).values().stream().anyMatch(this::isOracleDictionaryObject);
    }

    private boolean isOracleDictionaryObject(String tableName) {
        if (!hasText(tableName)) {
            return false;
        }
        String normalized = cleanIdentifier(tableName).toUpperCase(Locale.ROOT);
        String simple = simpleName(normalized).toUpperCase(Locale.ROOT);
        return ORACLE_DICTIONARY_OBJECTS.contains(simple)
                || simple.startsWith("ALL_")
                || simple.startsWith("DBA_")
                || simple.startsWith("USER_")
                || simple.startsWith("V$")
                || simple.startsWith("GV$");
    }

    private Long parseLong(String value) {
        String normalized = cleanOptional(value);
        if (!hasText(normalized) || "-".equals(normalized)) {
            return null;
        }
        String digits = normalized.replaceAll("[^0-9-]", "");
        if (!hasText(digits) || "-".equals(digits)) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String cleanOptional(String value) {
        return value == null ? null : value.trim();
    }

    private String simpleName(String value) {
        String clean = cleanIdentifier(value);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    private String cleanIdentifier(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\"", "").trim();
    }

    private boolean isKeyword(String value) {
        return value != null && SQL_KEYWORDS.contains(value.toLowerCase(Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

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
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "join", "inner", "left", "right", "full", "cross", "on", "group",
            "order", "having", "connect", "start", "union", "minus", "intersect"
    );

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
        List<AwrDtos.IndexRecommendationResponse> indexRecommendations = indexRecommendations(sqlId, metric);
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

    private List<AwrDtos.IndexRecommendationResponse> indexRecommendations(String sqlId, AwrDtos.SqlMetricResponse metric) {
        if (!hasText(metric.sqlText()) || !isIndexCandidateMetric(metric)) {
            return List.of();
        }

        Map<String, LinkedHashSet<String>> columnsByTable = predicateColumnsByTable(metric.sqlText());
        List<AwrDtos.IndexRecommendationResponse> recommendations = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : columnsByTable.entrySet()) {
            List<String> columns = entry.getValue().stream().limit(3).toList();
            if (columns.isEmpty()) {
                continue;
            }
            String tableName = entry.getKey();
            String ddl = "CREATE INDEX " + indexName(tableName, columns) + " ON " + tableName
                    + " (" + String.join(", ", columns) + ");";
            recommendations.add(new AwrDtos.IndexRecommendationResponse(
                    tableName,
                    columns,
                    ddl,
                    "Predicate columns were found in SQL text and the AWR metric suggests high logical or physical read cost.",
                    expectedBenefit(metric),
                    "Adds DML/storage overhead and can cause plan regression; test with the current plan, binds, and object statistics first.",
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
        if (!indexRecommendations.isEmpty()) {
            steps.add("Test the candidate index in a non-production environment and compare plan hash, logical reads, and elapsed time before applying it.");
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
            missing.add("table DDL and column definitions");
        }
        if (request == null || !hasText(request.existingIndexes())) {
            missing.add("existing index definitions");
        }
        if (request == null || !hasText(request.bindSamples())) {
            missing.add("bind variable samples");
        }
        missing.add("object statistics and last_analyzed values");
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
            builder.append(" No concrete index DDL candidate is emitted until SQL text, plan, and object metadata are sufficient.");
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
                && hasText(request.existingIndexes())) {
            return "high";
        }
        return indexRecommendations.isEmpty() ? "low" : "medium";
    }

    private boolean isIndexCandidateMetric(AwrDtos.SqlMetricResponse metric) {
        return "Manual SQL".equals(metric.sectionName())
                || (metric.bufferGets() != null && metric.bufferGets() > 1_000_000)
                || (metric.diskReads() != null && metric.diskReads() > 10_000);
    }

    private String expectedBenefit(AwrDtos.SqlMetricResponse metric) {
        if (metric.diskReads() != null && metric.diskReads() > 100_000) {
            return "May reduce physical reads if the predicates are selective and the current plan is scanning too much data.";
        }
        return "May reduce logical reads and CPU if the predicates are selective and the optimizer can use the access path.";
    }

    private String indexName(String tableName, List<String> columns) {
        String base = "idx_" + simpleName(tableName) + "_" + String.join("_", columns);
        String normalized = base.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        return normalized.length() <= 30 ? normalized : normalized.substring(0, 30);
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

package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;

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

final class SqlTuningAccuracyGuard {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_.$#\"]+)(?:\\s+(?:as\\s+)?([a-zA-Z][a-zA-Z0-9_$#]*))?"
    );
    private static final Pattern PREDICATE_PATTERN = Pattern.compile(
            "(?i)\\b([a-zA-Z][a-zA-Z0-9_$#]*)(?:\\.([a-zA-Z][a-zA-Z0-9_$#]*))?\\s*(=|>=|<=|<>|!=|>|<|like\\b|in\\s*\\()"
    );
    private static final Pattern TABLE_STATS_PATTERN = Pattern.compile(
            "(?im)^([a-zA-Z0-9_$#]+\\.)?([a-zA-Z0-9_$#]+)\\s+num_rows=([^,\\r\\n]+),\\s+blocks=([^,\\r\\n]+)"
    );
    private static final Pattern COLUMN_STATS_PATTERN = Pattern.compile(
            "(?im)^([a-zA-Z0-9_$#]+)\\.([a-zA-Z0-9_$#]+)\\.([a-zA-Z0-9_$#]+)\\s+num_distinct=([^,\\r\\n]+)"
    );
    private static final Pattern INDEX_LINE_PATTERN = Pattern.compile(
            "(?im)^\\s*([A-Za-z0-9_.$#\"]+)\\s*\\|\\s*([A-Za-z0-9_.$#\"]+)\\s*\\|.*?columns=\\(([^)]*)\\)"
    );
    private static final Pattern BIND_PATTERN =
            Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9_$#]*|[0-9]+)");
    private static final Pattern DML_TARGET_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:/\\*.*?\\*/\\s*)*(?:"
                    + "insert\\s+(?:/\\*.*?\\*/\\s*)*into\\s+"
                    + "|update\\s+(?:/\\*.*?\\*/\\s*)*"
                    + "|merge\\s+(?:/\\*.*?\\*/\\s*)*into\\s+"
                    + "|delete\\s+(?:/\\*.*?\\*/\\s*)*from\\s+"
                    + ")([A-Za-z0-9_.$#\"]+)"
    );
    private static final Pattern AGGREGATE_POWER_PATTERN = Pattern.compile(
            "(?is)\\bPOWER\\s*\\(\\s*(?:COUNT|SUM|AVG|MIN|MAX)\\s*\\("
    );
    private static final Pattern AGGREGATE_BINARY_MATH_PATTERN = Pattern.compile(
            "(?is)\\b(?:COUNT|SUM|AVG|MIN|MAX)\\s*\\([^)]*\\)\\s*[*+/\\-]\\s*"
                    + "(?:COUNT|SUM|AVG|MIN|MAX)\\s*\\("
    );
    private static final Pattern SIMPLE_COUNT_SELF_JOIN_PATTERN = Pattern.compile(
            "(?is)^SELECT\\s+COUNT\\s*\\(\\s*\\*\\s*\\)\\s+FROM\\s+"
                    + "([A-Z0-9_.$#\"]+)\\s+([A-Z][A-Z0-9_$#]*)\\s*,\\s*"
                    + "([A-Z0-9_.$#\"]+)\\s+([A-Z][A-Z0-9_$#]*)\\s+WHERE\\s+(.+)$"
    );

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "where", "join", "inner", "left", "right", "full", "cross", "on", "group",
            "order", "having", "connect", "start", "union", "minus", "intersect"
    );

    private SqlTuningAccuracyGuard() {
    }

    static AwrDtos.SqlTuningResponse refine(AwrDtos.SqlTuningResponse local) {
        if (local == null || local.metric() == null) {
            return local;
        }

        AwrDtos.SqlMetricResponse metric = local.metric();
        AwrDtos.SqlTuningRequest input = local.input();
        List<String> deterministicSymptoms = merge(local.symptoms(), perExecutionSymptoms(metric));

        List<AwrDtos.IndexRecommendationResponse> refinedIndexes = new ArrayList<>();
        if (local.indexRecommendations() != null) {
            for (AwrDtos.IndexRecommendationResponse recommendation : local.indexRecommendations()) {
                AwrDtos.IndexRecommendationResponse refined =
                        refineIndexCandidate(metric, input, recommendation);
                if (refined != null) {
                    refinedIndexes.add(refined);
                }
            }
        }

        List<String> missingInputs = new ArrayList<>(
                local.missingInputs() == null ? List.of() : local.missingInputs()
        );
        if (input != null
                && hasText(input.schemaDdl())
                && columnDistinctCounts(input).isEmpty()
                && !missingInputs.contains("column selectivity statistics such as NUM_DISTINCT/DENSITY")) {
            missingInputs.add("column selectivity statistics such as NUM_DISTINCT/DENSITY");
        }

        String summary = deterministicSummary(local.summary());
        String deterministicRewrite = redundantCountSelfJoinRewrite(
                input == null ? null : input.sqlText()
        );
        if (deterministicRewrite != null) {
            summary = "동일 테이블 자기조인으로 필터 대상 N건이 N×N건으로 증가합니다. "
                    + "단일 테이블의 필터 건수 조회가 목적이라는 전제로 자기조인을 제거했습니다.";
        }

        return new AwrDtos.SqlTuningResponse(
                local.tuningId(),
                local.reportId(),
                local.sqlId(),
                local.question(),
                local.input(),
                local.metric(),
                summary,
                deterministicSymptoms,
                refinedIndexes,
                local.rewriteRecommendations(),
                deterministicRewrite != null ? deterministicRewrite : local.rewrittenSql(),
                local.rewriteRisks(),
                local.validationSteps(),
                missingInputs,
                local.citations(),
                local.model(),
                confidence(local, refinedIndexes),
                local.createdAt() == null ? LocalDateTime.now() : local.createdAt()
        );
    }

    static AwrDtos.SqlTuningResponse mergeLlm(
            AwrDtos.SqlTuningResponse authoritative,
            AwrDtos.SqlTuningResponse llm
    ) {
        if (authoritative == null) {
            return llm;
        }
        if (llm == null) {
            return authoritative;
        }

        String safeRewrite = safeRewrite(authoritative, llm.rewrittenSql());
        boolean rejectedRewrite = hasText(llm.rewrittenSql()) && safeRewrite == null;
        List<String> rewriteRisks = merge(authoritative.rewriteRisks(), llm.rewriteRisks());
        if (rejectedRewrite) {
            rewriteRisks = merge(
                    rewriteRisks,
                    List.of("AI SQL 재작성안은 문장 유형·DML 대상·바인드 보존 검증을 통과하지 못해 제외했습니다.")
            );
        }

        String summary = authoritative.summary();
        if (!rejectedRewrite && compatibleNarrative(authoritative, llm.summary())) {
            summary = llm.summary().trim();
        }

        return new AwrDtos.SqlTuningResponse(
                authoritative.tuningId(),
                authoritative.reportId(),
                authoritative.sqlId(),
                authoritative.question(),
                authoritative.input(),
                authoritative.metric(),
                summary,
                authoritative.symptoms(),
                authoritative.indexRecommendations(),
                merge(authoritative.rewriteRecommendations(), llm.rewriteRecommendations()),
                hasText(authoritative.rewrittenSql()) ? authoritative.rewrittenSql() : safeRewrite,
                rewriteRisks,
                authoritative.validationSteps(),
                authoritative.missingInputs(),
                merge(authoritative.citations(), llm.citations()),
                hasText(llm.model()) ? llm.model() : authoritative.model(),
                authoritative.confidence(),
                llm.createdAt() == null ? authoritative.createdAt() : llm.createdAt()
        );
    }

    private static AwrDtos.IndexRecommendationResponse refineIndexCandidate(
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest input,
            AwrDtos.IndexRecommendationResponse recommendation
    ) {
        if (recommendation == null || !hasText(recommendation.tableName())) {
            return null;
        }

        String tableName = recommendation.tableName();
        if (isOracleDictionaryObject(tableName) || isInsert(metric.sqlText())) {
            return null;
        }

        Long numRows = tableRows(input, tableName);
        if (numRows != null && numRows < 10_000) {
            return null;
        }

        boolean fullScan = hasFullScan(input, tableName);
        boolean strongPerExec = hasStrongPerExecutionSignal(metric);
        int evidenceScore = evidenceScore(metric, input, tableName, numRows, fullScan);

        if ("Manual SQL".equals(metric.sectionName()) && !hasManualEvidence(input)) {
            return null;
        }

        if (metric.executions() != null
                && metric.executions() > 0
                && !strongPerExec
                && !fullScan) {
            return null;
        }

        if (evidenceScore < 50) {
            return null;
        }

        List<String> columns = reorderColumns(
                metric.sqlText(),
                tableName,
                recommendation.columns(),
                input,
                numRows
        );

        if (columns.isEmpty()) {
            return null;
        }

        if (onlyLowSelectivityColumns(tableName, columns, input, numRows)) {
            return null;
        }

        if (coveredByExistingIndex(tableName, columns, input)) {
            return null;
        }

        boolean reordered = recommendation.columns() != null
                && !normalizeColumns(recommendation.columns()).equals(normalizeColumns(columns));

        String ddl = recommendation.ddlCandidate();
        List<String> buildSteps = recommendation.buildSteps();
        String reason = appendSentence(
                recommendation.reason(),
                "정확도 보정 근거 점수=" + evidenceScore + "/100"
        );

        if (reordered) {
            String indexName = indexName(tableName, columns);
            ddl = "CREATE INDEX " + indexName + " ON " + tableName
                    + " (" + String.join(", ", columns) + ");";
            buildSteps = rebuildBuildSteps(recommendation.buildSteps(), ddl, indexName);
            reason = appendSentence(
                    reason,
                    "동등 조건 컬럼은 NUM_DISTINCT/NUM_ROWS 선택도와 조건 연산자 우선순위를 반영해 컬럼 순서를 재정렬했습니다"
            );
        }

        return new AwrDtos.IndexRecommendationResponse(
                tableName,
                columns,
                ddl,
                buildSteps,
                recommendation.postCreateSteps(),
                reason,
                recommendation.expectedBenefit(),
                recommendation.risk(),
                recommendation.validationSql()
        );
    }

    private static int evidenceScore(
            AwrDtos.SqlMetricResponse metric,
            AwrDtos.SqlTuningRequest input,
            String tableName,
            Long numRows,
            boolean fullScan
    ) {
        int score = 0;

        if (input != null && hasText(input.executionPlan())) score += 20;
        if (fullScan) score += 20;
        if (highLogicalReadsPerExecution(metric)) score += 25;
        if (highPhysicalReadsPerExecution(metric)) score += 20;
        if (slowPerExecution(metric)) score += 10;
        if (input != null && hasText(input.schemaDdl())) score += 10;
        if (input != null && hasText(input.existingIndexes())) score += 10;
        if (input != null && hasText(input.bindSamples())) score += 5;
        if (numRows != null && numRows >= 1_000_000) score += 10;

        if ("Manual SQL".equals(metric.sectionName())) {
            if (input == null || !hasText(input.executionPlan())) score -= 25;
            if (input == null || !hasText(input.schemaDdl())) score -= 20;
            if (input == null || !hasText(input.existingIndexes())) score -= 20;
        }

        if (numRows != null && numRows < 10_000) score -= 50;

        return Math.max(0, Math.min(100, score));
    }

    private static List<String> perExecutionSymptoms(AwrDtos.SqlMetricResponse metric) {
        List<String> symptoms = new ArrayList<>();
        Double elapsed = perExecution(metric.elapsedTimeSec(), metric.executions());
        Double buffer = perExecution(metric.bufferGets(), metric.executions());
        Double disk = perExecution(metric.diskReads(), metric.executions());

        if (metric.executions() != null && metric.executions() > 0) {
            if (elapsed != null) {
                symptoms.add("실행당 평균 수행시간=" + format(elapsed) + "초");
            }
            if (buffer != null) {
                symptoms.add("실행당 Buffer Gets=" + format(buffer));
            }
            if (disk != null) {
                symptoms.add("실행당 Disk Reads=" + format(disk));
            }
            if (!hasStrongPerExecutionSignal(metric)
                    && metric.executions() >= 10_000) {
                symptoms.add("누적 부하는 크지만 실행당 비용은 낮습니다. 인덱스보다 호출 횟수/반복 실행 감소를 먼저 검토하세요.");
            }
        }
        return symptoms;
    }

    private static String deterministicSummary(String original) {
        return hasText(original) ? original.trim() : "SQL 성능 분석이 완료되었습니다.";
    }

    private static String confidence(
            AwrDtos.SqlTuningResponse local,
            List<AwrDtos.IndexRecommendationResponse> indexes
    ) {
        AwrDtos.SqlTuningRequest input = local.input();
        AwrDtos.SqlMetricResponse metric = local.metric();

        if (containsOracleDictionary(metric == null ? null : metric.sqlText())) {
            return "low";
        }

        int evidence = 0;
        if (input != null && hasText(input.executionPlan())) evidence += 2;
        if (input != null && hasText(input.schemaDdl())) evidence += 2;
        if (input != null && hasText(input.existingIndexes())) evidence += 2;
        if (input != null && hasText(input.bindSamples())) evidence += 1;
        if (metric != null && metric.executions() != null && metric.executions() > 0) evidence += 1;
        if (metric != null && hasStrongPerExecutionSignal(metric)) evidence += 1;
        if (!indexes.isEmpty()) evidence += 1;

        if (evidence >= 7) return "high";
        if (evidence >= 4) return "medium";
        return "low";
    }

    private static boolean hasManualEvidence(AwrDtos.SqlTuningRequest input) {
        return input != null
                && hasText(input.executionPlan())
                && hasText(input.schemaDdl())
                && hasText(input.existingIndexes());
    }

    private static boolean highLogicalReadsPerExecution(AwrDtos.SqlMetricResponse metric) {
        Double perExec = perExecution(metric.bufferGets(), metric.executions());
        if (perExec != null) return perExec >= 10_000;
        return metric.bufferGets() != null && metric.bufferGets() >= 1_000_000;
    }

    private static boolean highPhysicalReadsPerExecution(AwrDtos.SqlMetricResponse metric) {
        Double perExec = perExecution(metric.diskReads(), metric.executions());
        if (perExec != null) return perExec >= 1_000;
        return metric.diskReads() != null && metric.diskReads() >= 10_000;
    }

    private static boolean slowPerExecution(AwrDtos.SqlMetricResponse metric) {
        Double perExec = perExecution(metric.elapsedTimeSec(), metric.executions());
        return perExec != null && perExec >= 1.0;
    }

    private static boolean hasStrongPerExecutionSignal(AwrDtos.SqlMetricResponse metric) {
        return metric != null
                && (highLogicalReadsPerExecution(metric)
                || highPhysicalReadsPerExecution(metric)
                || slowPerExecution(metric));
    }

    private static Double perExecution(Long total, Long executions) {
        if (total == null || executions == null || executions <= 0) return null;
        return total.doubleValue() / executions.doubleValue();
    }

    private static Double perExecution(Double total, Long executions) {
        if (total == null || executions == null || executions <= 0) return null;
        return total / executions.doubleValue();
    }

    private static String format(Double value) {
        if (value == null) return "-";
        if (Math.abs(value) >= 1_000) return String.format(Locale.ROOT, "%.0f", value);
        if (Math.abs(value) >= 10) return String.format(Locale.ROOT, "%.1f", value);
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static List<String> reorderColumns(
            String sqlText,
            String tableName,
            List<String> candidateColumns,
            AwrDtos.SqlTuningRequest input,
            Long numRows
    ) {
        if (candidateColumns == null || candidateColumns.size() < 2) {
            return candidateColumns == null ? List.of() : candidateColumns;
        }

        Map<String, Integer> operatorPriority = predicatePriorities(sqlText, tableName);
        Map<String, Long> distinctCounts = columnDistinctCounts(input);
        List<String> ordered = new ArrayList<>(candidateColumns);

        ordered.sort((left, right) -> {
            String leftKey = normalizeColumn(left);
            String rightKey = normalizeColumn(right);

            int leftPriority = operatorPriority.getOrDefault(leftKey, 9);
            int rightPriority = operatorPriority.getOrDefault(rightKey, 9);
            if (leftPriority != rightPriority) {
                return Integer.compare(leftPriority, rightPriority);
            }

            Double leftRatio = selectivityRatio(tableName, left, numRows, distinctCounts);
            Double rightRatio = selectivityRatio(tableName, right, numRows, distinctCounts);
            if (leftRatio != null && rightRatio != null
                    && Math.abs(leftRatio - rightRatio) > 0.000001) {
                return Double.compare(rightRatio, leftRatio);
            }
            if (leftRatio != null && rightRatio == null) return -1;
            if (leftRatio == null && rightRatio != null) return 1;

            return Integer.compare(
                    candidateColumns.indexOf(left),
                    candidateColumns.indexOf(right)
            );
        });

        return ordered;
    }

    private static Map<String, Integer> predicatePriorities(String sqlText, String targetTable) {
        if (!hasText(sqlText)) return Map.of();

        Map<String, String> aliases = tableAliases(sqlText);
        String simpleTarget = simpleName(targetTable).toUpperCase(Locale.ROOT);
        Map<String, Integer> priorities = new LinkedHashMap<>();
        Matcher matcher = PREDICATE_PATTERN.matcher(sqlText);

        while (matcher.find()) {
            String qualifierOrColumn = clean(matcher.group(1));
            String column = clean(matcher.group(2));
            String operator = matcher.group(3);

            String table;
            if (hasText(column)) {
                table = aliases.get(qualifierOrColumn.toLowerCase(Locale.ROOT));
            } else {
                column = qualifierOrColumn;
                table = aliases.values().stream().distinct().count() == 1
                        ? aliases.values().stream().findFirst().orElse(null)
                        : null;
            }

            if (!hasText(table)
                    || !simpleName(table).toUpperCase(Locale.ROOT).equals(simpleTarget)
                    || !hasText(column)) {
                continue;
            }

            priorities.merge(
                    normalizeColumn(column),
                    operatorPriority(operator),
                    Math::min
            );
        }
        return priorities;
    }

    private static int operatorPriority(String operator) {
        String normalized = operator == null ? "" : operator.trim().toUpperCase(Locale.ROOT);
        if ("=".equals(normalized)) return 0;
        if (normalized.startsWith("IN")) return 1;
        if (">".equals(normalized) || ">=".equals(normalized)
                || "<".equals(normalized) || "<=".equals(normalized)) return 2;
        if (normalized.startsWith("LIKE")) return 3;
        return 4;
    }

    private static Map<String, String> tableAliases(String sqlText) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = TABLE_PATTERN.matcher(sqlText == null ? "" : sqlText);
        while (matcher.find()) {
            String table = clean(matcher.group(1));
            String alias = clean(matcher.group(2));
            if (!hasText(table) || isKeyword(table)) continue;

            aliases.put(simpleName(table).toLowerCase(Locale.ROOT), table);
            if (hasText(alias) && !isKeyword(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), table);
            }
        }
        return aliases;
    }

    private static Long tableRows(AwrDtos.SqlTuningRequest input, String tableName) {
        if (input == null || !hasText(input.schemaDdl())) return null;

        Matcher matcher = TABLE_STATS_PATTERN.matcher(input.schemaDdl());
        while (matcher.find()) {
            String ownerPrefix = matcher.group(1) == null ? "" : matcher.group(1);
            String table = clean(matcher.group(2));
            String full = clean(ownerPrefix + table);
            if (sameTable(full, tableName)) {
                return parseLong(matcher.group(3));
            }
        }
        return null;
    }

    private static Map<String, Long> columnDistinctCounts(AwrDtos.SqlTuningRequest input) {
        if (input == null || !hasText(input.schemaDdl())) return Map.of();

        Map<String, Long> counts = new LinkedHashMap<>();
        Matcher matcher = COLUMN_STATS_PATTERN.matcher(input.schemaDdl());
        while (matcher.find()) {
            String owner = clean(matcher.group(1));
            String table = clean(matcher.group(2));
            String column = clean(matcher.group(3));
            Long distinct = parseLong(matcher.group(4));
            if (distinct == null) continue;

            counts.put(statsKey(owner + "." + table, column), distinct);
            counts.put(statsKey(table, column), distinct);
        }
        return counts;
    }

    private static Double selectivityRatio(
            String tableName,
            String column,
            Long numRows,
            Map<String, Long> distinctCounts
    ) {
        if (numRows == null || numRows <= 0) return null;

        Long distinct = distinctCounts.get(statsKey(tableName, column));
        if (distinct == null) {
            distinct = distinctCounts.get(statsKey(simpleName(tableName), column));
        }
        if (distinct == null) return null;

        return Math.min(1.0, Math.max(
                0.0,
                distinct.doubleValue() / numRows.doubleValue()
        ));
    }

    private static boolean onlyLowSelectivityColumns(
            String tableName,
            List<String> columns,
            AwrDtos.SqlTuningRequest input,
            Long numRows
    ) {
        if (numRows == null || numRows < 100_000 || columns.isEmpty()) {
            return false;
        }

        Map<String, Long> distinct = columnDistinctCounts(input);
        if (distinct.isEmpty()) return false;

        boolean allKnown = true;
        boolean allLow = true;
        for (String column : columns) {
            Double ratio = selectivityRatio(tableName, column, numRows, distinct);
            if (ratio == null) {
                allKnown = false;
                break;
            }
            if (ratio >= 0.01) {
                allLow = false;
            }
        }
        return allKnown && allLow;
    }

    private static boolean coveredByExistingIndex(
            String tableName,
            List<String> candidateColumns,
            AwrDtos.SqlTuningRequest input
    ) {
        if (input == null || !hasText(input.existingIndexes())) return false;

        List<String> normalizedCandidate = normalizeColumns(candidateColumns);
        Matcher matcher = INDEX_LINE_PATTERN.matcher(input.existingIndexes());
        while (matcher.find()) {
            String indexTable = clean(matcher.group(1));
            if (!sameTable(indexTable, tableName)) continue;

            List<String> existing = normalizeColumns(
                    List.of(matcher.group(3).split(","))
            );
            if (startsWith(existing, normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(List<String> existing, List<String> candidate) {
        if (candidate.isEmpty() || existing.size() < candidate.size()) return false;
        for (int i = 0; i < candidate.size(); i++) {
            if (!existing.get(i).equals(candidate.get(i))) return false;
        }
        return true;
    }

    private static boolean hasFullScan(
            AwrDtos.SqlTuningRequest input,
            String tableName
    ) {
        if (input == null || !hasText(input.executionPlan())) return false;

        String plan = input.executionPlan();
        String upper = plan.toUpperCase(Locale.ROOT);
        if (!upper.contains("TABLE ACCESS FULL")) return false;

        boolean structured = plan.contains("|") || plan.contains("\n");
        if (!structured) return true;

        String simple = simpleName(tableName).toUpperCase(Locale.ROOT);
        for (String line : plan.split("\\R")) {
            String normalized = line.toUpperCase(Locale.ROOT);
            if (normalized.contains("TABLE ACCESS")
                    && normalized.contains("FULL")
                    && normalized.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatibleNarrative(
            AwrDtos.SqlTuningResponse authoritative,
            String llmSummary
    ) {
        if (!hasText(llmSummary)) return false;

        String normalized = llmSummary.toUpperCase(Locale.ROOT);
        boolean noIndex = authoritative.indexRecommendations() == null
                || authoritative.indexRecommendations().isEmpty();

        if (noIndex && (normalized.contains("CREATE INDEX")
                || normalized.contains("인덱스 생성")
                || normalized.contains("인덱스를 생성"))) {
            return false;
        }

        if (!noIndex && (normalized.contains("인덱스 불필요")
                || normalized.contains("인덱스가 필요하지")
                || normalized.contains("NO INDEX"))) {
            return false;
        }

        return true;
    }

    private static String safeRewrite(
            AwrDtos.SqlTuningResponse authoritative,
            String candidate
    ) {
        if (!hasText(candidate)
                || authoritative.input() == null
                || !hasText(authoritative.input().sqlText())) {
            return null;
        }

        String original = normalizeSql(authoritative.input().sqlText());
        String rewritten = normalizeSql(candidate);

        if (!hasText(rewritten) || rewritten.contains(";")) return null;
        if (canonicalSql(original).equals(canonicalSql(rewritten))) return null;

        String originalType = statementType(original);
        String rewrittenType = statementType(rewritten);
        if (!hasText(originalType) || !originalType.equals(rewrittenType)) {
            return null;
        }

        if (Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE")
                .contains(rewrittenType)) {
            return null;
        }

        if (!bindVariables(original).equals(bindVariables(rewritten))) {
            return null;
        }

        if (!containsAggregateArithmetic(original) && containsAggregateArithmetic(rewritten)) {
            return null;
        }

        if (Set.of("INSERT", "UPDATE", "DELETE", "MERGE").contains(originalType)) {
            String originalTarget = dmlTarget(original);
            String rewrittenTarget = dmlTarget(rewritten);
            if (!hasText(originalTarget)
                    || !hasText(rewrittenTarget)
                    || !originalTarget.equalsIgnoreCase(rewrittenTarget)) {
                return null;
            }
        }

        return rewritten;
    }

    private static boolean containsAggregateArithmetic(String sql) {
        return hasText(sql)
                && (AGGREGATE_POWER_PATTERN.matcher(sql).find()
                || AGGREGATE_BINARY_MATH_PATTERN.matcher(sql).find());
    }

    static String redundantCountSelfJoinRewrite(String sql) {
        String normalized = canonicalSql(normalizeSql(sql));
        Matcher matcher = SIMPLE_COUNT_SELF_JOIN_PATTERN.matcher(normalized);
        if (!matcher.matches() || !matcher.group(1).equalsIgnoreCase(matcher.group(3))) {
            return null;
        }

        String table = matcher.group(1);
        String leftAlias = matcher.group(2);
        String rightAlias = matcher.group(4);
        String[] conditions = matcher.group(5).split("(?i)\\s+AND\\s+");
        if (conditions.length != 2) {
            return null;
        }

        Pattern joinPattern = Pattern.compile(
                "(?i)^(?:" + Pattern.quote(leftAlias) + "\\.([A-Z][A-Z0-9_$#]*)\\s*=\\s*"
                        + Pattern.quote(rightAlias) + "\\.\\1|"
                        + Pattern.quote(rightAlias) + "\\.([A-Z][A-Z0-9_$#]*)\\s*=\\s*"
                        + Pattern.quote(leftAlias) + "\\.\\2)$"
        );
        String joinColumn = null;
        String filterCondition = null;
        for (String condition : conditions) {
            String trimmed = condition.trim();
            Matcher joinMatcher = joinPattern.matcher(trimmed);
            if (joinMatcher.matches()) {
                joinColumn = joinMatcher.group(1) != null
                        ? joinMatcher.group(1)
                        : joinMatcher.group(2);
            } else {
                filterCondition = trimmed;
            }
        }
        if (joinColumn == null || filterCondition == null) {
            return null;
        }

        Pattern filterPattern = Pattern.compile(
                "(?i)^(?:" + Pattern.quote(leftAlias) + "|" + Pattern.quote(rightAlias) + ")\\."
                        + Pattern.quote(joinColumn)
                        + "\\s*=\\s*('(?:''|[^'])*'|[-+]?\\d+(?:\\.\\d+)?)$"
        );
        Matcher filterMatcher = filterPattern.matcher(filterCondition);
        if (!filterMatcher.matches()) {
            return null;
        }

        return "SELECT COUNT(*)\nFROM " + table + "\nWHERE " + joinColumn + " = " + filterMatcher.group(1);
    }

    private static String canonicalSql(String sql) {
        StringBuilder result = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean pendingSpace = false;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    pendingSpace = true;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    pendingSpace = true;
                    index++;
                }
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == '-' && next == '-') {
                lineComment = true;
                pendingSpace = true;
                index++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == '/' && next == '*') {
                blockComment = true;
                pendingSpace = true;
                index++;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && Character.isWhitespace(current)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && current == ';') {
                continue;
            }
            if (pendingSpace) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                pendingSpace = false;
            }

            result.append(singleQuoted || doubleQuoted
                    ? current
                    : Character.toUpperCase(current));

            if (!doubleQuoted && current == '\'') {
                if (singleQuoted && next == '\'') {
                    result.append(next);
                    index++;
                } else {
                    singleQuoted = !singleQuoted;
                }
            } else if (!singleQuoted && current == '"') {
                if (doubleQuoted && next == '"') {
                    result.append(next);
                    index++;
                } else {
                    doubleQuoted = !doubleQuoted;
                }
            }
        }
        return result.toString().trim();
    }

    private static String normalizeSql(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:sql)?", "")
                    .replaceFirst("```$", "")
                    .trim();
        }
        return trimmed.replaceFirst(";\\s*$", "").trim();
    }

    private static String statementType(String sql) {
        if (!hasText(sql)) return null;

        String normalized = sql
                .replaceFirst("(?is)^\\s*(?:/\\*.*?\\*/\\s*)+", "")
                .stripLeading()
                .toUpperCase(Locale.ROOT);

        for (String type : List.of(
                "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE",
                "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE"
        )) {
            if (normalized.startsWith(type + " ") || normalized.equals(type)) {
                return type;
            }
        }
        return null;
    }

    private static Set<String> bindVariables(String sql) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = BIND_PATTERN.matcher(sql == null ? "" : sql);
        while (matcher.find()) {
            values.add(matcher.group(1).toUpperCase(Locale.ROOT));
        }
        return values;
    }

    private static String dmlTarget(String sql) {
        Matcher matcher = DML_TARGET_PATTERN.matcher(sql == null ? "" : sql);
        return matcher.find()
                ? clean(matcher.group(1))
                : null;
    }

    private static List<String> rebuildBuildSteps(
            List<String> original,
            String ddl,
            String indexName
    ) {
        if (original == null || original.isEmpty()) {
            return List.of();
        }

        boolean usedNoLogging = original.stream()
                .anyMatch(step -> step != null
                        && step.toUpperCase(Locale.ROOT).contains("NOLOGGING"));
        if (!usedNoLogging) {
            return original;
        }

        return List.of(
                ddl.replaceFirst(";\\s*$", " NOLOGGING;"),
                "ALTER INDEX " + indexName + " LOGGING;"
        );
    }

    private static String indexName(String tableName, List<String> columns) {
        String base = "idx_" + simpleName(tableName)
                + "_" + String.join("_", columns);
        String normalized = base
                .replaceAll("[^A-Za-z0-9_]", "_")
                .toLowerCase(Locale.ROOT);
        return normalized.length() <= 30
                ? normalized
                : normalized.substring(0, 30);
    }

    private static boolean containsOracleDictionary(String sql) {
        if (!hasText(sql)) return false;
        return tableAliases(sql).values().stream()
                .anyMatch(SqlTuningAccuracyGuard::isOracleDictionaryObject);
    }

    private static boolean isOracleDictionaryObject(String tableName) {
        if (!hasText(tableName)) return false;

        String simple = simpleName(tableName).toUpperCase(Locale.ROOT);
        return simple.startsWith("ALL_")
                || simple.startsWith("DBA_")
                || simple.startsWith("USER_")
                || simple.startsWith("V$")
                || simple.startsWith("GV$");
    }

    private static boolean isInsert(String sql) {
        return hasText(sql)
                && sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("INSERT");
    }

    private static boolean sameTable(String left, String right) {
        return clean(left).equalsIgnoreCase(clean(right))
                || simpleName(left).equalsIgnoreCase(simpleName(right));
    }

    private static String statsKey(String table, String column) {
        return clean(table).toUpperCase(Locale.ROOT)
                + "|"
                + normalizeColumn(column);
    }

    private static List<String> normalizeColumns(List<String> columns) {
        if (columns == null) return List.of();

        List<String> result = new ArrayList<>();
        for (String column : columns) {
            if (column == null) continue;
            String normalized = normalizeColumn(column);
            if (hasText(normalized)) result.add(normalized);
        }
        return result;
    }

    private static String normalizeColumn(String column) {
        return simpleName(column).toUpperCase(Locale.ROOT);
    }

    private static String simpleName(String value) {
        String clean = clean(value);
        int dot = clean.lastIndexOf('.');
        return dot >= 0 ? clean.substring(dot + 1) : clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("\"", "").trim();
    }

    private static boolean isKeyword(String value) {
        return value != null
                && SQL_KEYWORDS.contains(value.toLowerCase(Locale.ROOT));
    }

    private static Long parseLong(String value) {
        if (!hasText(value)) return null;

        String digits = value.replaceAll("[^0-9-]", "");
        if (!hasText(digits) || "-".equals(digits)) return null;

        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String appendSentence(String original, String sentence) {
        if (!hasText(original)) return sentence + ".";
        String trimmed = original.trim();
        return (trimmed.endsWith(".") ? trimmed : trimmed + ".")
                + " "
                + sentence
                + ".";
    }

    private static List<String> merge(List<String> first, List<String> second) {
        Set<String> values = new LinkedHashSet<>();
        if (first != null) values.addAll(first);
        if (second != null) values.addAll(second);
        return new ArrayList<>(values);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

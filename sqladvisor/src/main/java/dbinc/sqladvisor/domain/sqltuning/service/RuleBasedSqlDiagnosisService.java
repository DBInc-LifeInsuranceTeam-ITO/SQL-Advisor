package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.DirectSqlMetricDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.ExecutionPlanDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlDiagnosisDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.TableMetadataDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RuleBasedSqlDiagnosisService {

    private static final long LARGE_ROW_THRESHOLD = 1_000_000L;
    private static final long HIGH_BUFFER_GETS_THRESHOLD = 10_000L;
    private static final long HIGH_DISK_READS_THRESHOLD = 1_000L;
    private static final long STALE_STATISTICS_DAYS = 30L;
    private static final double CARDINALITY_MISMATCH_RATIO = 10.0;

    private final DirectSqlMetricService directSqlMetricService;
    private final StructuredExecutionPlanService structuredExecutionPlanService;
    private final TableMetadataService tableMetadataService;

    public SqlDiagnosisDtos.SqlDiagnosisResponse diagnose(
            long connectionId,
            String requestedSqlId,
            Integer childNumber
    ) {
        String sqlId = normalizeSqlId(requestedSqlId);
        DirectSqlMetricDtos.DirectSqlMetricListResponse metricList = directSqlMetricService.topSql(
                connectionId, 100, "TOTAL_ELAPSED_TIME", false
        );
        DirectSqlMetricDtos.DirectSqlMetricResponse metric = metricList.rows().stream()
                .filter(row -> sqlId.equalsIgnoreCase(row.sqlId()))
                .filter(row -> childNumber == null || childNumber.equals(row.childNumber()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "진단 대상 SQL_ID를 Top SQL 상세 지표에서 찾지 못했습니다: " + sqlId
                ));

        ExecutionPlanDtos.StructuredExecutionPlanResponse plan = structuredExecutionPlanService.collect(
                connectionId, sqlId, childNumber == null ? metric.childNumber() : childNumber
        );
        TableMetadataDtos.TableMetadataListResponse tableMetadata = tableMetadataService.collect(connectionId, plan);

        List<SqlDiagnosisDtos.FindingResponse> findings = new ArrayList<>();
        int score = 0;

        List<ExecutionPlanDtos.ExecutionPlanNodeResponse> fullScanNodes = plan.nodes().stream()
                .filter(this::isFullTableScan)
                .toList();
        if (!fullScanNodes.isEmpty()) {
            score += 40;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("tables", fullScanNodes.stream().map(this::qualifiedObjectName).distinct().toList());
            evidence.put("operations", fullScanNodes.stream().map(node -> node.operation() + " " + node.options()).toList());
            evidence.put("estimatedRows", maximumEstimatedRows(fullScanNodes));
            findings.add(new SqlDiagnosisDtos.FindingResponse(
                    "FULL_TABLE_SCAN", "HIGH", "대용량 테이블 전체 스캔",
                    "실행계획에서 TABLE ACCESS FULL이 확인되었습니다.", evidence,
                    List.of(
                            "조회 범위를 줄일 수 있는 WHERE 조건이 있는지 확인합니다.",
                            "조건절과 기존 인덱스 구성을 비교한 뒤 인덱스 후보를 검토합니다.",
                            "전체 데이터 조회가 의도된 경우 페이징 또는 배치 분할을 적용합니다."
                    )
            ));
        }

        if (value(metric.bufferGets()) >= HIGH_BUFFER_GETS_THRESHOLD) {
            score += 20;
            findings.add(finding("HIGH_BUFFER_GETS", "HIGH", "논리 읽기량 과다",
                    "SQL 실행 과정에서 많은 데이터 블록을 메모리에서 읽었습니다.",
                    evidence("bufferGets", metric.bufferGets(), "averageBufferGets", metric.averageBufferGets(),
                            "threshold", HIGH_BUFFER_GETS_THRESHOLD),
                    List.of("불필요한 컬럼 조회를 제거하고 SELECT * 사용을 피합니다.", "접근 경로와 조인 순서를 실행계획에서 확인합니다.")));
        }

        if (value(metric.diskReads()) >= HIGH_DISK_READS_THRESHOLD) {
            score += 20;
            findings.add(finding("HIGH_DISK_READS", "HIGH", "물리 읽기량 과다",
                    "디스크에서 직접 읽은 블록 수가 기준값보다 높습니다.",
                    evidence("diskReads", metric.diskReads(), "averageDiskReads", metric.averageDiskReads(),
                            "threshold", HIGH_DISK_READS_THRESHOLD),
                    List.of("Full Scan 발생 원인을 확인하고 선택도가 높은 조건에 인덱스를 검토합니다.",
                            "테이블 및 인덱스 통계정보가 최신인지 확인합니다.")));
        }

        if (value(metric.rowsProcessed()) >= LARGE_ROW_THRESHOLD) {
            score += 20;
            findings.add(finding("LARGE_ROW_PROCESSING", "HIGH", "대량 행 처리",
                    "한 번의 SQL이 매우 많은 행을 처리했습니다.",
                    evidence("rowsProcessed", metric.rowsProcessed(), "threshold", LARGE_ROW_THRESHOLD),
                    List.of("필요한 데이터 범위만 조회하도록 조건을 추가합니다.",
                            "화면 조회라면 FETCH FIRST, OFFSET 또는 키셋 페이징을 적용합니다.")));
        }

        List<TableMetadataDtos.TableMetadataResponse> staleTables = tableMetadata.tables().stream()
                .filter(this::isStatisticsStale)
                .toList();
        if (!staleTables.isEmpty()) {
            score += 15;
            Map<String, Object> staleEvidence = new LinkedHashMap<>();
            staleEvidence.put("thresholdDays", STALE_STATISTICS_DAYS);
            staleEvidence.put("tables", staleTables.stream().map(table -> Map.of(
                    "table", table.owner() + "." + table.tableName(),
                    "lastAnalyzed", table.lastAnalyzed() == null ? "NOT_ANALYZED" : table.lastAnalyzed().toString(),
                    "numRows", table.numRows() == null ? 0L : table.numRows()
            )).toList());
            findings.add(finding("STALE_STATISTICS", "MEDIUM", "통계정보 미수집 또는 노후",
                    "실행계획 대상 테이블의 통계정보가 없거나 기준 기간보다 오래되었습니다.", staleEvidence,
                    List.of("DBMS_STATS로 테이블 및 인덱스 통계를 갱신한 뒤 실행계획을 다시 확인합니다.",
                            "운영 반영 전 통계 수집 시간과 샘플 비율을 검토합니다.")));
        }

        List<TableMetadataDtos.TableMetadataResponse> noUsableIndexTables = tableMetadata.tables().stream()
                .filter(table -> table.indexes().stream().noneMatch(this::isUsableIndex))
                .toList();
        if (!fullScanNodes.isEmpty() && !noUsableIndexTables.isEmpty()) {
            score += 15;
            findings.add(finding("NO_USABLE_INDEX", "MEDIUM", "사용 가능한 인덱스 없음",
                    "Full Scan 대상 테이블에서 VALID 및 VISIBLE 상태의 인덱스를 확인하지 못했습니다.",
                    evidence("tables", noUsableIndexTables.stream()
                            .map(table -> table.owner() + "." + table.tableName()).toList()),
                    List.of("WHERE/JOIN 조건을 기준으로 선택도 높은 선두 컬럼의 인덱스 후보를 검토합니다.",
                            "전체 조회 SQL이라면 인덱스 생성보다 조회 범위 제한이 우선입니다.")));
        }

        Long estimatedRows = maximumEstimatedRows(fullScanNodes);
        if (metric.executions() != null && metric.executions() == 1L
                && estimatedRows != null && value(metric.rowsProcessed()) > 0) {
            double ratio = Math.max(
                    estimatedRows.doubleValue() / metric.rowsProcessed().doubleValue(),
                    metric.rowsProcessed().doubleValue() / estimatedRows.doubleValue()
            );
            if (ratio >= CARDINALITY_MISMATCH_RATIO) {
                score += 15;
                findings.add(finding("CARDINALITY_MISMATCH", "MEDIUM", "예상 행 수와 처리 행 수 불일치",
                        "옵티마이저 예상 행 수와 SQL 누적 처리 행 수 차이가 큽니다.",
                        evidence("estimatedRows", estimatedRows, "rowsProcessed", metric.rowsProcessed(),
                                "ratio", ratio, "thresholdRatio", CARDINALITY_MISMATCH_RATIO),
                        List.of("컬럼 통계와 히스토그램 상태를 확인합니다.",
                                "조건절 컬럼 간 상관관계가 크면 확장 통계 생성을 검토합니다.")));
            }
        }

        score = Math.min(score, 100);
        String severity = severity(score);
        List<String> warnings = new ArrayList<>();
        warnings.addAll(metricList.warnings());
        warnings.addAll(plan.warnings());
        warnings.addAll(tableMetadata.warnings());
        if (plan.nodes().stream().allMatch(node -> node.actualRows() == null && node.lastActualRows() == null)) {
            warnings.add("실행계획 실제 행 통계가 없어 누적 SQL 지표와 예상 실행계획을 기준으로 진단했습니다.");
        }

        return new SqlDiagnosisDtos.SqlDiagnosisResponse(
                connectionId, sqlId, severity, score, summary(severity, findings),
                metric, plan, tableMetadata, List.copyOf(findings), List.copyOf(warnings)
        );
    }

    private SqlDiagnosisDtos.FindingResponse finding(String code, String severity, String title,
                                                     String description, Map<String, Object> evidence,
                                                     List<String> recommendations) {
        return new SqlDiagnosisDtos.FindingResponse(code, severity, title, description, evidence, recommendations);
    }

    private Map<String, Object> evidence(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private boolean isStatisticsStale(TableMetadataDtos.TableMetadataResponse table) {
        return table.lastAnalyzed() == null
                || ChronoUnit.DAYS.between(table.lastAnalyzed(), LocalDateTime.now()) > STALE_STATISTICS_DAYS;
    }

    private boolean isUsableIndex(TableMetadataDtos.IndexMetadataResponse index) {
        boolean valid = index.status() == null || "VALID".equalsIgnoreCase(index.status());
        boolean visible = index.visibility() == null || "VISIBLE".equalsIgnoreCase(index.visibility());
        return valid && visible;
    }

    private Long maximumEstimatedRows(List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes) {
        return nodes.stream().map(ExecutionPlanDtos.ExecutionPlanNodeResponse::cardinality)
                .filter(value -> value != null).max(Long::compareTo).orElse(null);
    }

    private boolean isFullTableScan(ExecutionPlanDtos.ExecutionPlanNodeResponse node) {
        return "TABLE ACCESS".equalsIgnoreCase(nullToEmpty(node.operation()))
                && "FULL".equalsIgnoreCase(nullToEmpty(node.options()));
    }

    private String qualifiedObjectName(ExecutionPlanDtos.ExecutionPlanNodeResponse node) {
        return node.objectOwner() == null || node.objectOwner().isBlank()
                ? node.objectName() : node.objectOwner() + "." + node.objectName();
    }

    private String severity(int score) {
        if (score >= 70) return "HIGH";
        if (score >= 40) return "MEDIUM";
        return "LOW";
    }

    private String summary(String severity, List<SqlDiagnosisDtos.FindingResponse> findings) {
        if (findings.isEmpty()) return "현재 기준에서 명확한 고위험 패턴이 발견되지 않았습니다.";
        return String.format(Locale.ROOT, "%s 위험도이며 %d개의 성능 이슈가 발견되었습니다. 우선순위는 %s입니다.",
                severity, findings.size(), findings.get(0).title());
    }

    private String normalizeSqlId(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SQL_ID는 필수입니다.");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-z]{13}")) throw new IllegalArgumentException("SQL_ID 형식이 올바르지 않습니다: " + value);
        return normalized;
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
}

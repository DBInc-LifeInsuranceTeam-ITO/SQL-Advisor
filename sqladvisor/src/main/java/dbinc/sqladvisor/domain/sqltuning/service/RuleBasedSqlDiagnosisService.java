package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.DirectSqlMetricDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.ExecutionPlanDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlDiagnosisDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    private final DirectSqlMetricService directSqlMetricService;
    private final StructuredExecutionPlanService structuredExecutionPlanService;

    public SqlDiagnosisDtos.SqlDiagnosisResponse diagnose(
            long connectionId,
            String requestedSqlId,
            Integer childNumber
    ) {
        String sqlId = normalizeSqlId(requestedSqlId);
        DirectSqlMetricDtos.DirectSqlMetricListResponse metricList = directSqlMetricService.topSql(
                connectionId,
                100,
                "TOTAL_ELAPSED_TIME",
                false
        );
        DirectSqlMetricDtos.DirectSqlMetricResponse metric = metricList.rows().stream()
                .filter(row -> sqlId.equalsIgnoreCase(row.sqlId()))
                .filter(row -> childNumber == null || childNumber.equals(row.childNumber()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "진단 대상 SQL_ID를 Top SQL 상세 지표에서 찾지 못했습니다: " + sqlId
                ));

        ExecutionPlanDtos.StructuredExecutionPlanResponse plan = structuredExecutionPlanService.collect(
                connectionId,
                sqlId,
                childNumber == null ? metric.childNumber() : childNumber
        );

        List<SqlDiagnosisDtos.FindingResponse> findings = new ArrayList<>();
        int score = 0;

        List<ExecutionPlanDtos.ExecutionPlanNodeResponse> fullScanNodes = plan.nodes().stream()
                .filter(this::isFullTableScan)
                .toList();
        if (!fullScanNodes.isEmpty()) {
            score += 40;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("tables", fullScanNodes.stream()
                    .map(this::qualifiedObjectName)
                    .distinct()
                    .toList());
            evidence.put("operations", fullScanNodes.stream()
                    .map(node -> node.operation() + " " + node.options())
                    .toList());
            evidence.put("estimatedRows", fullScanNodes.stream()
                    .map(ExecutionPlanDtos.ExecutionPlanNodeResponse::cardinality)
                    .filter(value -> value != null)
                    .max(Long::compareTo)
                    .orElse(null));
            findings.add(new SqlDiagnosisDtos.FindingResponse(
                    "FULL_TABLE_SCAN",
                    "HIGH",
                    "대용량 테이블 전체 스캔",
                    "실행계획에서 TABLE ACCESS FULL이 확인되었습니다.",
                    evidence,
                    List.of(
                            "조회 범위를 줄일 수 있는 WHERE 조건이 있는지 확인합니다.",
                            "조건절과 기존 인덱스 구성을 비교한 뒤 인덱스 후보를 검토합니다.",
                            "전체 데이터 조회가 의도된 경우 페이징 또는 배치 분할을 적용합니다."
                    )
            ));
        }

        if (value(metric.bufferGets()) >= HIGH_BUFFER_GETS_THRESHOLD) {
            score += 20;
            findings.add(new SqlDiagnosisDtos.FindingResponse(
                    "HIGH_BUFFER_GETS",
                    "HIGH",
                    "논리 읽기량 과다",
                    "SQL 실행 과정에서 많은 데이터 블록을 메모리에서 읽었습니다.",
                    Map.of(
                            "bufferGets", metric.bufferGets(),
                            "averageBufferGets", metric.averageBufferGets(),
                            "threshold", HIGH_BUFFER_GETS_THRESHOLD
                    ),
                    List.of(
                            "불필요한 컬럼 조회를 제거하고 SELECT * 사용을 피합니다.",
                            "접근 경로와 조인 순서를 실행계획에서 확인합니다."
                    )
            ));
        }

        if (value(metric.diskReads()) >= HIGH_DISK_READS_THRESHOLD) {
            score += 20;
            findings.add(new SqlDiagnosisDtos.FindingResponse(
                    "HIGH_DISK_READS",
                    "HIGH",
                    "물리 읽기량 과다",
                    "디스크에서 직접 읽은 블록 수가 기준값보다 높습니다.",
                    Map.of(
                            "diskReads", metric.diskReads(),
                            "averageDiskReads", metric.averageDiskReads(),
                            "threshold", HIGH_DISK_READS_THRESHOLD
                    ),
                    List.of(
                            "Full Scan 발생 원인을 확인하고 선택도가 높은 조건에 인덱스를 검토합니다.",
                            "테이블 및 인덱스 통계정보가 최신인지 확인합니다."
                    )
            ));
        }

        if (value(metric.rowsProcessed()) >= LARGE_ROW_THRESHOLD) {
            score += 20;
            findings.add(new SqlDiagnosisDtos.FindingResponse(
                    "LARGE_ROW_PROCESSING",
                    "HIGH",
                    "대량 행 처리",
                    "한 번의 SQL이 매우 많은 행을 처리했습니다.",
                    Map.of(
                            "rowsProcessed", metric.rowsProcessed(),
                            "threshold", LARGE_ROW_THRESHOLD
                    ),
                    List.of(
                            "필요한 데이터 범위만 조회하도록 조건을 추가합니다.",
                            "화면 조회라면 FETCH FIRST, OFFSET 또는 키셋 페이징을 적용합니다."
                    )
            ));
        }

        score = Math.min(score, 100);
        String severity = severity(score);
        List<String> warnings = new ArrayList<>();
        warnings.addAll(metricList.warnings());
        warnings.addAll(plan.warnings());
        if (plan.nodes().stream().allMatch(node -> node.actualRows() == null && node.lastActualRows() == null)) {
            warnings.add("실행계획 실제 행 통계가 없어 누적 SQL 지표와 예상 실행계획을 기준으로 진단했습니다.");
        }

        return new SqlDiagnosisDtos.SqlDiagnosisResponse(
                connectionId,
                sqlId,
                severity,
                score,
                summary(severity, findings),
                metric,
                plan,
                List.copyOf(findings),
                List.copyOf(warnings)
        );
    }

    private boolean isFullTableScan(ExecutionPlanDtos.ExecutionPlanNodeResponse node) {
        return "TABLE ACCESS".equalsIgnoreCase(nullToEmpty(node.operation()))
                && "FULL".equalsIgnoreCase(nullToEmpty(node.options()));
    }

    private String qualifiedObjectName(ExecutionPlanDtos.ExecutionPlanNodeResponse node) {
        if (node.objectOwner() == null || node.objectOwner().isBlank()) {
            return node.objectName();
        }
        return node.objectOwner() + "." + node.objectName();
    }

    private String severity(int score) {
        if (score >= 70) {
            return "HIGH";
        }
        if (score >= 40) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String summary(String severity, List<SqlDiagnosisDtos.FindingResponse> findings) {
        if (findings.isEmpty()) {
            return "현재 기준에서 명확한 고위험 패턴이 발견되지 않았습니다.";
        }
        return String.format(
                Locale.ROOT,
                "%s 위험도이며 %d개의 성능 이슈가 발견되었습니다. 우선순위는 %s입니다.",
                severity,
                findings.size(),
                findings.getFirst().title()
        );
    }

    private String normalizeSqlId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SQL_ID는 필수입니다.");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-z]{13}")) {
            throw new IllegalArgumentException("SQL_ID 형식이 올바르지 않습니다: " + value);
        }
        return normalized;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}

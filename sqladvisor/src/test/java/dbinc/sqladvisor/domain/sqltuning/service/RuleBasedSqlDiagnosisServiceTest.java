package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.DirectSqlMetricDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.ExecutionPlanDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlDiagnosisDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.TableMetadataDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleBasedSqlDiagnosisServiceTest {

    @Test
    void diagnosesSlowRedundantCountSelfJoinBeforeAiTuning() {
        DirectSqlMetricService metricService = mock(DirectSqlMetricService.class);
        StructuredExecutionPlanService planService = mock(StructuredExecutionPlanService.class);
        TableMetadataService metadataService = mock(TableMetadataService.class);
        RuleBasedSqlDiagnosisService service = new RuleBasedSqlDiagnosisService(
                metricService,
                planService,
                metadataService
        );
        String sqlId = "9d149583pqxzh";
        String sql = """
                SELECT COUNT(*)
                FROM TEST.AX_ORDERS a, TEST.AX_ORDERS b
                WHERE a.status = b.status
                -- AND a.amount + b.amount > 0
                AND a.status = 'CANCEL'
                """;
        DirectSqlMetricDtos.DirectSqlMetricResponse metric = new DirectSqlMetricDtos.DirectSqlMetricResponse(
                sqlId, 1, 3_421_304_172L, 0, "TEST", null, null, null,
                1L, 695.44, 695.44, 591.435, 591.435,
                3_267L, 3_267.0, 0L, 0.0, 0L, null, null, sql
        );
        ExecutionPlanDtos.StructuredExecutionPlanResponse plan =
                new ExecutionPlanDtos.StructuredExecutionPlanResponse(
                        1L, sqlId, 1, 0, 3_421_304_172L, "test", List.of(), List.of()
                );

        when(metricService.topSql(1L, 100, "TOTAL_ELAPSED_TIME", false))
                .thenReturn(new DirectSqlMetricDtos.DirectSqlMetricListResponse("test", List.of(metric), List.of()));
        when(planService.collect(1L, sqlId, 1, 0)).thenReturn(plan);
        when(metadataService.collect(1L, plan))
                .thenReturn(new TableMetadataDtos.TableMetadataListResponse(List.of(), List.of()));

        SqlDiagnosisDtos.SqlDiagnosisResponse response = service.diagnose(1L, sqlId, 1, 0);

        assertThat(response.summary()).contains("N×N건으로 증가");
        assertThat(response.findings()).extracting(SqlDiagnosisDtos.FindingResponse::code)
                .contains("REDUNDANT_SELF_JOIN", "SLOW_AVERAGE_ELAPSED_TIME");
    }
}

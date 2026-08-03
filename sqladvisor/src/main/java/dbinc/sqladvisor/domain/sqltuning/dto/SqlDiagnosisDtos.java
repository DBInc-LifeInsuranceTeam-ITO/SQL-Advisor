package dbinc.sqladvisor.domain.sqltuning.dto;

import java.util.List;
import java.util.Map;

public final class SqlDiagnosisDtos {

    private SqlDiagnosisDtos() {
    }

    public record FindingResponse(
            String code,
            String severity,
            String title,
            String description,
            Map<String, Object> evidence,
            List<String> recommendations
    ) {
    }

    public record SqlDiagnosisResponse(
            Long connectionId,
            String sqlId,
            String severity,
            int score,
            String summary,
            DirectSqlMetricDtos.DirectSqlMetricResponse metric,
            ExecutionPlanDtos.StructuredExecutionPlanResponse executionPlan,
            TableMetadataDtos.TableMetadataListResponse tableMetadata,
            List<FindingResponse> findings,
            List<String> warnings
    ) {
    }
}

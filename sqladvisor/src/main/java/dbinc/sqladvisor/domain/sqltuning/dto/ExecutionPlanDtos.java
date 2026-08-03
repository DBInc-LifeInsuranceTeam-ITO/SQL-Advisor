package dbinc.sqladvisor.domain.sqltuning.dto;

import java.util.List;

public final class ExecutionPlanDtos {

    private ExecutionPlanDtos() {
    }

    public record ExecutionPlanNodeResponse(
            Integer id,
            Integer parentId,
            Integer depth,
            String operation,
            String options,
            String objectOwner,
            String objectName,
            String objectType,
            Long cost,
            Long cardinality,
            Long bytes,
            String accessPredicate,
            String filterPredicate,
            Long starts,
            Long actualRows,
            Long bufferGets,
            Long diskReads,
            Long lastStarts,
            Long lastActualRows,
            Long lastBufferGets,
            Long lastDiskReads
    ) {
    }

    public record StructuredExecutionPlanResponse(
            Long connectionId,
            String sqlId,
            Integer instanceId,
            Integer childNumber,
            Long planHashValue,
            String sourceView,
            List<ExecutionPlanNodeResponse> nodes,
            List<String> warnings
    ) {
    }
}

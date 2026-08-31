package dbinc.sqladvisor.domain.sqltuning.dto;

import java.util.List;

public final class DirectSqlMetricDtos {

    private DirectSqlMetricDtos() {
    }

    public record DirectSqlMetricResponse(
            String sqlId,
            Integer instanceId,
            Long planHashValue,
            Integer childNumber,
            String parsingSchemaName,
            String module,
            String action,
            String serviceName,
            Long executions,
            Double totalElapsedTimeSec,
            Double averageElapsedTimeSec,
            Double totalCpuTimeSec,
            Double averageCpuTimeSec,
            Long bufferGets,
            Double averageBufferGets,
            Long diskReads,
            Double averageDiskReads,
            Long rowsProcessed,
            String firstLoadTime,
            String lastActiveTime,
            String sqlText
    ) {
    }

    public record DirectSqlMetricListResponse(
            String sourceView,
            List<DirectSqlMetricResponse> rows,
            List<String> warnings
    ) {
    }
}

package dbinc.sqladvisor.domain.sqltuning.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class TableMetadataDtos {

    private TableMetadataDtos() {
    }

    public record IndexMetadataResponse(
            String owner,
            String indexName,
            String indexType,
            String uniqueness,
            String status,
            String visibility,
            Long numRows,
            Long distinctKeys,
            Long leafBlocks,
            Long clusteringFactor,
            LocalDateTime lastAnalyzed,
            List<String> columns,
            boolean usedInCurrentPlan
    ) {
    }

    public record TableMetadataResponse(
            String owner,
            String tableName,
            Long numRows,
            Long blocks,
            Long avgRowLength,
            Long sampleSize,
            LocalDateTime lastAnalyzed,
            String partitioned,
            String temporary,
            List<IndexMetadataResponse> indexes
    ) {
    }

    public record TableMetadataListResponse(
            List<TableMetadataResponse> tables,
            List<String> warnings
    ) {
    }
}

package dbinc.sqladvisor.domain.awr.service;

import java.util.Map;

public record AwrRagChunk(
        Long id,
        Long reportId,
        String sectionName,
        String sqlId,
        String chunkText,
        String chunkType,
        Map<String, Object> metricJson,
        Double similarity
) {
}

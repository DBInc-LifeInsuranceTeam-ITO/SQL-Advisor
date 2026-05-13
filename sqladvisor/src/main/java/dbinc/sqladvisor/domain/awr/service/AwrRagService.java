package dbinc.sqladvisor.domain.awr.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AwrRagService {

    private static final Pattern SQL_ID_PATTERN = Pattern.compile("(?i)\\b([0-9a-z]{13})\\b");

    private final AwrRepository repository;
    private final AwrAiClient aiClient;

    @Value("${awr.rag.top-k:8}")
    private int topK;

    public AwrIndexResult indexReport(long reportId,
                                      List<AwrDtos.SectionResponse> sections,
                                      List<AwrDtos.SqlMetricResponse> sqlMetrics,
                                      List<AwrDtos.WaitEventResponse> waitEvents) {
        List<AwrRagChunk> chunks = buildChunks(reportId, sections, sqlMetrics, waitEvents);
        List<AwrRagChunk> indexed = new ArrayList<>(chunks.size());
        AwrAiClient.EmbeddingResult embeddingMetadata = null;
        int embeddedCount = 0;

        for (AwrRagChunk chunk : chunks) {
            Map<String, Object> metricJson = new LinkedHashMap<>(chunk.metricJson());
            if (aiClient.isEmbeddingEnabled()) {
                Optional<AwrAiClient.EmbeddingResult> embedding = aiClient.embed(chunk.chunkText());
                if (embedding.isPresent()) {
                    AwrAiClient.EmbeddingResult result = embedding.get();
                    embeddingMetadata = result;
                    embeddedCount++;
                    metricJson.put("_embedding", repository.toVectorLiteral(result.vector()));
                }
            }
            indexed.add(new AwrRagChunk(
                    chunk.id(),
                    chunk.reportId(),
                    chunk.sectionName(),
                    chunk.sqlId(),
                    chunk.chunkText(),
                    chunk.chunkType(),
                    metricJson,
                    chunk.similarity()
            ));
        }

        repository.replaceChunks(reportId, indexed, embeddingMetadata);
        log.info("AWR RAG chunks indexed: reportId={}, chunks={}, embedding={}",
                reportId, chunks.size(), embeddingMetadata == null ? "disabled" : embeddingMetadata.provider() + "/" + embeddingMetadata.model());
        return new AwrIndexResult(chunks.size(), embeddedCount, embeddingMetadata == null ? null : embeddingMetadata.provider() + "/" + embeddingMetadata.model());
    }

    public List<AwrRagChunk> retrieve(long reportId, String question) {
        String sqlId = sqlIdFrom(question).orElse(null);
        List<AwrRagChunk> results = new ArrayList<>();

        if (sqlId != null) {
            results.addAll(repository.findChunksBySqlId(reportId, sqlId, topK));
        }

        if (aiClient.isEmbeddingEnabled() && question != null && !question.isBlank()) {
            aiClient.embed(question).ifPresent(embedding -> {
                String queryVector = repository.toVectorLiteral(embedding.vector());
                results.addAll(repository.findSimilarChunks(reportId, queryVector, sqlId, topK));
            });
        }

        if (results.size() < topK) {
            results.addAll(repository.findFallbackChunks(reportId, sqlId, topK));
        }

        return unique(results).stream().limit(topK).toList();
    }

    public String evidenceBlock(List<AwrRagChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "No RAG chunks were retrieved.";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (AwrRagChunk chunk : chunks) {
            builder.append("[")
                    .append(index++)
                    .append("] type=")
                    .append(chunk.chunkType());
            if (chunk.sectionName() != null) {
                builder.append(", section=").append(chunk.sectionName());
            }
            if (chunk.sqlId() != null) {
                builder.append(", sql_id=").append(chunk.sqlId());
            }
            if (chunk.similarity() != null) {
                builder.append(", similarity=").append(String.format(Locale.ROOT, "%.4f", chunk.similarity()));
            }
            builder.append("\n")
                    .append(chunk.chunkText())
                    .append("\n\n");
        }
        return builder.toString();
    }

    public List<String> citations(List<AwrRagChunk> chunks) {
        return unique(chunks).stream()
                .map(chunk -> {
                    StringBuilder builder = new StringBuilder();
                    builder.append(chunk.chunkType());
                    if (chunk.sectionName() != null) {
                        builder.append(" / ").append(chunk.sectionName());
                    }
                    if (chunk.sqlId() != null) {
                        builder.append(" / SQL_ID ").append(chunk.sqlId());
                    }
                    if (chunk.id() != null) {
                        builder.append(" / chunk ").append(chunk.id());
                    }
                    return builder.toString();
                })
                .limit(topK)
                .toList();
    }

    private List<AwrRagChunk> buildChunks(long reportId,
                                          List<AwrDtos.SectionResponse> sections,
                                          List<AwrDtos.SqlMetricResponse> sqlMetrics,
                                          List<AwrDtos.WaitEventResponse> waitEvents) {
        List<AwrRagChunk> chunks = new ArrayList<>();

        for (AwrDtos.SectionResponse section : sections) {
            Map<String, Object> metricJson = new LinkedHashMap<>(section.parsedJson());
            chunks.add(new AwrRagChunk(
                    null,
                    reportId,
                    section.sectionName(),
                    null,
                    section.rawText(),
                    String.valueOf(metricJson.getOrDefault("chunkType", "summary")),
                    metricJson,
                    null
            ));
        }

        for (AwrDtos.SqlMetricResponse metric : sqlMetrics) {
            Map<String, Object> metricJson = new LinkedHashMap<>();
            put(metricJson, "rank_no", metric.rankNo());
            put(metricJson, "elapsed_time_sec", metric.elapsedTimeSec());
            put(metricJson, "cpu_time_sec", metric.cpuTimeSec());
            put(metricJson, "buffer_gets", metric.bufferGets());
            put(metricJson, "disk_reads", metric.diskReads());
            put(metricJson, "executions", metric.executions());
            put(metricJson, "rows_processed", metric.rowsProcessed());
            put(metricJson, "plan_hash_value", metric.planHashValue());
            put(metricJson, "score", metric.score());

            chunks.add(new AwrRagChunk(
                    null,
                    reportId,
                    metric.sectionName(),
                    metric.sqlId(),
                    sqlMetricText(metric),
                    "sql_metric_row",
                    metricJson,
                    null
            ));
        }

        for (AwrDtos.WaitEventResponse waitEvent : waitEvents) {
            Map<String, Object> metricJson = new LinkedHashMap<>();
            put(metricJson, "wait_class", waitEvent.waitClass());
            put(metricJson, "total_wait_time_sec", waitEvent.totalWaitTimeSec());
            put(metricJson, "avg_wait_ms", waitEvent.avgWaitMs());
            put(metricJson, "db_time_percent", waitEvent.dbTimePercent());

            chunks.add(new AwrRagChunk(
                    null,
                    reportId,
                    "Top Wait Events",
                    null,
                    "Wait event: " + waitEvent.eventName()
                            + "\nwait_class=" + waitEvent.waitClass()
                            + "\ntotal_wait_time_sec=" + waitEvent.totalWaitTimeSec()
                            + "\navg_wait_ms=" + waitEvent.avgWaitMs()
                            + "\ndb_time_percent=" + waitEvent.dbTimePercent(),
                    "wait_event",
                    metricJson,
                    null
            ));
        }

        return chunks;
    }

    private String sqlMetricText(AwrDtos.SqlMetricResponse metric) {
        StringBuilder builder = new StringBuilder();
        builder.append("SQL_ID=").append(metric.sqlId()).append("\n")
                .append("section=").append(metric.sectionName()).append("\n")
                .append("rank_no=").append(metric.rankNo()).append("\n")
                .append("elapsed_time_sec=").append(metric.elapsedTimeSec()).append("\n")
                .append("cpu_time_sec=").append(metric.cpuTimeSec()).append("\n")
                .append("buffer_gets=").append(metric.bufferGets()).append("\n")
                .append("disk_reads=").append(metric.diskReads()).append("\n")
                .append("executions=").append(metric.executions()).append("\n")
                .append("rows_processed=").append(metric.rowsProcessed()).append("\n")
                .append("plan_hash_value=").append(metric.planHashValue()).append("\n")
                .append("interpretation_hint=").append(metric.interpretationHint());
        if (metric.sqlText() != null && !metric.sqlText().isBlank()) {
            builder.append("\nsql_text=\n").append(metric.sqlText());
        }
        return builder.toString();
    }

    private List<AwrRagChunk> unique(List<AwrRagChunk> chunks) {
        Set<String> seen = new LinkedHashSet<>();
        List<AwrRagChunk> unique = new ArrayList<>();
        for (AwrRagChunk chunk : chunks) {
            String key = (chunk.id() == null ? "new" : chunk.id()) + "|" + chunk.chunkType() + "|" + chunk.sqlId() + "|" + chunk.sectionName();
            if (seen.add(key)) {
                unique.add(chunk);
            }
        }
        return unique;
    }

    private Optional<String> sqlIdFrom(String question) {
        if (question == null) {
            return Optional.empty();
        }
        Matcher matcher = SQL_ID_PATTERN.matcher(question);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    private void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    public record AwrIndexResult(int chunkCount, int embeddedChunkCount, String embeddingModel) {
    }
}

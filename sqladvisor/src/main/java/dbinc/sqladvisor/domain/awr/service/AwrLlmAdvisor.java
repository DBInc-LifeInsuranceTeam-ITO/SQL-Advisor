package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AwrLlmAdvisor {

    private final AwrAiClient aiClient;
    private final AwrRagService ragService;
    private final ObjectMapper objectMapper;

    public Optional<AwrDtos.AnalysisResponse> analyze(
            Long reportId,
            String question,
            AwrDtos.AnalysisResponse localAnalysis,
            List<AwrRagChunk> ragChunks
    ) {
        if (!aiClient.isExternalLlmEnabled()) {
            return Optional.empty();
        }

        String systemPrompt = """
                You are SQLAdvisor, an Oracle AWR SQL tuning advisor.
                Use only the supplied AWR metrics and RAG evidence.
                If evidence is missing, say what is missing instead of guessing.
                Return JSON only with this schema:
                {
                  "summary": "string",
                  "top_findings": [
                    {
                      "priority": 1,
                      "sql_id": "string",
                      "symptom": "string",
                      "evidence": ["string"],
                      "likely_causes": ["string"],
                      "recommended_actions": ["string"],
                      "validation_steps": ["string"],
                      "risk": "string",
                      "confidence": "low|medium|high"
                    }
                  ],
                  "missing_inputs": ["string"]
                }
                """;
        String userPrompt = """
                User question:
                %s

                Local rule-based analysis:
                %s

                Retrieved AWR evidence:
                %s
                """.formatted(
                safeQuestion(question),
                toJson(localAnalysis),
                ragService.evidenceBlock(ragChunks)
        );

        return aiClient.complete(systemPrompt, userPrompt)
                .map(result -> toAnalysis(reportId, question, localAnalysis, ragChunks, result));
    }

    public Optional<AwrDtos.ChatResponse> chat(
            Long reportId,
            String question,
            AwrDtos.ChatResponse localChat,
            List<AwrRagChunk> ragChunks
    ) {
        if (!aiClient.isExternalLlmEnabled()) {
            return Optional.empty();
        }

        String systemPrompt = """
                You are SQLAdvisor, an Oracle AWR SQL tuning advisor.
                Answer in Korean.
                Use only the supplied AWR metrics and RAG evidence.
                Be concise but specific. Include SQL_IDs, numeric evidence, and validation steps when available.
                If the evidence is not enough, clearly say what is missing.
                Do not invent execution plans, DDL, bind values, or object statistics.
                """;
        String userPrompt = """
                User question:
                %s

                Local rule-based answer:
                %s

                Retrieved AWR evidence:
                %s
                """.formatted(
                safeQuestion(question),
                localChat.answer(),
                ragService.evidenceBlock(ragChunks)
        );

        return aiClient.complete(systemPrompt, userPrompt)
                .map(result -> new AwrDtos.ChatResponse(
                        reportId,
                        question,
                        result.content(),
                        merge(localChat.citations(), ragService.citations(ragChunks)),
                        localChat.evidenceSql(),
                        localChat.evidenceWaitEvents(),
                        localChat.confidence()
                ));
    }

    private AwrDtos.AnalysisResponse toAnalysis(
            Long reportId,
            String question,
            AwrDtos.AnalysisResponse localAnalysis,
            List<AwrRagChunk> ragChunks,
            AwrAiClient.LlmResult result
    ) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(result.content()));
            return new AwrDtos.AnalysisResponse(
                    localAnalysis.analysisId(),
                    reportId,
                    safeQuestion(question),
                    textOr(root, "summary", localAnalysis.summary()),
                    findings(root.path("top_findings"), localAnalysis.topFindings()),
                    stringList(root.path("missing_inputs"), localAnalysis.missingInputs()),
                    merge(localAnalysis.citations(), ragService.citations(ragChunks)),
                    result.providerModel(),
                    LocalDateTime.now()
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            return new AwrDtos.AnalysisResponse(
                    localAnalysis.analysisId(),
                    reportId,
                    safeQuestion(question),
                    result.content(),
                    localAnalysis.topFindings(),
                    localAnalysis.missingInputs(),
                    merge(localAnalysis.citations(), ragService.citations(ragChunks)),
                    result.providerModel(),
                    LocalDateTime.now()
            );
        }
    }

    private List<AwrDtos.FindingResponse> findings(JsonNode node, List<AwrDtos.FindingResponse> fallback) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return fallback;
        }
        List<AwrDtos.FindingResponse> findings = new ArrayList<>();
        for (JsonNode item : node) {
            findings.add(new AwrDtos.FindingResponse(
                    item.path("priority").asInt(findings.size() + 1),
                    textOr(item, "sql_id", null),
                    textOr(item, "symptom", "AWR evidence finding"),
                    stringList(item.path("evidence"), List.of()),
                    stringList(item.path("likely_causes"), List.of()),
                    stringList(item.path("recommended_actions"), List.of()),
                    stringList(item.path("validation_steps"), List.of()),
                    textOr(item, "risk", "운영 적용 전 검증이 필요합니다."),
                    textOr(item, "confidence", "medium")
            ));
        }
        return findings;
    }

    private String extractJsonObject(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> stringList(JsonNode node, List<String> fallback) {
        if (node == null || !node.isArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                values.add(item.asText());
            } else {
                values.add(item.toString());
            }
        }
        return values.isEmpty() ? fallback : values;
    }

    private List<String> merge(List<String> first, List<String> second) {
        Set<String> values = new LinkedHashSet<>();
        if (first != null) {
            values.addAll(first);
        }
        if (second != null) {
            values.addAll(second);
        }
        return new ArrayList<>(values);
    }

    private String safeQuestion(String question) {
        return question == null || question.isBlank() ? "이 AWR에서 제일 먼저 봐야 할 병목과 SQL을 분석해줘" : question;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }
}

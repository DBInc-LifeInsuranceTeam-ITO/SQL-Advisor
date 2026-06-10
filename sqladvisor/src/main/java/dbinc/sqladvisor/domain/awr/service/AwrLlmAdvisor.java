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
import java.util.Locale;
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

    public Optional<AwrDtos.SqlTuningResponse> tuneSql(
            Long reportId,
            String sqlId,
            AwrDtos.SqlTuningRequest request,
            AwrDtos.SqlTuningResponse localTuning,
            List<AwrRagChunk> ragChunks
    ) {
        if (!aiClient.isExternalLlmEnabled()) {
            return Optional.empty();
        }

        String systemPrompt = """
                You are SQLAdvisor, an Oracle SQL tuning advisor.
                Answer in Korean, but return JSON only.
                Use only the supplied AWR metric, SQL text, optional user evidence, and RAG evidence.
                Index recommendations must be candidates, not production-ready commands, unless execution plan,
                object metadata, existing indexes, and bind evidence are supplied.
                When recommending indexes, explicitly consider table data volume and load/write volume from
                NUM_ROWS, BLOCKS, LAST_ANALYZED, and recent INSERTS/UPDATES/DELETES evidence when supplied.
                For large or write-heavy tables, include index maintenance, ETL/write-window impact, build time,
                and stats gathering cost in risk and validation steps. If this evidence is unavailable, list it
                in missing_inputs instead of assuming the index is safe.
                DBA_*, V$, and GV$ views are valid diagnostic sources and should remain visible in analysis.
                Never recommend CREATE INDEX against Oracle data dictionary or dynamic performance views.
                For those SQLs, recommend rewrite/scope reduction, dictionary statistics validation,
                caching, or reducing repeated polling instead.
                Do not put NOLOGGING in the default ddl_candidate. If a large index build may use NOLOGGING,
                put it only in build_steps and include a follow-up ALTER INDEX ... LOGGING step.
                Do not invent table names, column names, execution plans, DDL, bind values, or object statistics.
                Return JSON only with this schema:
                {
                  "summary": "string",
                  "symptoms": ["string"],
                  "index_recommendations": [
                    {
                      "table_name": "string",
                      "columns": ["string"],
                      "ddl_candidate": "string",
                      "build_steps": ["string"],
                      "post_create_steps": ["string"],
                      "reason": "string",
                      "expected_benefit": "string",
                      "risk": "string",
                      "validation_sql": "string"
                    }
                  ],
                  "rewrite_recommendations": ["string"],
                  "validation_steps": ["string"],
                  "missing_inputs": ["string"],
                  "confidence": "low|medium|high"
                }
                """;
        String userPrompt = """
                SQL_ID:
                %s

                Optional user request and evidence:
                %s

                Local rule-based tuning draft:
                %s

                Retrieved AWR evidence:
                %s
                """.formatted(
                sqlId,
                toJson(request),
                toJson(localTuning),
                ragService.evidenceBlock(ragChunks)
        );

        return aiClient.complete(systemPrompt, userPrompt)
                .map(result -> toSqlTuning(reportId, sqlId, localTuning, ragChunks, result));
    }

    public Optional<AwrDtos.SqlTuningQuestionResponse> answerSqlTuningQuestion(
            AwrDtos.SqlTuningResponse tuning,
            String question,
            AwrDtos.SqlTuningQuestionResponse localAnswer,
            List<AwrDtos.SqlTuningQuestionResponse> questionHistory
    ) {
        if (!aiClient.isExternalLlmEnabled()) {
            return Optional.empty();
        }

        String systemPrompt = """
                You are SQLAdvisor, an Oracle SQL tuning advisor.
                Answer in Korean.
                Use only the supplied tuning result, SQL text, execution plan, existing indexes, binds, and object metadata.
                Be concise and specific. If evidence is missing, say what is missing instead of guessing.
                Do not invent execution plans, indexes, table statistics, bind values, or production DDL.
                """;
        String userPrompt = """
                User question:
                %s

                Local answer:
                %s

                Previous questions in this tuning session:
                %s

                Tuning result and context:
                %s
                """.formatted(
                safeQuestion(question),
                localAnswer.answer(),
                toJson(questionHistory == null ? List.of() : questionHistory),
                toJson(tuning)
        );

        return aiClient.complete(systemPrompt, userPrompt)
                .map(result -> new AwrDtos.SqlTuningQuestionResponse(
                        localAnswer.questionId(),
                        localAnswer.tuningId(),
                        question,
                        result.content(),
                        localAnswer.citations(),
                        result.providerModel(),
                        localAnswer.confidence(),
                        LocalDateTime.now()
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

    private AwrDtos.SqlTuningResponse toSqlTuning(
            Long reportId,
            String sqlId,
            AwrDtos.SqlTuningResponse localTuning,
            List<AwrRagChunk> ragChunks,
            AwrAiClient.LlmResult result
    ) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(result.content()));
            return new AwrDtos.SqlTuningResponse(
                    localTuning.tuningId(),
                    reportId,
                    sqlId,
                    localTuning.question(),
                    localTuning.input(),
                    localTuning.metric(),
                    textOr(root, "summary", localTuning.summary()),
                    stringList(root.path("symptoms"), localTuning.symptoms()),
                    indexRecommendations(root.path("index_recommendations"), localTuning.indexRecommendations()),
                    stringList(root.path("rewrite_recommendations"), localTuning.rewriteRecommendations()),
                    stringList(root.path("validation_steps"), localTuning.validationSteps()),
                    stringList(root.path("missing_inputs"), localTuning.missingInputs()),
                    merge(localTuning.citations(), ragService.citations(ragChunks)),
                    result.providerModel(),
                    textOr(root, "confidence", localTuning.confidence()),
                    LocalDateTime.now()
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            return new AwrDtos.SqlTuningResponse(
                    localTuning.tuningId(),
                    reportId,
                    sqlId,
                    localTuning.question(),
                    localTuning.input(),
                    localTuning.metric(),
                    result.content(),
                    localTuning.symptoms(),
                    localTuning.indexRecommendations(),
                    localTuning.rewriteRecommendations(),
                    localTuning.validationSteps(),
                    localTuning.missingInputs(),
                    merge(localTuning.citations(), ragService.citations(ragChunks)),
                    result.providerModel(),
                    localTuning.confidence(),
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

    private List<AwrDtos.IndexRecommendationResponse> indexRecommendations(
            JsonNode node,
            List<AwrDtos.IndexRecommendationResponse> fallback
    ) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return fallback;
        }
        List<AwrDtos.IndexRecommendationResponse> recommendations = new ArrayList<>();
        for (JsonNode item : node) {
            String tableName = textOr(item, "table_name", null);
            boolean dictionaryObject = isOracleDictionaryObject(tableName);
            String ddlCandidate = safeDdlCandidate(dictionaryObject, textOr(item, "ddl_candidate", null));
            List<String> buildSteps = dictionaryObject
                    ? List.of()
                    : safeBuildSteps(stringList(item.path("build_steps"), List.of()));
            List<String> postCreateSteps = dictionaryObject
                    ? List.of()
                    : stringList(item.path("post_create_steps"), List.of());
            String risk = textOr(item, "risk", "");
            if (dictionaryObject) {
                risk = appendSentence(risk, "Oracle dictionary or dynamic performance views are shown for diagnosis only; do not create user indexes on them.");
            }
            recommendations.add(new AwrDtos.IndexRecommendationResponse(
                    tableName,
                    stringList(item.path("columns"), List.of()),
                    ddlCandidate,
                    buildSteps,
                    postCreateSteps,
                    textOr(item, "reason", ""),
                    textOr(item, "expected_benefit", ""),
                    risk,
                    textOr(item, "validation_sql", "")
            ));
        }
        return recommendations.isEmpty() ? fallback : recommendations;
    }

    private String safeDdlCandidate(boolean dictionaryObject, String ddlCandidate) {
        if (dictionaryObject || isUnsafeDictionaryDdl(ddlCandidate)) {
            return null;
        }
        return ddlCandidate;
    }

    private List<String> safeBuildSteps(List<String> buildSteps) {
        return buildSteps.stream()
                .filter(step -> !isUnsafeDictionaryDdl(step))
                .toList();
    }

    private boolean isUnsafeDictionaryDdl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replace("\"", "").toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.contains("CREATE INDEX")
                && normalized.contains(" ON ")
                && (normalized.matches(".*\\sON\\s+(SYS\\.)?(ALL_|DBA_|USER_|V\\$|GV\\$).*")
                || normalized.matches(".*\\sON\\s+(SYS\\.)?[A-Z0-9_$#]+\\.(ALL_|DBA_|USER_|V\\$|GV\\$).*"));
    }

    private String appendSentence(String value, String sentence) {
        if (value == null || value.isBlank()) {
            return sentence;
        }
        String trimmed = value.trim();
        if (trimmed.contains(sentence)) {
            return trimmed;
        }
        return trimmed.endsWith(".") ? trimmed + " " + sentence : trimmed + ". " + sentence;
    }

    private boolean isOracleDictionaryObject(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        String clean = tableName.replace("\"", "").trim().toUpperCase(Locale.ROOT);
        int dot = clean.lastIndexOf('.');
        String simple = dot >= 0 ? clean.substring(dot + 1) : clean;
        return simple.startsWith("ALL_")
                || simple.startsWith("DBA_")
                || simple.startsWith("USER_")
                || simple.startsWith("V$")
                || simple.startsWith("GV$");
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

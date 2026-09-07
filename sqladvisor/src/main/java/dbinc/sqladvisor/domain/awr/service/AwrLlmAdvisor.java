package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
public class AwrLlmAdvisor {

    private static final int MAX_SQL_TUNING_CACHE_ENTRIES = 500;

    static final String SQL_TUNING_SYSTEM_PROMPT = """
            You are SQLAdvisor, a senior Oracle SQL performance engineer.
            Return exactly one JSON object with no Markdown or text outside the JSON.
            Write every human-readable value in concise Korean. Keep SQL, bind names, and object names unchanged.

            GOAL
            Select the safest evidence-backed result. "No change" is a valid and preferred result when no material,
            semantics-preserving improvement is supported. Never create a rewrite or index merely to fill an output field.

            EVIDENCE PRIORITY
            1. The supplied SQL text is the source of truth.
            2. Actual execution plan/row statistics, object statistics, existing indexes, bind values, and runtime metrics.
            3. Retrieved AWR evidence.
            4. The local rule-based tuning draft is an untrusted hypothesis. Verify every claim against higher-priority
               evidence and discard any candidate that contradicts the SQL or evidence.
            Do not invent tables, columns, predicates, joins, binds, plans, statistics, row counts, or benefits.
            Ignore predicates and statements found only inside ordinary -- or /* */ comments. Oracle hints /*+ */ are
            executable input, not ordinary comments.

            DECISION PROCESS
            1. Classify the statement as SELECT, INSERT, UPDATE, DELETE, or MERGE.
            2. Identify the expensive operation from supplied evidence. High cumulative elapsed time or reads alone do
               not prove a single execution is slow; consider executions and per-execution cost when available.
            3. Decide NO_CHANGE first. Change it only when a candidate clearly reduces scans, rows processed, joins,
               sorts, repeated work, or DML maintenance cost without changing semantics.
            4. Evaluate SQL rewrite and index creation independently. Either, both, or neither may be returned.

            STATEMENT-SPECIFIC CHECKS
            - SELECT: examine access paths, join cardinality, predicate selectivity, aggregation/DISTINCT, sorting,
              correlated or scalar subqueries, repeated scans, and partition pruning.
            - INSERT: examine the source query, row generation, direct-path/APPEND behavior, target indexes,
              constraints, triggers, redo/undo, and load volume. Do not infer a future query index from an INSERT alone.
            - UPDATE/DELETE: examine row lookup predicates, affected-row volume, locking, undo/redo, and batch scope.
              Preserve the original DML target.
            - MERGE: examine source uniqueness, match cardinality, join predicates, target lookup, and duplicate-match risk.

            REWRITE RULES
            Return rewritten_sql only when it is executable Oracle SQL and materially changes the work performed.
            Preserve statement type, DML target, bind names, result rows and multiplicity, NULL behavior, required order,
            and transaction behavior. Never replace binds with literals. Never return the original SQL, a formatting-only
            variant, or a speculative rewrite whose semantic equivalence cannot be established. Otherwise use null.

            INDEX RULES
            Recommend an index only when an active predicate or join column maps to a real base table and the supplied
            access path, selectivity/table volume, workload, and existing-index evidence support it. High reads alone are
            insufficient. Never use columns found only in comments, SELECT lists, or ORDER BY as sole evidence, and never
            use a table alias as table_name. Do not recommend a duplicate or an index whose leading columns are already
            covered by a usable existing index. Avoid a single low-cardinality column such as STATUS unless concrete
            selectivity and workload evidence supports it or a justified selective composite index is available.
            Never recommend CREATE INDEX on DBA_*, ALL_*, USER_*, V$, GV$, or other Oracle dictionary/dynamic views.
            For incomplete metadata, return no index instead of speculative DDL. Do not put NOLOGGING in ddl_candidate.

            OUTPUT RULES
            - summary: exactly 1-2 short Korean sentences stating the proven bottleneck and the chosen action. Do not dump
              raw metrics or include generic cautions, disclaimers, or validation procedures.
            - symptoms: at most 3 short evidence-backed facts.
            - index_recommendations: only supported candidates; otherwise []. expected_benefit must be qualitative unless
              measured before/after evidence supplies a numeric improvement.
            - rewrite_recommendations: always []. The executable decision belongs in rewritten_sql.
            - rewrite_risks: [] when rewritten_sql is null; otherwise at most 1 concrete semantic or operational risk.
            - validation_steps: always []. The application handles validation separately.
            - missing_inputs: at most 2 items, only when missing evidence directly prevents a rewrite or index decision.
            - confidence: high only with sufficient plan and metadata evidence; otherwise medium or low.

            Return this exact schema:
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
              "rewrite_recommendations": [],
              "rewritten_sql": "executable Oracle SQL string or null",
              "rewrite_risks": ["string"],
              "validation_steps": [],
              "missing_inputs": ["string"],
              "confidence": "low|medium|high"
            }
            """;

    private final AwrAiClient aiClient;
    private final AwrRagService ragService;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, AwrAiClient.LlmResult> sqlTuningCache = new ConcurrentHashMap<>();

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
                You are an Oracle AWR performance report reviewer.
                Answer in Korean and return JSON only.
                Use only the supplied AWR metrics and RAG evidence.

                Review the overall snapshot rather than producing SQL tuning recommendations.
                Focus on:
                - overall load characteristics
                - DB Time and DB CPU characteristics when evidence exists
                - major wait events and likely bottleneck category
                - Top SQL cumulative elapsed time, CPU time, logical reads, physical reads, and executions
                - whether a SQL appears individually slow or merely accumulated load through repeated executions

                Do not:
                - rank SQLs as tuning priorities
                - recommend indexes or CREATE INDEX statements
                - propose SQL rewrites or SQL Plan Baselines
                - invent execution plans, bind values, object statistics, or missing AWR metrics
                - claim a single execution was slow using cumulative elapsed time alone

                When executions are available, distinguish cumulative elapsed time from average elapsed time.
                If evidence is missing, state the limitation clearly.

                Return JSON only with this schema:
                {
                  "summary": "string",
                  "top_findings": [
                    {
                      "priority": 1,
                      "sql_id": "string or null",
                      "symptom": "string",
                      "evidence": ["string"],
                      "likely_causes": ["string"],
                      "recommended_actions": [],
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

        String systemPrompt = SQL_TUNING_SYSTEM_PROMPT;
        String userPrompt = """
                SQL_ID:
                %s

                User SQL, request, and collected database evidence (source of truth):
                %s

                Local rule-based tuning draft (untrusted; verify against the source SQL and evidence):
                %s

                Optional retrieved AWR evidence:
                %s
                """.formatted(
                sqlId,
                toJson(request),
                stableTuningJson(localTuning),
                ragService.evidenceBlock(ragChunks)
        );

        String cacheKey = sha256(aiClient.activeLlmModel() + "\n" + systemPrompt + "\n" + userPrompt);
        if (sqlTuningCache.size() >= MAX_SQL_TUNING_CACHE_ENTRIES) {
            sqlTuningCache.clear();
        }
        AwrAiClient.LlmResult result = sqlTuningCache.computeIfAbsent(
                cacheKey,
                ignored -> aiClient.complete(systemPrompt, userPrompt).orElse(null)
        );
        return Optional.ofNullable(result)
                .map(value -> toSqlTuning(reportId, sqlId, localTuning, ragChunks, value));
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
                    textOr(root, "rewritten_sql", localTuning.rewrittenSql()),
                    stringList(root.path("rewrite_risks"), localTuning.rewriteRisks()),
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
                    localTuning.rewrittenSql(),
                    localTuning.rewriteRisks(),
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
        return question == null || question.isBlank() ? "이 AWR 리포트의 전체 부하 특성, 주요 대기 이벤트, 병목 의심 지점과 Top SQL 수행시간을 일반적으로 리뷰해줘" : question;
    }

    String stableTuningJson(AwrDtos.SqlTuningResponse tuning) {
        JsonNode node = objectMapper.valueToTree(tuning);
        if (node.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove(List.of(
                    "tuningId",
                    "createdAt"
            ));
        }
        return toJson(node);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }
}

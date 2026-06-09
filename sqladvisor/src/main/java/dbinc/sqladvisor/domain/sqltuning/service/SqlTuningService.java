package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.awr.service.AwrLlmAdvisor;
import dbinc.sqladvisor.domain.awr.service.AwrSqlTuningAdvisor;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SqlTuningService {

    private static final String SOURCE_TYPE = "MANUAL_SQL";
    private static final String DIRECT_DB_SOURCE_TYPE = "DIRECT_DB";

    private final AwrSqlTuningAdvisor sqlTuningAdvisor;
    private final AwrLlmAdvisor llmAdvisor;
    private final SqlTuningRepository repository;
    private final CurrentUserService currentUserService;

    public AwrDtos.SqlTuningResponse tune(AwrDtos.SqlTuningRequest request) {
        if (request == null || request.sqlText() == null || request.sqlText().isBlank()) {
            throw new IllegalArgumentException("SQL text is required.");
        }

        String sqlText = request.sqlText().trim();
        String sqlId = "manual-" + shortHash(sqlText);
        String question = request.question() == null || request.question().isBlank()
                ? "Tune this SQL and recommend safe index candidates considering table volume and load/write volume."
                : request.question().trim();
        AwrDtos.SqlTuningRequest normalizedRequest = new AwrDtos.SqlTuningRequest(
                sqlText,
                question,
                blankToNull(request.executionPlan()),
                blankToNull(request.schemaDdl()),
                blankToNull(request.existingIndexes()),
                blankToNull(request.bindSamples())
        );
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                sqlId,
                "Manual SQL",
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sqlText,
                null,
                "Manual SQL tuning request."
        );

        AwrDtos.SqlTuningResponse local = sqlTuningAdvisor.tune(
                null,
                sqlId,
                question,
                metric,
                normalizedRequest,
                List.of("manual sql input")
        );
        AwrDtos.SqlTuningResponse selected = llmAdvisor.tuneSql(
                null,
                sqlId,
                normalizedRequest,
                local,
                List.of()
        ).orElse(local);

        long tuningId = repository.save(
                currentUserService.currentUserIdOrNull(),
                SOURCE_TYPE,
                selected.sqlId(),
                sqlText,
                normalizedRequest,
                selected
        );
        AwrDtos.SqlTuningResponse persisted = new AwrDtos.SqlTuningResponse(
                tuningId,
                null,
                selected.sqlId(),
                selected.question(),
                normalizedRequest,
                selected.metric(),
                selected.summary(),
                selected.symptoms(),
                selected.indexRecommendations(),
                selected.rewriteRecommendations(),
                selected.validationSteps(),
                selected.missingInputs(),
                selected.citations(),
                selected.model(),
                selected.confidence(),
                selected.createdAt() == null ? LocalDateTime.now() : selected.createdAt()
        );
        repository.updateResult(tuningId, persisted);
        return persisted;
    }

    public List<AwrDtos.SqlTuningResponse> listHistory() {
        return repository.findHistory(
                currentUserService.currentUserIdOrNull(),
                currentUserService.isCurrentUserAdmin()
        );
    }

    public AwrDtos.SqlTuningResponse getTuning(long tuningId) {
        return repository.findById(
                        tuningId,
                        currentUserService.currentUserIdOrNull(),
                        currentUserService.isCurrentUserAdmin()
                )
                .orElseThrow(() -> new IllegalArgumentException("SQL tuning result not found: " + tuningId));
    }

    public List<AwrDtos.SqlTuningQuestionResponse> listQuestions(long tuningId) {
        getTuning(tuningId);
        return repository.findQuestions(tuningId);
    }

    public AwrDtos.SqlTuningQuestionResponse askQuestion(long tuningId, AwrDtos.SqlTuningQuestionRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("Question is required.");
        }
        AwrDtos.SqlTuningResponse tuning = getTuning(tuningId);
        String question = request.question().trim();
        List<AwrDtos.SqlTuningQuestionResponse> questionHistory = repository.findQuestions(tuningId);
        AwrDtos.SqlTuningQuestionResponse local = localQuestionAnswer(tuning, question, questionHistory);
        AwrDtos.SqlTuningQuestionResponse selected = llmAdvisor.answerSqlTuningQuestion(tuning, question, local, questionHistory)
                .orElse(local);
        long questionId = repository.saveQuestion(
                currentUserService.currentUserIdOrNull(),
                tuningId,
                selected
        );
        return new AwrDtos.SqlTuningQuestionResponse(
                questionId,
                tuningId,
                selected.question(),
                selected.answer(),
                selected.citations(),
                selected.model(),
                selected.confidence(),
                selected.createdAt() == null ? LocalDateTime.now() : selected.createdAt()
        );
    }

    public AwrDtos.SqlTuningResponse tuneDirect(SqlTuningDtos.DirectDbContextResponse context) {
        if (context == null || context.input() == null || context.input().sqlText() == null || context.input().sqlText().isBlank()) {
            throw new IllegalArgumentException("Direct DB context must include SQL text.");
        }
        AwrDtos.SqlTuningRequest input = context.input();
        AwrDtos.SqlMetricResponse metric = context.metric() == null
                ? new AwrDtos.SqlMetricResponse(
                        "direct-" + shortHash(input.sqlText()),
                        "Direct DB SQL",
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        input.sqlText(),
                        null,
                        "Direct DB context did not include runtime metrics."
                )
                : context.metric();
        String sqlId = metric.sqlId() == null || metric.sqlId().isBlank()
                ? "direct-" + shortHash(input.sqlText())
                : metric.sqlId();
        String question = input.question() == null || input.question().isBlank()
                ? "Tune SQL from direct database context and recommend safe index candidates considering table volume and load/write volume."
                : input.question();
        List<String> citations = new java.util.ArrayList<>();
        citations.add("target_db_connection / " + context.connectionName());
        citations.add("direct_db_context / " + sqlId);
        if (context.warnings() != null) {
            context.warnings().forEach(warning -> citations.add("warning / " + warning));
        }

        AwrDtos.SqlTuningResponse local = sqlTuningAdvisor.tune(
                null,
                sqlId,
                question,
                metric,
                input,
                citations
        );
        AwrDtos.SqlTuningResponse selected = llmAdvisor.tuneSql(
                null,
                sqlId,
                input,
                local,
                List.of()
        ).orElse(local);

        long tuningId = repository.save(
                currentUserService.currentUserIdOrNull(),
                DIRECT_DB_SOURCE_TYPE,
                context.connectionId(),
                selected.sqlId(),
                input.sqlText(),
                input,
                selected
        );
        AwrDtos.SqlTuningResponse persisted = new AwrDtos.SqlTuningResponse(
                tuningId,
                null,
                selected.sqlId(),
                selected.question(),
                input,
                selected.metric(),
                selected.summary(),
                selected.symptoms(),
                selected.indexRecommendations(),
                selected.rewriteRecommendations(),
                selected.validationSteps(),
                selected.missingInputs(),
                selected.citations(),
                selected.model(),
                selected.confidence(),
                selected.createdAt() == null ? LocalDateTime.now() : selected.createdAt()
        );
        repository.updateResult(tuningId, persisted);
        return persisted;
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AwrDtos.SqlTuningQuestionResponse localQuestionAnswer(
            AwrDtos.SqlTuningResponse tuning,
            String question,
            List<AwrDtos.SqlTuningQuestionResponse> questionHistory
    ) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add("SQL_ID " + tuning.sqlId() + " 튜닝 컨텍스트 기준 답변입니다.");
        lines.add("질문: " + question);
        if (questionHistory != null && !questionHistory.isEmpty()) {
            lines.add("이전 질문 " + questionHistory.size() + "건의 맥락을 함께 참고했습니다.");
        }
        if (referencesOracleDictionary(tuning)) {
            lines.add("이 SQL은 Oracle 데이터 딕셔너리/동적 성능 뷰를 조회하므로 ALL_*/DBA_*/USER_*/V$/GV$ 객체에 CREATE INDEX 후보를 적용하면 안 됩니다.");
            lines.add("튜닝 방향은 USER_* 또는 더 좁은 scope의 뷰 사용, WHERE 조건 축소, 반복 조회 감소, 딕셔너리 통계 확인, 애플리케이션 캐싱 검토입니다.");
        }
        if (tuning.summary() != null && !tuning.summary().isBlank()) {
            lines.add("요약: " + tuning.summary());
        }
        if (!referencesOracleDictionary(tuning) && tuning.indexRecommendations() != null && !tuning.indexRecommendations().isEmpty()) {
            lines.add("인덱스 후보: " + tuning.indexRecommendations().stream()
                    .map(item -> (item.tableName() == null ? "table" : item.tableName())
                            + "(" + String.join(", ", item.columns() == null ? List.of() : item.columns()) + ")")
                    .toList());
        }
        if (tuning.input() != null && tuning.input().existingIndexes() != null && !tuning.input().existingIndexes().isBlank()) {
            lines.add("기존 인덱스 메타는 수집되어 있으므로 새 후보 적용 전 중복/커버리지 확인이 필요합니다.");
        }
        if (tuning.input() != null && tuning.input().executionPlan() != null && !tuning.input().executionPlan().isBlank()) {
            lines.add("수집된 실행계획 근거가 있으므로 row estimate, access path, buffer gets 변화를 기준으로 검증하십시오.");
        }
        if (tuning.validationSteps() != null && !tuning.validationSteps().isEmpty()) {
            lines.add("다음 검증: " + tuning.validationSteps().get(0));
        }
        return new AwrDtos.SqlTuningQuestionResponse(
                null,
                tuning.tuningId(),
                question,
                String.join(System.lineSeparator(), lines),
                tuning.citations(),
                "rule-based-local-advisor",
                tuning.confidence(),
                LocalDateTime.now()
        );
    }

    private boolean referencesOracleDictionary(AwrDtos.SqlTuningResponse tuning) {
        if (tuning == null) {
            return false;
        }
        if (containsOracleDictionaryName(tuning.input() == null ? null : tuning.input().sqlText())) {
            return true;
        }
        if (tuning.indexRecommendations() == null) {
            return false;
        }
        return tuning.indexRecommendations().stream()
                .anyMatch(item -> containsOracleDictionaryName(item.tableName())
                        || containsOracleDictionaryName(item.ddlCandidate()));
    }

    private boolean containsOracleDictionaryName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return upper.contains("ALL_")
                || upper.contains("DBA_")
                || upper.contains("USER_")
                || upper.contains("V$")
                || upper.contains("GV$");
    }
}

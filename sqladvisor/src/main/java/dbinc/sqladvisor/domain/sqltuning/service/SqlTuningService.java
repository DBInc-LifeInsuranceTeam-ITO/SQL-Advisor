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
}

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

    private static final String SOURCE_TYPE_MANUAL = "MANUAL_SQL";
    private static final String SOURCE_TYPE_DIRECT = "DIRECT_DB_SQL";

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
                ? "Tune this SQL and recommend safe index candidates with validation steps."
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
        return tuneInternal(SOURCE_TYPE_MANUAL, normalizedRequest, metric, List.of("manual sql input"));
    }

    public AwrDtos.SqlTuningResponse tuneDirect(SqlTuningDtos.DirectDbContextResponse context) {
        if (context == null || context.input() == null || context.input().sqlText() == null || context.input().sqlText().isBlank()) {
            throw new IllegalArgumentException("Direct DB SQL context is required.");
        }

        AwrDtos.SqlTuningRequest input = context.input();
        String sqlText = input.sqlText().trim();
        AwrDtos.SqlMetricResponse metric = context.metric() == null
                ? new AwrDtos.SqlMetricResponse(
                "direct-" + shortHash(sqlText),
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
                sqlText,
                null,
                "Direct DB tuning request."
        )
                : context.metric();
        String question = input.question() == null || input.question().isBlank()
                ? "Tune SQL from direct database context and recommend safe index candidates."
                : input.question().trim();
        AwrDtos.SqlTuningRequest normalizedRequest = new AwrDtos.SqlTuningRequest(
                sqlText,
                question,
                blankToNull(input.executionPlan()),
                blankToNull(input.schemaDdl()),
                blankToNull(input.existingIndexes()),
                blankToNull(input.bindSamples())
        );
        return tuneInternal(
                SOURCE_TYPE_DIRECT,
                normalizedRequest,
                metric,
                context.warnings() == null || context.warnings().isEmpty()
                        ? List.of("direct db context")
                        : context.warnings()
        );
    }

    private AwrDtos.SqlTuningResponse tuneInternal(
            String sourceType,
            AwrDtos.SqlTuningRequest normalizedRequest,
            AwrDtos.SqlMetricResponse metric,
            List<String> evidence
    ) {
        String sqlText = normalizedRequest.sqlText().trim();
        String sqlId = metric.sqlId() == null || metric.sqlId().isBlank()
                ? sourceType.toLowerCase() + "-" + shortHash(sqlText)
                : metric.sqlId();
        String question = normalizedRequest.question();
        AwrDtos.SqlTuningResponse local = sqlTuningAdvisor.tune(
                null,
                sqlId,
                question,
                metric,
                normalizedRequest,
                evidence
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
                sourceType,
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

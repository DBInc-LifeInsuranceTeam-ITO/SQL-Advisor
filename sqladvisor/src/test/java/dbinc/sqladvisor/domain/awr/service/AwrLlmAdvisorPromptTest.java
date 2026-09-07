package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwrLlmAdvisorPromptTest {

    @Test
    void tuningPromptRequiresEvidenceBackedMaterialChanges() {
        String prompt = AwrLlmAdvisor.SQL_TUNING_SYSTEM_PROMPT;

        assertThat(prompt)
                .contains("The supplied SQL text is the source of truth")
                .contains("local rule-based tuning draft is an untrusted hypothesis")
                .contains("Decide NO_CHANGE first")
                .contains("SELECT, INSERT, UPDATE, DELETE, or MERGE")
                .contains("Never return the original SQL, a formatting-only")
                .contains("Never use columns found only in comments")
                .contains("never use a table alias as table_name")
                .contains("Return exactly one JSON object");
    }

    @Test
    void tuningPromptKeepsUnusedNarrativeFieldsEmpty() {
        String prompt = AwrLlmAdvisor.SQL_TUNING_SYSTEM_PROMPT;

        assertThat(prompt)
                .contains("rewrite_recommendations: always []")
                .contains("validation_steps: always []")
                .contains("rewrite_risks: [] when rewritten_sql is null")
                .contains("missing_inputs: at most 2 items")
                .contains("summary: exactly 1-2 short Korean sentences");
    }

    @Test
    void reusesAiResultWhenSqlAndEvidenceAreUnchanged() {
        AwrAiClient aiClient = mock(AwrAiClient.class);
        AwrRagService ragService = mock(AwrRagService.class);
        AwrLlmAdvisor advisor = new AwrLlmAdvisor(aiClient, ragService, new ObjectMapper().findAndRegisterModules());
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                "SELECT status, COUNT(*) FROM orders GROUP BY status",
                "병목 원인과 개선 SQL을 알려줘",
                "TABLE ACCESS FULL ORDERS",
                "CREATE TABLE orders(status VARCHAR2(10))",
                null,
                null
        );
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "sql-1", "Manual SQL", 0, 1.5, 1.0, 5_361L, 5_342L,
                1L, 2L, 123L, null, request.sqlText(), null, "test"
        );
        AwrSqlTuningAdvisor localAdvisor = new AwrSqlTuningAdvisor();
        AwrDtos.SqlTuningResponse firstLocal = localAdvisor.tune(
                null, "sql-1", request.question(), metric, request, List.of()
        );
        AwrDtos.SqlTuningResponse secondLocal = localAdvisor.tune(
                null, "sql-1", request.question(), metric, request, List.of()
        );

        when(aiClient.isExternalLlmEnabled()).thenReturn(true);
        when(aiClient.activeLlmModel()).thenReturn("openai/test-model");
        when(ragService.evidenceBlock(anyList())).thenReturn("");
        when(ragService.citations(anyList())).thenReturn(List.of());
        when(aiClient.complete(anyString(), anyString())).thenReturn(Optional.of(new AwrAiClient.LlmResult(
                "openai",
                "test-model",
                """
                        {"summary":"확정할 수 있는 변경안이 없습니다.","symptoms":[],"index_recommendations":[],
                        "rewrite_recommendations":[],"rewritten_sql":null,"rewrite_risks":[],
                        "validation_steps":[],"missing_inputs":[],"confidence":"medium"}
                        """
        )));

        assertThat(advisor.stableTuningJson(firstLocal)).isEqualTo(advisor.stableTuningJson(secondLocal));
        assertThat(advisor.stableTuningJson(firstLocal)).doesNotContain("createdAt");

        advisor.tuneSql(null, "sql-1", request, firstLocal, List.of());
        advisor.tuneSql(null, "sql-1", request, secondLocal, List.of());

        verify(aiClient, times(1)).complete(anyString(), anyString());

        AwrDtos.SqlMetricResponse changedMetric = new AwrDtos.SqlMetricResponse(
                "sql-1", "Manual SQL", 0, 2.5, 1.0, 9_000L, 5_342L,
                1L, 2L, 123L, null, request.sqlText(), null, "test"
        );
        AwrDtos.SqlTuningResponse changedLocal = localAdvisor.tune(
                null, "sql-1", request.question(), changedMetric, request, List.of()
        );

        advisor.tuneSql(null, "sql-1", request, changedLocal, List.of());

        verify(aiClient, times(2)).complete(anyString(), anyString());
    }
}

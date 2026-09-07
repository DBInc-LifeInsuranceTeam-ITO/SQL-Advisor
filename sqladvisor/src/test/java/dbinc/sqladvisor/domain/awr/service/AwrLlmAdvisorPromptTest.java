package dbinc.sqladvisor.domain.awr.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}

package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.awr.service.AwrLlmAdvisor;
import dbinc.sqladvisor.domain.awr.service.AwrSqlTuningAdvisor;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlTuningServiceTest {

    @Test
    void tunesManualSqlAndPersistsResult() {
        AwrLlmAdvisor llmAdvisor = mock(AwrLlmAdvisor.class);
        SqlTuningRepository repository = mock(SqlTuningRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        SqlTuningService service = new SqlTuningService(
                new AwrSqlTuningAdvisor(),
                llmAdvisor,
                repository,
                currentUserService
        );

        when(llmAdvisor.tuneSql(any(), anyString(), any(), any(), anyList())).thenReturn(Optional.empty());
        when(currentUserService.currentUserIdOrNull()).thenReturn(7L);
        when(repository.save(eq(7L), eq("MANUAL_SQL"), anyString(), anyString(), any(), any())).thenReturn(42L);

        AwrDtos.SqlTuningResponse response = service.tune(new AwrDtos.SqlTuningRequest(
                "SELECT * FROM orders o WHERE o.customer_id = :1 AND o.status = :2",
                null,
                "plan",
                "CREATE TABLE orders(customer_id NUMBER, status VARCHAR2(10))",
                "CREATE INDEX idx_orders_status ON orders(status)",
                ":1=100, :2='READY'"
        ));

        assertThat(response.tuningId()).isEqualTo(42L);
        assertThat(response.reportId()).isNull();
        assertThat(response.sqlId()).startsWith("manual-");
        assertThat(response.input()).isNotNull();
        assertThat(response.input().sqlText()).startsWith("SELECT * FROM orders");
        assertThat(response.input().executionPlan()).isEqualTo("plan");
        assertThat(response.input().schemaDdl()).contains("CREATE TABLE orders");
        assertThat(response.input().existingIndexes()).contains("idx_orders_status");
        assertThat(response.input().bindSamples()).contains(":1=100");
        assertThat(response.metric().sqlText()).contains("orders");
        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.indexRecommendations().get(0).columns()).containsExactly("customer_id", "status");
        assertThat(response.confidence()).isEqualTo("high");
        verify(repository).updateResult(eq(42L), any(AwrDtos.SqlTuningResponse.class));
    }
}

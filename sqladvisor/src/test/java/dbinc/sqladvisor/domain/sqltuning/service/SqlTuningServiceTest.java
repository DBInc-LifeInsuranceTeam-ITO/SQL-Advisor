package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.awr.service.AwrLlmAdvisor;
import dbinc.sqladvisor.domain.awr.service.AwrSqlTuningAdvisor;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
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
                """
                        CREATE TABLE orders(customer_id NUMBER, status VARCHAR2(10))

                        -- Table statistics
                        APP.ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N

                        -- Table load statistics
                        APP.ORDERS inserts=1200000, updates=250000, deletes=10000, changed_rows=1460000, last_dml=2026-06-05 11:00:00, truncated=NO
                        """,
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
        assertThat(response.indexRecommendations().get(0).reason()).contains("num_rows=50000000");
        assertThat(response.indexRecommendations().get(0).risk()).contains("changed_rows=1460000");
        assertThat(response.confidence()).isEqualTo("high");
        verify(repository).updateResult(eq(42L), any(AwrDtos.SqlTuningResponse.class));
    }

    @Test
    void tunesDirectDbContextAndPersistsConnectionId() {
        AwrLlmAdvisor llmAdvisor = mock(AwrLlmAdvisor.class);
        SqlTuningRepository repository = mock(SqlTuningRepository.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        SqlTuningService service = new SqlTuningService(
                new AwrSqlTuningAdvisor(),
                llmAdvisor,
                repository,
                currentUserService
        );
        AwrDtos.SqlTuningRequest input = new AwrDtos.SqlTuningRequest(
                "SELECT * FROM orders o WHERE o.customer_id = :customer_id",
                "Tune direct DB SQL",
                "TABLE ACCESS FULL ORDERS",
                """
                        CREATE TABLE orders(customer_id NUMBER)

                        -- Table statistics
                        APP.ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N

                        -- Table load statistics
                        APP.ORDERS inserts=1200000, updates=250000, deletes=10000, changed_rows=1460000, last_dml=2026-06-05 11:00:00, truncated=NO
                        """,
                null,
                ":customer_id=100"
        );
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "7p6k1x9s2m3ab",
                "Direct DB SQL",
                0,
                12.3,
                3.2,
                12_000_000L,
                200_000L,
                15L,
                10L,
                12345L,
                "JDBC",
                input.sqlText(),
                null,
                "Collected from target database v$sql."
        );

        when(llmAdvisor.tuneSql(any(), anyString(), any(), any(), anyList())).thenReturn(Optional.empty());
        when(currentUserService.currentUserIdOrNull()).thenReturn(7L);
        when(repository.save(eq(7L), eq("DIRECT_DB"), eq(3L), anyString(), anyString(), any(), any())).thenReturn(99L);

        AwrDtos.SqlTuningResponse response = service.tuneDirect(new SqlTuningDtos.DirectDbContextResponse(
                3L,
                "PROD readonly",
                metric,
                input,
                List.of("Bind capture 조회 실패"),
                LocalDateTime.now()
        ));

        assertThat(response.tuningId()).isEqualTo(99L);
        assertThat(response.sqlId()).isEqualTo("7p6k1x9s2m3ab");
        assertThat(response.input()).isEqualTo(input);
        assertThat(response.metric().bufferGets()).isEqualTo(12_000_000L);
        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.indexRecommendations().get(0).reason()).contains("num_rows=50000000");
        assertThat(response.indexRecommendations().get(0).risk()).contains("changed_rows=1460000");
        assertThat(response.citations()).contains("target_db_connection / PROD readonly");
        verify(repository).updateResult(eq(99L), any(AwrDtos.SqlTuningResponse.class));
    }
}

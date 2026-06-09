package dbinc.sqladvisor.domain.awr.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AwrSqlTuningAdvisorTest {

    private final AwrSqlTuningAdvisor advisor = new AwrSqlTuningAdvisor();

    @Test
    void recommendsCandidateIndexFromSqlPredicatesWhenMetricIsHeavy() {
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "5yqqyn38m2jjw",
                "SQL ordered by Gets",
                1,
                120.0,
                80.0,
                20_000_000L,
                5_000L,
                40L,
                1_000L,
                123456789L,
                "JDBC Thin Client",
                "SELECT * FROM orders o WHERE o.customer_id = :1 AND o.status = :2",
                99.0,
                "High buffer gets."
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                1L,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                null,
                List.of("awr_sql_metric / SQL_ID " + metric.sqlId())
        );

        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.indexRecommendations().get(0).tableName()).isEqualTo("orders");
        assertThat(response.indexRecommendations().get(0).columns()).containsExactly("customer_id", "status");
        assertThat(response.indexRecommendations().get(0).ddlCandidate()).contains("CREATE INDEX");
        assertThat(response.confidence()).isEqualTo("medium");
    }

    @Test
    void doesNotEmitIndexDdlWithoutSqlText() {
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "1kgrkvwnjxwxy",
                "SQL ordered by Reads",
                1,
                300.0,
                20.0,
                500_000L,
                300_000L,
                5L,
                null,
                null,
                null,
                null,
                80.0,
                null
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                1L,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                null,
                List.of()
        );

        assertThat(response.indexRecommendations()).isEmpty();
        assertThat(response.missingInputs()).contains("full SQL text");
        assertThat(response.confidence()).isEqualTo("low");
    }

    @Test
    void separatesNoLoggingBuildStepsForLargeTables() {
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "7p6k1x9s2m3ab",
                "Direct DB SQL",
                1,
                120.0,
                80.0,
                20_000_000L,
                200_000L,
                40L,
                1_000L,
                123456789L,
                "JDBC Thin Client",
                "SELECT * FROM ax_orders o WHERE o.order_id = :1",
                99.0,
                "High buffer gets."
        );
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                metric.sqlText(),
                "Tune this SQL",
                "TABLE ACCESS FULL AX_ORDERS",
                """
                        -- Table statistics
                        APP.AX_ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N
                        """,
                "APP.AX_ORDERS | APP.IDX_AX_ORDERS_PK | columns=(ORDER_ID) | uniqueness=UNIQUE | status=VALID | logging=YES | visibility=VISIBLE",
                ":1=100"
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        AwrDtos.IndexRecommendationResponse recommendation = response.indexRecommendations().get(0);
        assertThat(recommendation.ddlCandidate()).doesNotContain("NOLOGGING");
        assertThat(recommendation.buildSteps()).anySatisfy(step -> assertThat(step).contains("NOLOGGING"));
        assertThat(recommendation.buildSteps()).anySatisfy(step -> assertThat(step).contains("ALTER INDEX").contains("LOGGING"));
        assertThat(recommendation.postCreateSteps()).isNotEmpty();
        assertThat(response.validationSteps()).anySatisfy(step -> assertThat(step).contains("NOLOGGING").contains("LOGGING"));
    }

    @Test
    void doesNotRecommendIndexOnOracleDictionaryViews() {
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "f9q9bnv12sxcx",
                "Direct DB SQL",
                1,
                120.0,
                80.0,
                20_000_000L,
                200_000L,
                40L,
                1_000L,
                123456789L,
                "JDBC Thin Client",
                """
                        SELECT c.owner, c.constraint_name
                          FROM all_constraints c
                          JOIN all_cons_columns col
                            ON col.owner = c.owner
                           AND col.constraint_name = c.constraint_name
                         WHERE c.owner = :owner
                           AND c.constraint_type = 'P'
                        """,
                99.0,
                "High buffer gets."
        );
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                metric.sqlText(),
                "Tune dictionary SQL",
                "TABLE ACCESS FULL ALL_CONSTRAINTS",
                null,
                null,
                ":owner=APP"
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune dictionary SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).isEmpty();
        assertThat(response.summary()).contains("Oracle data dictionary");
        assertThat(response.rewriteRecommendations()).anySatisfy(item -> assertThat(item).contains("do not create user indexes"));
        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("dictionary statistics"));
        assertThat(response.confidence()).isEqualTo("low");
    }
}

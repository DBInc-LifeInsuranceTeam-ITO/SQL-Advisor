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
}

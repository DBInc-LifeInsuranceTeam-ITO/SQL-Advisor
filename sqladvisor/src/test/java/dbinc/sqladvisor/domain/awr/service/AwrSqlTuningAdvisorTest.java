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
                "APP.AX_ORDERS | APP.IDX_AX_ORDERS_STATUS | columns=(STATUS) | uniqueness=NONUNIQUE | status=VALID | logging=YES | visibility=VISIBLE",
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
    void skipsIndexCandidateCoveredByExistingLeadingColumns() {
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "5yqqyn38m2jjw",
                "Direct DB SQL",
                1,
                120.0,
                80.0,
                20_000_000L,
                5_000L,
                40L,
                1_000L,
                123456789L,
                "JDBC Thin Client",
                "SELECT * FROM APP.ORDERS o WHERE o.customer_id = :1 AND o.status = :2",
                99.0,
                "High buffer gets."
        );
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                metric.sqlText(),
                "Tune this SQL",
                "TABLE ACCESS FULL APP.ORDERS",
                "APP.ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N",
                "APP.ORDERS | APP.IDX_ORDERS_CUSTOMER_STATUS | columns=(CUSTOMER_ID, STATUS) | uniqueness=NONUNIQUE | status=VALID | logging=YES | visibility=VISIBLE",
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

        assertThat(response.indexRecommendations()).isEmpty();
    }

    @Test
    void skipsIndexCandidateCoveredByRelatedTableIndexesSection() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM APP.ORDERS o WHERE o.status = :1",
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        APP.ORDERS | APP.IDX_ORDERS_STATUS_DATE | columns=(STATUS, ORDER_DATE) | uniqueness=NONUNIQUE | status=VALID | logging=YES | visibility=VISIBLE
                        -- Plan Used Indexes
                        APP.ORDERS | APP.IDX_ORDERS_STATUS_DATE | access=INDEX RANGE SCAN | uniqueness=NONUNIQUE | status=VALID | visibility=VISIBLE
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).isEmpty();
    }

    @Test
    void recommendsCandidateWhenRelatedTableIndexesDoNotCoverPredicateColumns() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM APP.ORDERS o WHERE o.status = :1",
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        APP.ORDERS | APP.IDX_ORDERS_ORDER_DATE | columns=(ORDER_DATE) | uniqueness=NONUNIQUE | status=VALID | logging=YES | visibility=VISIBLE
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.indexRecommendations().get(0).columns()).containsExactly("status");
    }

    @Test
    void recommendsOrderDateWhenOnlyOrderIdIndexExists() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM TEST.AX_ORDERS o WHERE o.order_date >= :1",
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        TEST.AX_ORDERS | TEST.SYS_C007602 | columns=(ORDER_ID) | uniqueness=UNIQUE | status=VALID | logging=YES | visibility=VISIBLE | blevel=2 | leaf_blocks=13941 | distinct_keys=7000000 | clustering_factor=44983 | num_rows=7000000 | last_analyzed=2026-06-12 05:00:07.0
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.indexRecommendations().get(0).columns()).containsExactly("order_date");
    }

    @Test
    void keepsCandidateAndExplainsTrailingColumnOrderWhenExistingIndexHasCandidateLater() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM TEST.AX_ORDERS o WHERE o.order_date >= :1",
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        TEST.AX_ORDERS | TEST.IDX_AX_ORDERS_ID_DATE | columns=(ORDER_ID, ORDER_DATE) | uniqueness=NONUNIQUE | status=VALID | logging=YES | visibility=VISIBLE | blevel=2 | leaf_blocks=13941 | distinct_keys=7000000 | clustering_factor=6500000 | num_rows=7000000 | last_analyzed=2026-06-12 05:00:07.0
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.indexRecommendations().get(0).reason()).contains("leading column order");
    }

    @Test
    void doesNotTreatInvisibleOrUnusableIndexAsCoveringCandidate() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM TEST.AX_ORDERS o WHERE o.order_date >= :1",
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        TEST.AX_ORDERS | TEST.IDX_AX_ORDERS_DATE | columns=(ORDER_DATE) | uniqueness=NONUNIQUE | status=UNUSABLE | logging=YES | visibility=INVISIBLE | blevel=2 | leaf_blocks=13941 | distinct_keys=7000000 | clustering_factor=44983 | num_rows=7000000 | last_analyzed=2026-06-12 05:00:07.0
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("usable/visible"));
    }

    @Test
    void addsIndexStatsValidationWhenExistingIndexStatsAreMissing() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM TEST.AX_ORDERS o WHERE o.order_date >= :1",
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        TEST.AX_ORDERS | TEST.SYS_C007602 | columns=(ORDER_ID) | uniqueness=UNIQUE | status=VALID | logging=YES | visibility=VISIBLE | blevel=- | leaf_blocks=- | distinct_keys=- | clustering_factor=- | num_rows=- | last_analyzed=-
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("missing or incomplete statistics"));
    }

    @Test
    void doesNotRecommendQueryIndexForInsertLoadSql() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                """
                        INSERT /*+ APPEND */ INTO TEST.AX_ORDERS (ORDER_ID, ORDER_DATE)
                        SELECT TEST.SEQ_AX_ORDER_ID.NEXTVAL, T.ORDER_DATE
                          FROM (SELECT SYSDATE ORDER_DATE FROM DUAL CONNECT BY LEVEL <= 1000000) T
                        """,
                20_000_000L,
                5_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Related Table Indexes
                        TEST.AX_ORDERS | TEST.SYS_C007602 | columns=(ORDER_ID) | uniqueness=UNIQUE | status=VALID | logging=YES | visibility=VISIBLE
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).isEmpty();
        assertThat(response.summary()).contains("INSERT/load statement");
        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("actual SELECT SQL_ID"));
    }

    @Test
    void addsCurrentIndexEfficiencyChecksWhenUsedIndexExistsAndCostIsHigh() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM APP.ORDERS o WHERE o.status = :1",
                20_000_000L,
                200_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Plan Used Indexes
                        APP.ORDERS | APP.IDX_ORDERS_STATUS | access=INDEX RANGE SCAN | uniqueness=NONUNIQUE | status=VALID | visibility=VISIBLE
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).hasSize(1);
        assertThat(response.rewriteRecommendations()).anySatisfy(item -> assertThat(item).contains("Plan Used Indexes"));
        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("current index access path"));
        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("partition pruning"));
    }

    @Test
    void skipsCandidateCoveredByPlanUsedIndexWhenColumnsAreAvailable() {
        AwrDtos.SqlMetricResponse metric = directMetric(
                "SELECT * FROM APP.ORDERS o WHERE o.status = :1",
                20_000_000L,
                200_000L
        );
        AwrDtos.SqlTuningRequest request = requestWithIndexes(
                metric.sqlText(),
                """
                        -- Plan Used Indexes
                        APP.ORDERS | APP.IDX_ORDERS_STATUS | columns=(STATUS) | access=INDEX RANGE SCAN | uniqueness=NONUNIQUE | status=VALID | visibility=VISIBLE
                        """
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune this SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations()).isEmpty();
        assertThat(response.summary()).contains("existing usable index");
        assertThat(response.validationSteps()).anySatisfy(item -> assertThat(item).contains("current index access path"));
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

    private AwrDtos.SqlMetricResponse directMetric(String sqlText, Long bufferGets, Long diskReads) {
        return new AwrDtos.SqlMetricResponse(
                "5yqqyn38m2jjw",
                "Direct DB SQL",
                1,
                120.0,
                80.0,
                bufferGets,
                diskReads,
                40L,
                1_000L,
                123456789L,
                "JDBC Thin Client",
                sqlText,
                99.0,
                "High buffer gets."
        );
    }

    private AwrDtos.SqlTuningRequest requestWithIndexes(String sqlText, String existingIndexes) {
        return new AwrDtos.SqlTuningRequest(
                sqlText,
                "Tune this SQL",
                "TABLE ACCESS FULL APP.ORDERS",
                "APP.ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N",
                existingIndexes,
                ":1=READY"
        );
    }
}

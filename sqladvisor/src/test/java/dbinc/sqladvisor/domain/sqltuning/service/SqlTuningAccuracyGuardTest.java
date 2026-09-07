package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.awr.service.AwrSqlTuningAdvisor;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTuningAccuracyGuardTest {

    private final AwrSqlTuningAdvisor advisor = new AwrSqlTuningAdvisor();

    @Test
    void suppressesHighCumulativeLoadWhenPerExecutionCostIsLow() {
        AwrDtos.SqlMetricResponse metric = metric(
                "SELECT * FROM APP.ORDERS o WHERE o.customer_id = :1",
                20_000_000L,
                20_000L,
                2_000_000L,
                100.0
        );
        AwrDtos.SqlTuningRequest request = request(
                metric.sqlText(),
                "INDEX RANGE SCAN APP.IDX_ORDERS_OTHER",
                "APP.ORDERS",
                50_000_000L,
                "APP.ORDERS | APP.IDX_ORDERS_OTHER | columns=(ORDER_DATE) | uniqueness=NONUNIQUE | status=VALID | visibility=VISIBLE"
        );

        AwrDtos.SqlTuningResponse raw = advisor.tune(
                null,
                metric.sqlId(),
                "Tune SQL",
                metric,
                request,
                List.of()
        );
        assertThat(raw.indexRecommendations()).hasSize(1);

        AwrDtos.SqlTuningResponse refined = SqlTuningAccuracyGuard.refine(raw);

        assertThat(refined.indexRecommendations()).isEmpty();
        assertThat(refined.symptoms()).anySatisfy(
                item -> assertThat(item).contains("실행당 Buffer Gets")
        );
    }

    @Test
    void suppressesIndexForSmallTableEvenWithFullScan() {
        AwrDtos.SqlMetricResponse metric = metric(
                "SELECT * FROM APP.CODE_TABLE c WHERE c.code = :1",
                1_000_000L,
                10_000L,
                10L,
                20.0
        );
        AwrDtos.SqlTuningRequest request = request(
                metric.sqlText(),
                "TABLE ACCESS FULL APP.CODE_TABLE",
                "APP.CODE_TABLE",
                2_000L,
                "APP.CODE_TABLE | APP.IDX_CODE_DESC | columns=(DESCRIPTION) | uniqueness=NONUNIQUE | status=VALID | visibility=VISIBLE"
        );

        AwrDtos.SqlTuningResponse raw = advisor.tune(
                null,
                metric.sqlId(),
                "Tune SQL",
                metric,
                request,
                List.of()
        );
        assertThat(raw.indexRecommendations()).hasSize(1);

        AwrDtos.SqlTuningResponse refined = SqlTuningAccuracyGuard.refine(raw);

        assertThat(refined.indexRecommendations()).isEmpty();
    }

    @Test
    void reordersEqualityColumnsUsingColumnSelectivity() {
        String sql = "SELECT * FROM APP.ORDERS o WHERE o.status = :1 AND o.customer_id = :2";
        AwrDtos.SqlMetricResponse metric = metric(
                sql,
                20_000_000L,
                200_000L,
                40L,
                120.0
        );
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                sql,
                "Tune SQL",
                "TABLE ACCESS FULL APP.ORDERS",
                """
                        -- Table statistics
                        APP.ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-09-07 10:00:00
                        -- Column statistics
                        APP.ORDERS.STATUS num_distinct=2, density=0.5, num_nulls=0, histogram=NONE, last_analyzed=2026-09-07 10:00:00
                        APP.ORDERS.CUSTOMER_ID num_distinct=40000000, density=0.000000025, num_nulls=0, histogram=NONE, last_analyzed=2026-09-07 10:00:00
                        """,
                "APP.ORDERS | APP.IDX_ORDERS_DATE | columns=(ORDER_DATE) | uniqueness=NONUNIQUE | status=VALID | visibility=VISIBLE",
                ":1=READY, :2=100"
        );

        AwrDtos.SqlTuningResponse raw = advisor.tune(
                null,
                metric.sqlId(),
                "Tune SQL",
                metric,
                request,
                List.of()
        );
        assertThat(raw.indexRecommendations().get(0).columns())
                .containsExactly("status", "customer_id");

        AwrDtos.SqlTuningResponse refined = SqlTuningAccuracyGuard.refine(raw);

        assertThat(refined.indexRecommendations()).hasSize(1);
        assertThat(refined.indexRecommendations().get(0).columns())
                .containsExactly("customer_id", "status");
        assertThat(refined.indexRecommendations().get(0).ddlCandidate())
                .contains("customer_id, status");
        assertThat(refined.indexRecommendations().get(0).reason())
                .contains("선택도");
    }

    @Test
    void keepsRuleBasedCoreDecisionWhenLlmContradictsIt() {
        String sql = "SELECT * FROM APP.ORDERS o WHERE o.customer_id = :customer_id";
        AwrDtos.SqlMetricResponse metric = metric(sql, 1_000L, 10L, 100L, 1.0);
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                sql,
                "Tune SQL",
                null,
                null,
                null,
                null
        );

        AwrDtos.SqlTuningResponse authoritative = new AwrDtos.SqlTuningResponse(
                null,
                null,
                metric.sqlId(),
                "Tune SQL",
                request,
                metric,
                "근거 부족으로 인덱스를 확정하지 않습니다.",
                List.of("근거 기반 증상"),
                List.of(),
                List.of("실행계획을 먼저 확인하세요."),
                null,
                List.of(),
                List.of("DBMS_XPLAN 확인"),
                List.of("actual execution plan"),
                List.of("manual sql input"),
                "rule-based-local-advisor",
                "low",
                LocalDateTime.now()
        );

        AwrDtos.IndexRecommendationResponse inventedIndex =
                new AwrDtos.IndexRecommendationResponse(
                        "APP.ORDERS",
                        List.of("CUSTOMER_ID"),
                        "CREATE INDEX IDX_AI ON APP.ORDERS (CUSTOMER_ID);",
                        List.of(),
                        List.of(),
                        "AI invented candidate",
                        "benefit",
                        "risk",
                        "validation"
                );

        AwrDtos.SqlTuningResponse llm = new AwrDtos.SqlTuningResponse(
                null,
                null,
                metric.sqlId(),
                "Tune SQL",
                request,
                metric,
                "CUSTOMER_ID 인덱스를 생성하는 것이 좋습니다.",
                List.of("AI symptom"),
                List.of(inventedIndex),
                List.of("AI rewrite"),
                "SELECT * FROM APP.ORDERS o WHERE o.customer_id = :other_bind",
                List.of(),
                List.of("AI validation"),
                List.of(),
                List.of(),
                "openai/test-model",
                "high",
                LocalDateTime.now()
        );

        AwrDtos.SqlTuningResponse merged =
                SqlTuningAccuracyGuard.mergeLlm(authoritative, llm);

        assertThat(merged.indexRecommendations()).isEmpty();
        assertThat(merged.confidence()).isEqualTo("low");
        assertThat(merged.validationSteps()).containsExactly("DBMS_XPLAN 확인");
        assertThat(merged.summary())
                .doesNotContain("인덱스를 생성하는 것이 좋습니다");
        assertThat(merged.rewrittenSql()).isNull();
        assertThat(merged.rewriteRisks()).anySatisfy(
                item -> assertThat(item).contains("검증을 통과하지 못해 제외")
        );
    }

    @Test
    void ignoresPredicateInsideLineCommentWhenBuildingIndexCandidate() {
        String sql = """
                SELECT COUNT(*)
                FROM TEST.AX_ORDERS a, TEST.AX_ORDERS b
                WHERE a.status = b.status
                -- AND a.amount + b.amount > 0
                AND a.status = 'CANCEL'
                """;
        AwrDtos.SqlMetricResponse metric = metric(sql, 193_385L, 61_037L, 1L, 190.7);
        AwrDtos.SqlTuningRequest request = request(
                sql,
                "TABLE ACCESS FULL TEST.AX_ORDERS",
                "TEST.AX_ORDERS",
                1_000_000L,
                "TEST.AX_ORDERS | TEST.IDX_AX_ORDERS_ID | columns=(ORDER_ID) | uniqueness=UNIQUE | status=VALID | visibility=VISIBLE"
        );

        AwrDtos.SqlTuningResponse response = advisor.tune(
                null,
                metric.sqlId(),
                "Tune SQL",
                metric,
                request,
                List.of()
        );

        assertThat(response.indexRecommendations())
                .isNotEmpty()
                .allSatisfy(item -> {
                    assertThat(item.tableName()).isNotEqualToIgnoringCase("b");
                    assertThat(item.columns()).noneMatch(column -> column.equalsIgnoreCase("amount"));
                });
    }

    @Test
    void rejectsFormattingOnlySqlRewrite() {
        String sql = """
                SELECT status, COUNT(*)
                FROM TEST.AX_ORDERS_KEEP
                GROUP BY status
                ORDER BY COUNT(*) DESC
                """;
        AwrDtos.SqlMetricResponse metric = metric(sql, 5_361L, 5_342L, 1L, 1.52);
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                sql, "Tune SQL", null, null, null, null
        );
        AwrDtos.SqlTuningResponse authoritative = advisor.tune(
                null, metric.sqlId(), "Tune SQL", metric, request, List.of()
        );
        AwrDtos.SqlTuningResponse llm = new AwrDtos.SqlTuningResponse(
                null,
                null,
                metric.sqlId(),
                "Tune SQL",
                request,
                metric,
                "SQL을 재작성했습니다.",
                List.of(),
                List.of(),
                List.of(),
                "select STATUS, count(*) from TEST.AX_ORDERS_KEEP group by STATUS order by count(*) desc",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "test-model",
                "high",
                LocalDateTime.now()
        );

        AwrDtos.SqlTuningResponse merged = SqlTuningAccuracyGuard.mergeLlm(authoritative, llm);

        assertThat(merged.rewrittenSql()).isNull();
        assertThat(merged.summary()).isEqualTo(authoritative.summary());
    }

    @Test
    void rejectsAggregateArithmeticIntroducedByAiRewrite() {
        String sql = """
                SELECT COUNT(*)
                FROM TEST.AX_ORDERS a, TEST.AX_ORDERS b
                WHERE a.status = b.status
                  AND a.status = 'CANCEL'
                """;
        AwrDtos.SqlMetricResponse metric = metric(sql, 193_385L, 61_037L, 1L, 190.7);
        AwrDtos.SqlTuningRequest request = new AwrDtos.SqlTuningRequest(
                sql, "Tune SQL", null, null, null, null
        );
        AwrDtos.SqlTuningResponse authoritative = advisor.tune(
                null, metric.sqlId(), "Tune SQL", metric, request, List.of()
        );

        for (String candidate : List.of(
                "SELECT POWER(COUNT(*), 2) FROM TEST.AX_ORDERS WHERE status = 'CANCEL'",
                "SELECT COUNT(*) * COUNT(*) FROM TEST.AX_ORDERS WHERE status = 'CANCEL'"
        )) {
            AwrDtos.SqlTuningResponse llm = new AwrDtos.SqlTuningResponse(
                    null, null, metric.sqlId(), "Tune SQL", request, metric,
                    "자기 조인을 집계식으로 변경했습니다.", List.of(), List.of(), List.of(),
                    candidate, List.of(), List.of(), List.of(), List.of(),
                    "test-model", "high", LocalDateTime.now()
            );

            AwrDtos.SqlTuningResponse merged = SqlTuningAccuracyGuard.mergeLlm(authoritative, llm);

            assertThat(merged.rewrittenSql()).isNull();
            assertThat(merged.summary()).isEqualTo(authoritative.summary());
        }
    }

    private AwrDtos.SqlMetricResponse metric(
            String sqlText,
            Long bufferGets,
            Long diskReads,
            Long executions,
            Double elapsedTimeSec
    ) {
        return new AwrDtos.SqlMetricResponse(
                "accuracy01",
                "Direct DB SQL",
                1,
                elapsedTimeSec,
                10.0,
                bufferGets,
                diskReads,
                executions,
                1_000L,
                123456789L,
                "JDBC Thin Client",
                sqlText,
                90.0,
                "Collected from target database."
        );
    }

    private AwrDtos.SqlTuningRequest request(
            String sqlText,
            String executionPlan,
            String tableName,
            Long numRows,
            String existingIndexes
    ) {
        return new AwrDtos.SqlTuningRequest(
                sqlText,
                "Tune SQL",
                executionPlan,
                tableName + " num_rows=" + numRows
                        + ", blocks=900000, avg_row_len=120, sample_size=" + numRows
                        + ", last_analyzed=2026-09-07 10:00:00",
                existingIndexes,
                ":1=100"
        );
    }
}

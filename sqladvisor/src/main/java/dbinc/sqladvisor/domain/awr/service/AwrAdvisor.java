package dbinc.sqladvisor.domain.awr.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AwrAdvisor {

    private static final Pattern SQL_ID_PATTERN = Pattern.compile("(?i)\\b([0-9a-z]{13})\\b");
    private final AtomicLong analysisSequence = new AtomicLong(1);

    public AwrDtos.AnalysisResponse analyze(
            Long reportId,
            String question,
            List<AwrDtos.SqlMetricResponse> sqlMetrics,
            List<AwrDtos.WaitEventResponse> waitEvents
    ) {
        List<AwrDtos.SqlMetricResponse> prioritizedSql = prioritizeSql(sqlMetrics);
        List<AwrDtos.FindingResponse> findings = new ArrayList<>();
        int priority = 1;

        for (AwrDtos.SqlMetricResponse metric : prioritizedSql.stream().limit(5).toList()) {
            findings.add(toFinding(priority++, metric));
        }

        String summary = buildSummary(prioritizedSql, waitEvents);
        List<String> missingInputs = List.of(
                "SQL execution plan with actual row statistics",
                "table/index DDL",
                "object statistics and last_analyzed",
                "bind variable samples",
                "ASH or SQL Monitor evidence for the same snapshot"
        );
        List<String> citations = citations(prioritizedSql, waitEvents);

        return new AwrDtos.AnalysisResponse(
                analysisSequence.getAndIncrement(),
                reportId,
                question == null || question.isBlank() ? "이 AWR에서 제일 먼저 봐야 할 병목과 SQL을 분석해줘" : question,
                summary,
                findings,
                missingInputs,
                citations,
                "rule-based-local-advisor",
                LocalDateTime.now()
        );
    }

    public AwrDtos.ChatResponse chat(
            Long reportId,
            String question,
            List<AwrDtos.SqlMetricResponse> sqlMetrics,
            List<AwrDtos.WaitEventResponse> waitEvents
    ) {
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Optional<String> sqlId = sqlIdFrom(question);
        List<AwrDtos.SqlMetricResponse> evidenceSql = sqlId
                .map(id -> sqlMetrics.stream()
                        .filter(metric -> metric.sqlId().equalsIgnoreCase(id))
                        .sorted(Comparator.comparing(AwrDtos.SqlMetricResponse::score, Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(8)
                        .toList())
                .orElseGet(() -> prioritizeSql(sqlMetrics).stream().limit(5).toList());

        List<AwrDtos.WaitEventResponse> evidenceWaits = waitEvents.stream().limit(5).toList();
        String answer;

        if (sqlId.isPresent()) {
            answer = answerForSql(sqlId.get(), evidenceSql);
        } else if (normalizedQuestion.contains("cpu")) {
            answer = answerForCpu(evidenceSql, evidenceWaits);
        } else if (normalizedQuestion.contains("i/o") || normalizedQuestion.contains("io") || normalizedQuestion.contains("read")) {
            answer = answerForIo(evidenceSql, evidenceWaits);
        } else if (normalizedQuestion.contains("wait") || normalizedQuestion.contains("대기")) {
            answer = answerForWait(evidenceWaits);
        } else {
            answer = answerForFirstLook(evidenceSql, evidenceWaits);
        }

        return new AwrDtos.ChatResponse(
                reportId,
                question,
                answer,
                citations(evidenceSql, evidenceWaits),
                evidenceSql,
                evidenceWaits,
                evidenceSql.isEmpty() && evidenceWaits.isEmpty() ? "low" : "medium"
        );
    }

    private AwrDtos.FindingResponse toFinding(int priority, AwrDtos.SqlMetricResponse metric) {
        List<String> evidence = new ArrayList<>();
        addIfPresent(evidence, "section=" + metric.sectionName());
        addIfPresent(evidence, "rank_no=" + metric.rankNo());
        addIfPresent(evidence, "elapsed_time_sec=" + metric.elapsedTimeSec());
        addIfPresent(evidence, "cpu_time_sec=" + metric.cpuTimeSec());
        addIfPresent(evidence, "buffer_gets=" + metric.bufferGets());
        addIfPresent(evidence, "disk_reads=" + metric.diskReads());
        addIfPresent(evidence, "executions=" + metric.executions());
        addIfPresent(evidence, "plan_hash_value=" + metric.planHashValue());

        List<String> likelyCauses = new ArrayList<>();
        if (metric.bufferGets() != null && metric.bufferGets() > 10_000_000) {
            likelyCauses.add("buffer gets가 높아 비효율적인 조인 순서, 낮은 선택도 조건, 인덱스 부재 가능성이 큽니다.");
            likelyCauses.add("통계정보가 오래되었거나 예상 row와 실제 row 차이가 클 수 있습니다.");
        }
        if (metric.diskReads() != null && metric.diskReads() > 100_000) {
            likelyCauses.add("physical reads가 높아 full scan, partition pruning 실패, I/O wait 병목 가능성이 있습니다.");
        }
        if (metric.cpuTimeSec() != null && metric.elapsedTimeSec() != null && metric.cpuTimeSec() > metric.elapsedTimeSec() * 0.65) {
            likelyCauses.add("elapsed time 중 CPU 비중이 높아 sort/hash join/filter 함수 비용을 확인해야 합니다.");
        }
        if (metric.executions() != null && metric.executions() > 10_000) {
            likelyCauses.add("실행 횟수가 많아 애플리케이션 반복 호출, bind 변수 사용, parse call 증가를 점검해야 합니다.");
        }
        if (likelyCauses.isEmpty()) {
            likelyCauses.add("AWR 상위 SQL에 포함되어 있어 execution plan, row estimate, wait profile 검증이 필요합니다.");
        }

        List<String> recommendedActions = List.of(
                "DBMS_XPLAN.DISPLAY_CURSOR로 actual plan과 predicate/access path를 확인합니다.",
                "AWR의 동일 SQL_ID 과거 plan hash value와 elapsed/CPU/buffer gets 변화를 비교합니다.",
                "조건절 컬럼의 인덱스 선택도와 table/index statistics 최신성을 확인합니다.",
                "SQL Monitor 또는 ASH로 CPU, I/O, concurrency wait 중 실제 지배 병목을 확인합니다.",
                "필요 시 SQL Plan Baseline, 통계 갱신, 인덱스 추가를 검증 환경에서 먼저 실험합니다."
        );
        List<String> validationSteps = List.of(
                "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR('" + metric.sqlId() + "', NULL, 'ALLSTATS LAST +PEEKED_BINDS'));",
                "DBA_HIST_SQLSTAT에서 snapshot 구간별 elapsed_delta, cpu_delta, buffer_gets_delta를 비교합니다.",
                "DBA_TAB_STATISTICS와 DBA_IND_STATISTICS의 last_analyzed, stale_stats를 확인합니다."
        );

        return new AwrDtos.FindingResponse(
                priority,
                metric.sqlId(),
                metric.sectionName() + " 상위 SQL",
                evidence,
                likelyCauses,
                recommendedActions,
                validationSteps,
                "인덱스 추가나 plan 고정은 DML 부하, 저장공간, plan regression을 유발할 수 있으므로 검증 후 적용해야 합니다.",
                confidence(metric)
        );
    }

    private String buildSummary(List<AwrDtos.SqlMetricResponse> sqlMetrics, List<AwrDtos.WaitEventResponse> waitEvents) {
        if (sqlMetrics.isEmpty() && waitEvents.isEmpty()) {
            return "구조화된 SQL metric 또는 wait event가 충분히 추출되지 않았습니다. AWR HTML/TXT 원본의 주요 섹션을 확인하거나 parser rule을 보강해야 합니다.";
        }

        StringBuilder builder = new StringBuilder();
        if (!sqlMetrics.isEmpty()) {
            AwrDtos.SqlMetricResponse top = sqlMetrics.get(0);
            builder.append("우선순위 1순위는 SQL_ID ")
                    .append(top.sqlId())
                    .append("입니다. ")
                    .append(top.sectionName())
                    .append("에서 상위에 위치하고");
            if (top.elapsedTimeSec() != null) {
                builder.append(", elapsed_time_sec=").append(top.elapsedTimeSec());
            }
            if (top.cpuTimeSec() != null) {
                builder.append(", cpu_time_sec=").append(top.cpuTimeSec());
            }
            if (top.bufferGets() != null) {
                builder.append(", buffer_gets=").append(top.bufferGets());
            }
            builder.append(" 지표가 확인됩니다. ");
        }
        if (!waitEvents.isEmpty()) {
            AwrDtos.WaitEventResponse wait = waitEvents.get(0);
            builder.append("대기 관점에서는 ")
                    .append(wait.eventName())
                    .append(" (")
                    .append(wait.waitClass())
                    .append(") 확인이 우선입니다.");
        }
        return builder.toString();
    }

    private String answerForSql(String sqlId, List<AwrDtos.SqlMetricResponse> evidenceSql) {
        if (evidenceSql.isEmpty()) {
            return "SQL_ID " + sqlId + "에 대한 구조화 metric을 찾지 못했습니다. SQL Text 또는 SQL ordered by 섹션이 추출됐는지 확인해야 합니다.";
        }
        AwrDtos.SqlMetricResponse top = evidenceSql.get(0);
        return "SQL_ID " + sqlId + "는 " + top.sectionName() + "에서 rank " + top.rankNo()
                + "로 잡혔습니다. 우선 DBMS_XPLAN으로 actual plan과 row estimate 차이를 확인하고, "
                + "buffer gets/disk reads/executions 중 가장 큰 지표를 기준으로 인덱스 선택도, 조인 순서, 통계정보 stale 여부를 검증하세요. "
                + "현재 parser 근거: " + metricLine(top);
    }

    private String answerForCpu(List<AwrDtos.SqlMetricResponse> evidenceSql, List<AwrDtos.WaitEventResponse> waitEvents) {
        String topSql = evidenceSql.stream()
                .filter(metric -> metric.cpuTimeSec() != null)
                .findFirst()
                .map(metric -> "CPU 관점 Top SQL은 " + metric.sqlId() + "이며 " + metricLine(metric) + "입니다.")
                .orElse("CPU Time 섹션에서 구조화된 SQL metric을 충분히 찾지 못했습니다.");
        boolean dbCpuHigh = waitEvents.stream().anyMatch(wait -> wait.eventName().toLowerCase(Locale.ROOT).contains("db cpu"));
        return topSql + (dbCpuHigh
                ? " DB CPU wait/event 근거도 있으므로 CPU-bound 가능성을 먼저 봅니다."
                : " DB CPU 지표가 별도로 없다면 Time Model Statistics의 DB CPU와 sql execute elapsed time 비율을 추가 확인하세요.");
    }

    private String answerForIo(List<AwrDtos.SqlMetricResponse> evidenceSql, List<AwrDtos.WaitEventResponse> waitEvents) {
        String topSql = evidenceSql.stream()
                .filter(metric -> metric.diskReads() != null)
                .findFirst()
                .map(metric -> "I/O 관점 Top SQL은 " + metric.sqlId() + "이며 " + metricLine(metric) + "입니다.")
                .orElse("Reads/User I/O 기준 SQL metric이 충분히 추출되지 않았습니다.");
        String topWait = waitEvents.stream()
                .filter(wait -> wait.waitClass().contains("I/O"))
                .findFirst()
                .map(wait -> " 관련 wait event는 " + wait.eventName() + "입니다.")
                .orElse(" wait event에서 User I/O 지배 근거는 아직 약합니다.");
        return topSql + topWait + " full scan, partition pruning, index clustering factor, storage latency를 함께 확인하세요.";
    }

    private String answerForWait(List<AwrDtos.WaitEventResponse> waitEvents) {
        if (waitEvents.isEmpty()) {
            return "Top Wait Event 섹션이 구조화되지 않았습니다. Foreground Wait Class 또는 Top 10 Foreground Events 섹션 추출 여부를 확인하세요.";
        }
        AwrDtos.WaitEventResponse wait = waitEvents.get(0);
        return "가장 먼저 볼 wait event는 " + wait.eventName() + "입니다. wait class=" + wait.waitClass()
                + ", total_wait_time_sec=" + wait.totalWaitTimeSec()
                + ", db_time_percent=" + wait.dbTimePercent()
                + " 근거가 있습니다. SQL별 대기 분포는 ASH 또는 SQL Monitor로 연결해 확인하는 것이 안전합니다.";
    }

    private String answerForFirstLook(List<AwrDtos.SqlMetricResponse> evidenceSql, List<AwrDtos.WaitEventResponse> waitEvents) {
        if (evidenceSql.isEmpty()) {
            return "현재 추출 결과에서는 Top SQL을 확정하기 어렵습니다. AWR HTML/TXT의 SQL ordered by Elapsed Time, CPU Time, Gets, Reads 섹션이 필요합니다.";
        }
        AwrDtos.SqlMetricResponse top = evidenceSql.get(0);
        String wait = waitEvents.isEmpty() ? "" : " 함께 확인할 wait event는 " + waitEvents.get(0).eventName() + "입니다.";
        return "제일 먼저 볼 SQL은 " + top.sqlId() + "입니다. " + metricLine(top)
                + " 근거로 튜닝 우선순위가 가장 높습니다." + wait
                + " 실행계획, 통계정보, bind, 과거 plan hash 변화 순서로 검증하세요.";
    }

    private List<AwrDtos.SqlMetricResponse> prioritizeSql(List<AwrDtos.SqlMetricResponse> sqlMetrics) {
        return sqlMetrics.stream()
                .sorted(Comparator.comparing(AwrDtos.SqlMetricResponse::score, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private List<String> citations(List<AwrDtos.SqlMetricResponse> sqlMetrics, List<AwrDtos.WaitEventResponse> waitEvents) {
        Set<String> citations = new LinkedHashSet<>();
        sqlMetrics.stream().limit(5).forEach(metric ->
                citations.add(metric.sectionName() + " / SQL_ID " + metric.sqlId() + " / rank " + metric.rankNo()));
        waitEvents.stream().limit(3).forEach(wait ->
                citations.add("Wait Event / " + wait.eventName() + " / class " + wait.waitClass()));
        return new ArrayList<>(citations);
    }

    private Optional<String> sqlIdFrom(String question) {
        if (question == null) {
            return Optional.empty();
        }
        Matcher matcher = SQL_ID_PATTERN.matcher(question);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        return Optional.empty();
    }

    private String metricLine(AwrDtos.SqlMetricResponse metric) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, "elapsed_time_sec=" + metric.elapsedTimeSec());
        addIfPresent(parts, "cpu_time_sec=" + metric.cpuTimeSec());
        addIfPresent(parts, "buffer_gets=" + metric.bufferGets());
        addIfPresent(parts, "disk_reads=" + metric.diskReads());
        addIfPresent(parts, "executions=" + metric.executions());
        if (parts.isEmpty()) {
            parts.add("section=" + metric.sectionName());
            parts.add("rank_no=" + metric.rankNo());
        }
        return String.join(", ", parts);
    }

    private String confidence(AwrDtos.SqlMetricResponse metric) {
        int evidenceCount = 0;
        if (metric.elapsedTimeSec() != null) {
            evidenceCount++;
        }
        if (metric.cpuTimeSec() != null) {
            evidenceCount++;
        }
        if (metric.bufferGets() != null) {
            evidenceCount++;
        }
        if (metric.diskReads() != null) {
            evidenceCount++;
        }
        if (metric.executions() != null) {
            evidenceCount++;
        }
        if (evidenceCount >= 3) {
            return "medium";
        }
        return "low";
    }

    private void addIfPresent(List<String> values, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.endsWith("=null") && !text.isBlank()) {
            values.add(text);
        }
    }
}

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
    private static final String DEFAULT_REVIEW_QUESTION =
            "이 AWR 리포트의 전체 부하 특성, 주요 대기 이벤트, 병목 의심 지점과 Top SQL 수행시간을 일반적으로 리뷰해줘";
    private final AtomicLong analysisSequence = new AtomicLong(1);

    public AwrDtos.AnalysisResponse analyze(
            Long reportId,
            String question,
            String elapsedTime,
            String dbTime,
            List<AwrDtos.SqlMetricResponse> sqlMetrics,
            List<AwrDtos.WaitEventResponse> waitEvents
    ) {
        List<AwrDtos.SqlMetricResponse> prioritizedSql = prioritizeSql(sqlMetrics);
        List<AwrDtos.FindingResponse> findings = new ArrayList<>();

        addWaitFinding(findings, waitEvents);
        addElapsedSqlFinding(findings, sqlMetrics);
        addCpuSqlFinding(findings, sqlMetrics);
        addIoSqlFinding(findings, sqlMetrics);

        if (findings.isEmpty()) {
            findings.add(new AwrDtos.FindingResponse(
                    1,
                    null,
                    "구조화된 진단 근거 부족",
                    List.of("Top SQL 또는 Top Wait Event 데이터가 충분히 추출되지 않았습니다."),
                    List.of("현재 데이터만으로는 CPU, I/O, Commit, Concurrency 병목 여부를 판단하기 어렵습니다."),
                    List.of(),
                    List.of("AWR의 Load Profile, Time Model Statistics, Top Foreground Events, SQL ordered by 섹션을 확인합니다."),
                    "파서가 필요한 섹션을 추출하지 못했을 가능성이 있습니다.",
                    "low"
            ));
        }

        String summary = buildReviewSummary(elapsedTime, dbTime, sqlMetrics, waitEvents);
        List<String> missingInputs = List.of(
                "Load Profile과 Time Model Statistics의 상세 수치",
                "DB CPU와 DB Time의 직접 비교",
                "동일 시간대 Host CPU 및 스토리지 지연 정보",
                "평상시 또는 이전 AWR 구간과의 비교",
                "필요 시 ASH 또는 SQL Monitor의 세션별 근거"
        );
        List<String> citations = citations(prioritizedSql, waitEvents);

        return new AwrDtos.AnalysisResponse(
                analysisSequence.getAndIncrement(),
                reportId,
                question == null || question.isBlank() ? DEFAULT_REVIEW_QUESTION : question,
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

    private void addWaitFinding(
            List<AwrDtos.FindingResponse> findings,
            List<AwrDtos.WaitEventResponse> waitEvents
    ) {
        Optional<AwrDtos.WaitEventResponse> candidate = waitEvents.stream()
                .max(Comparator.comparingDouble(this::waitImportance));
        if (candidate.isEmpty()) {
            return;
        }

        AwrDtos.WaitEventResponse wait = candidate.get();
        List<String> evidence = new ArrayList<>();
        addIfPresent(evidence, "event=" + wait.eventName());
        addIfPresent(evidence, "wait_class=" + wait.waitClass());
        addIfPresent(evidence, "db_time_percent=" + wait.dbTimePercent());
        addIfPresent(evidence, "total_wait_time_sec=" + wait.totalWaitTimeSec());
        addIfPresent(evidence, "avg_wait_ms=" + wait.avgWaitMs());

        findings.add(new AwrDtos.FindingResponse(
                findings.size() + 1,
                null,
                "주요 대기 이벤트: " + wait.eventName(),
                evidence,
                List.of(waitDiagnosis(wait)),
                List.of(),
                List.of(
                        "동일 시간대 DB CPU와 DB Time을 비교합니다.",
                        "해당 대기가 특정 SQL 또는 전체 세션에 분산되었는지 확인합니다.",
                        "이전 AWR 구간에도 같은 대기 이벤트가 반복되는지 비교합니다."
                ),
                "Top Wait Event만으로 개별 세션이나 특정 SQL을 원인으로 확정할 수는 없습니다.",
                wait.dbTimePercent() == null ? "medium" : "high"
        ));
    }

    private void addElapsedSqlFinding(
            List<AwrDtos.FindingResponse> findings,
            List<AwrDtos.SqlMetricResponse> sqlMetrics
    ) {
        Optional<AwrDtos.SqlMetricResponse> candidate = sqlMetrics.stream()
                .filter(metric -> metric.elapsedTimeSec() != null)
                .max(Comparator.comparingDouble(AwrDtos.SqlMetricResponse::elapsedTimeSec));
        if (candidate.isEmpty()) {
            return;
        }

        AwrDtos.SqlMetricResponse metric = candidate.get();
        Double averageElapsed = average(metric.elapsedTimeSec(), metric.executions());
        List<String> evidence = new ArrayList<>();
        addIfPresent(evidence, "section=" + metric.sectionName());
        addIfPresent(evidence, "elapsed_time_sec=" + metric.elapsedTimeSec());
        addIfPresent(evidence, "executions=" + metric.executions());
        if (averageElapsed != null) {
            evidence.add("avg_elapsed_sec=" + formatDecimal(averageElapsed));
        }

        findings.add(new AwrDtos.FindingResponse(
                findings.size() + 1,
                metric.sqlId(),
                "누적 수행시간이 높은 SQL",
                evidence,
                List.of(elapsedDiagnosis(metric, averageElapsed)),
                List.of(),
                List.of(
                        "업무 SLA 또는 정상 시간대의 평균 수행시간과 비교합니다.",
                        "동일 SQL_ID의 이전 AWR 구간에서 elapsed와 executions 변화를 비교합니다.",
                        "총 수행시간 증가가 개별 지연인지 반복 호출 증가인지 구분합니다."
                ),
                "AWR elapsed 값은 스냅샷 구간의 누적값이므로 단일 실행시간과 동일하지 않습니다.",
                averageElapsed == null ? "medium" : "high"
        ));
    }

    private void addCpuSqlFinding(
            List<AwrDtos.FindingResponse> findings,
            List<AwrDtos.SqlMetricResponse> sqlMetrics
    ) {
        Optional<AwrDtos.SqlMetricResponse> candidate = sqlMetrics.stream()
                .filter(metric -> metric.cpuTimeSec() != null)
                .max(Comparator.comparingDouble(AwrDtos.SqlMetricResponse::cpuTimeSec));
        if (candidate.isEmpty()) {
            return;
        }

        AwrDtos.SqlMetricResponse metric = candidate.get();
        Double cpuRatio = ratio(metric.cpuTimeSec(), metric.elapsedTimeSec());
        List<String> evidence = new ArrayList<>();
        addIfPresent(evidence, "section=" + metric.sectionName());
        addIfPresent(evidence, "cpu_time_sec=" + metric.cpuTimeSec());
        addIfPresent(evidence, "elapsed_time_sec=" + metric.elapsedTimeSec());
        if (cpuRatio != null) {
            evidence.add("cpu_to_elapsed_percent=" + formatDecimal(cpuRatio * 100.0));
        }

        findings.add(new AwrDtos.FindingResponse(
                findings.size() + 1,
                metric.sqlId(),
                "CPU 사용 비중이 높은 SQL",
                evidence,
                List.of(cpuDiagnosis(cpuRatio)),
                List.of(),
                List.of(
                        "AWR Time Model의 DB CPU 비중과 함께 확인합니다.",
                        "Host CPU 사용률과 run queue가 같은 시간대에 높았는지 비교합니다.",
                        "병렬 실행 여부와 실행 횟수 증가 여부를 함께 확인합니다."
                ),
                "SQL CPU 누적값만으로 시스템 전체가 CPU 병목이었다고 단정할 수는 없습니다.",
                cpuRatio == null ? "medium" : "high"
        ));
    }

    private void addIoSqlFinding(
            List<AwrDtos.FindingResponse> findings,
            List<AwrDtos.SqlMetricResponse> sqlMetrics
    ) {
        Optional<AwrDtos.SqlMetricResponse> physicalReadCandidate = sqlMetrics.stream()
                .filter(metric -> metric.diskReads() != null)
                .max(Comparator.comparingLong(AwrDtos.SqlMetricResponse::diskReads));
        Optional<AwrDtos.SqlMetricResponse> logicalReadCandidate = sqlMetrics.stream()
                .filter(metric -> metric.bufferGets() != null)
                .max(Comparator.comparingLong(AwrDtos.SqlMetricResponse::bufferGets));

        AwrDtos.SqlMetricResponse metric = physicalReadCandidate.orElseGet(() -> logicalReadCandidate.orElse(null));
        if (metric == null) {
            return;
        }

        List<String> evidence = new ArrayList<>();
        addIfPresent(evidence, "section=" + metric.sectionName());
        addIfPresent(evidence, "disk_reads=" + metric.diskReads());
        addIfPresent(evidence, "buffer_gets=" + metric.bufferGets());
        addIfPresent(evidence, "executions=" + metric.executions());

        findings.add(new AwrDtos.FindingResponse(
                findings.size() + 1,
                metric.sqlId(),
                "I/O 부하가 상대적으로 높은 SQL",
                evidence,
                List.of(ioDiagnosis(metric)),
                List.of(),
                List.of(
                        "User I/O 계열 wait event가 함께 높았는지 확인합니다.",
                        "실행당 disk reads와 buffer gets를 계산해 반복 수행과 단일 수행 부하를 구분합니다.",
                        "동일 SQL의 이전 AWR 구간과 읽기량 변화를 비교합니다."
                ),
                "읽기량이 높더라도 캐시 적중률, 데이터 처리량, 업무 특성에 따라 정상 부하일 수 있습니다.",
                "medium"
        ));
    }

    private String buildReviewSummary(
            String elapsedTime,
            String dbTime,
            List<AwrDtos.SqlMetricResponse> sqlMetrics,
            List<AwrDtos.WaitEventResponse> waitEvents
    ) {
        if (sqlMetrics.isEmpty() && waitEvents.isEmpty()) {
            return "구조화된 Top SQL 또는 Wait Event가 충분히 추출되지 않아 AWR 전반을 판단하기 어렵습니다.";
        }

        StringBuilder builder = new StringBuilder("이 결과는 특정 SQL 튜닝안이 아니라 AWR 구간의 전반적인 부하 특성을 요약한 리뷰입니다. ");
        if (elapsedTime != null && !elapsedTime.isBlank()) {
            builder.append("스냅샷 경과시간은 ").append(elapsedTime).append("이고, ");
        }
        if (dbTime != null && !dbTime.isBlank()) {
            builder.append("DB Time은 ").append(dbTime).append("입니다. ");
        }

        waitEvents.stream()
                .max(Comparator.comparingDouble(this::waitImportance))
                .ifPresent(wait -> builder.append("주요 대기 이벤트는 ")
                        .append(wait.eventName())
                        .append(" (")
                        .append(wait.waitClass())
                        .append(")입니다. "));

        sqlMetrics.stream()
                .filter(metric -> metric.elapsedTimeSec() != null)
                .max(Comparator.comparingDouble(AwrDtos.SqlMetricResponse::elapsedTimeSec))
                .ifPresent(metric -> {
                    builder.append("누적 elapsed가 가장 큰 SQL_ID는 ")
                            .append(metric.sqlId())
                            .append("이며 총 ")
                            .append(formatDecimal(metric.elapsedTimeSec()))
                            .append("초입니다. ");
                    Double averageElapsed = average(metric.elapsedTimeSec(), metric.executions());
                    if (averageElapsed != null) {
                        builder.append("단순 평균 수행시간은 약 ")
                                .append(formatDecimal(averageElapsed))
                                .append("초로 계산됩니다. ");
                    } else {
                        builder.append("실행 횟수 근거가 없어 개별 실행이 오래 걸렸는지는 확정할 수 없습니다. ");
                    }
                });

        builder.append("최종 판단은 평상시 구간, Host 지표, ASH 또는 SQL Monitor와 비교해야 합니다.");
        return builder.toString();
    }

    private String waitDiagnosis(AwrDtos.WaitEventResponse wait) {
        String waitClass = wait.waitClass() == null ? "" : wait.waitClass().toLowerCase(Locale.ROOT);
        String eventName = wait.eventName() == null ? "" : wait.eventName().toLowerCase(Locale.ROOT);
        if (eventName.contains("db cpu") || waitClass.contains("cpu")) {
            return "DB CPU 비중이 높게 나타난다면 SQL 실행 또는 동시 처리량 증가에 따른 CPU 압박 가능성을 확인해야 합니다.";
        }
        if (waitClass.contains("user i/o") || waitClass.contains("system i/o")) {
            return "스토리지 지연 또는 읽기량 증가가 응답시간에 영향을 주었을 가능성이 있습니다.";
        }
        if (waitClass.contains("commit")) {
            return "커밋 빈도 증가나 redo 처리 지연이 응답시간에 영향을 주었을 가능성이 있습니다.";
        }
        if (waitClass.contains("concurrency") || waitClass.contains("application")) {
            return "세션 간 경합 또는 공유 자원 대기가 발생했을 가능성이 있습니다.";
        }
        if (waitClass.contains("network")) {
            return "DB와 클라이언트 사이의 전송량 또는 응답 대기 특성을 확인해야 합니다.";
        }
        return "이 대기 이벤트가 일시적인 현상인지 반복되는 병목인지 이전 구간과 비교가 필요합니다.";
    }

    private String elapsedDiagnosis(AwrDtos.SqlMetricResponse metric, Double averageElapsed) {
        if (averageElapsed == null) {
            return "누적 elapsed는 높지만 executions가 없어 개별 수행시간이 긴지, 반복 실행으로 누적된 것인지 구분할 수 없습니다.";
        }
        if (averageElapsed >= 10.0) {
            return "단순 평균 기준으로 개별 실행도 오래 걸린 편으로 의심됩니다. 다만 업무 특성과 SLA 기준으로 재확인이 필요합니다.";
        }
        if (averageElapsed >= 1.0) {
            return "개별 실행시간이 짧다고 보기는 어렵지만, 정상 여부는 업무 SLA와 평상시 수치 비교가 필요합니다.";
        }
        if (metric.executions() != null && metric.executions() >= 1_000) {
            return "개별 실행은 비교적 짧지만 반복 호출이 누적 부하를 만든 유형으로 보입니다.";
        }
        return "누적 elapsed는 상위지만 단순 평균만 보면 개별 실행이 매우 오래 걸린 유형으로 단정하기는 어렵습니다.";
    }

    private String cpuDiagnosis(Double cpuRatio) {
        if (cpuRatio == null) {
            return "CPU 누적시간은 높지만 elapsed 대비 비중을 계산할 근거가 부족합니다.";
        }
        if (cpuRatio >= 0.65) {
            return "elapsed 중 CPU 비중이 높아 해당 SQL이 CPU 사용에 직접 기여했을 가능성이 있습니다.";
        }
        return "CPU 사용량은 상위지만 전체 elapsed 중 대기시간의 영향도 함께 존재할 수 있습니다.";
    }

    private String ioDiagnosis(AwrDtos.SqlMetricResponse metric) {
        if (metric.diskReads() != null && metric.diskReads() > 0) {
            return "physical reads가 상대적으로 높아 스토리지 I/O 또는 대량 데이터 접근 부하 가능성이 있습니다.";
        }
        return "buffer gets가 상대적으로 높아 메모리 내 논리 읽기와 데이터 처리량이 큰 SQL로 보입니다.";
    }

    private double waitImportance(AwrDtos.WaitEventResponse wait) {
        if (wait.dbTimePercent() != null) {
            return wait.dbTimePercent();
        }
        return wait.totalWaitTimeSec() == null ? 0.0 : wait.totalWaitTimeSec();
    }

    private Double average(Double total, Long count) {
        if (total == null || count == null || count <= 0) {
            return null;
        }
        return total / count;
    }

    private Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator <= 0) {
            return null;
        }
        return numerator / denominator;
    }

    private String formatDecimal(Double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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

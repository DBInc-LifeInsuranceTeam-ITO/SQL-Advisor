package dbinc.sqladvisor.domain.awr.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AwrParser {

    private static final Pattern SQL_ID_PATTERN = Pattern.compile("(?i)\\b([0-9a-z]{13})\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");
    private static final int PREVIEW_LIMIT = 6000;

    private static final List<String> SECTION_MARKERS = List.of(
            "Report Header",
            "Report Summary",
            "Load Profile",
            "Instance Efficiency Percentages",
            "Top 10 Foreground Events by Total Wait Time",
            "Foreground Wait Class",
            "Wait Classes by Total Wait Time",
            "Time Model Statistics",
            "SQL ordered by Elapsed Time",
            "SQL ordered by CPU Time",
            "SQL ordered by Gets",
            "SQL ordered by Reads",
            "SQL ordered by Executions",
            "SQL ordered by Parse Calls",
            "SQL ordered by Cluster Wait Time",
            "SQL ordered by User I/O Wait Time",
            "SQL ordered by IO Wait Time",
            "SQL Text",
            "Complete List of SQL Text",
            "SQL Plan Statistics",
            "Execution Plan"
    );

    public ExtractedText extractText(String filename, byte[] bytes) {
        String extension = extensionOf(filename);
        String decoded = decode(bytes);
        if ("html".equals(extension) || "htm".equals(extension)) {
            Document document = Jsoup.parse(decoded);
            List<String> lines = new ArrayList<>();

            for (Element heading : document.select("h1,h2,h3,h4,b,font")) {
                String text = clean(heading.text());
                if (isUsefulLine(text)) {
                    lines.add(text);
                }
            }
            for (Element row : document.select("tr")) {
                String text = clean(row.text());
                if (isUsefulLine(text)) {
                    lines.add(text);
                }
            }
            String bodyText = clean(document.body() == null ? document.text() : document.body().wholeText());
            if (bodyText.length() > lines.stream().mapToInt(String::length).sum()) {
                lines.add(bodyText);
            }

            return new ExtractedText(String.join("\n", deduplicate(lines)), List.of("HTML table/text extraction completed"));
        }

        if ("txt".equals(extension) || "text".equals(extension) || "log".equals(extension)) {
            return new ExtractedText(decoded, List.of("TXT extraction completed"));
        }

        if ("pdf".equals(extension)) {
            return new ExtractedText(
                    "PDF file was uploaded. Text extraction dependency is not enabled in this Java POC, so upload AWR HTML/TXT for structured parsing or add a PDFBox/OCR worker adapter.",
                    List.of("PDF extraction placeholder used", "OCR fallback should run in the background worker in production")
            );
        }

        return new ExtractedText(decoded, List.of("Generic text extraction completed"));
    }

    public ParsedAwr parse(String filename, String rawText) {
        String normalized = normalize(rawText);
        List<AwrDtos.SectionResponse> sections = splitSections(normalized);
        Header header = parseHeader(filename, normalized, sections);
        List<AwrDtos.WaitEventResponse> waitEvents = parseWaitEvents(sections);
        List<AwrDtos.SqlMetricResponse> sqlMetrics = parseSqlMetrics(sections);

        if (sections.isEmpty()) {
            sections = List.of(new AwrDtos.SectionResponse(
                    "Raw Text",
                    1,
                    preview(normalized),
                    Map.of("chunkType", "summary", "parser", "fallback")
            ));
        }

        return new ParsedAwr(header, sections, sqlMetrics, waitEvents, preview(normalized));
    }

    private Header parseHeader(String filename, String rawText, List<AwrDtos.SectionResponse> sections) {
        String headerText = sections.stream()
                .filter(section -> section.sectionOrder() <= 2 || section.sectionName().toLowerCase(Locale.ROOT).contains("header"))
                .map(AwrDtos.SectionResponse::rawText)
                .findFirst()
                .orElse(rawText.substring(0, Math.min(rawText.length(), 3000)));

        return new Header(
                firstMatch(headerText, "(?i)(?:DB Name|Database Name)\\s*[:=]?\\s*([A-Za-z0-9_$#.-]{2,})")
                        .filter(value -> !"DB".equalsIgnoreCase(value) && !"Name".equalsIgnoreCase(value))
                        .orElse(deriveDbName(filename)),
                firstMatch(headerText, "(?i)(?:Instance Name|Instance)\\s*[:=]?\\s*([A-Za-z0-9_$#.-]{2,})")
                        .filter(value -> !"Instance".equalsIgnoreCase(value))
                        .orElse("unknown"),
                firstMatch(headerText, "(?i)(?:Begin Snap|Snap Begin|Begin Snapshot)\\s*[:=]?\\s*([^\\n]{4,80})").orElse(null),
                firstMatch(headerText, "(?i)(?:End Snap|Snap End|End Snapshot)\\s*[:=]?\\s*([^\\n]{4,80})").orElse(null),
                firstMatch(rawText, "(?i)Elapsed\\s*:?\\s*([0-9,.]+\\s*(?:mins?|minutes?|sec|seconds?|hrs?|hours?)?)").orElse(null),
                firstMatch(rawText, "(?i)DB Time\\s*:?\\s*([0-9,.]+\\s*(?:mins?|minutes?|sec|seconds?|hrs?|hours?)?)").orElse(null)
        );
    }

    private List<AwrDtos.SectionResponse> splitSections(String rawText) {
        List<String> lines = rawText.lines().toList();
        List<AwrDtos.SectionResponse> sections = new ArrayList<>();
        String currentName = null;
        List<String> currentLines = new ArrayList<>();
        int order = 1;

        for (String line : lines) {
            String cleaned = clean(line);
            Optional<String> marker = findSectionMarker(cleaned);
            if (marker.isPresent() && shouldStartNewSection(currentName, marker.get())) {
                if (currentName != null && !currentLines.isEmpty()) {
                    sections.add(toSection(currentName, order++, currentLines));
                }
                currentName = marker.get();
                currentLines = new ArrayList<>();
                currentLines.add(cleaned);
            } else if (currentName != null) {
                currentLines.add(line);
            }
        }

        if (currentName != null && !currentLines.isEmpty()) {
            sections.add(toSection(currentName, order, currentLines));
        }

        return sections;
    }

    private AwrDtos.SectionResponse toSection(String sectionName, int order, List<String> lines) {
        String rawText = preview(String.join("\n", lines).trim());
        return new AwrDtos.SectionResponse(
                sectionName,
                order,
                rawText,
                Map.of(
                        "chunkType", chunkTypeOf(sectionName),
                        "source", "awr-parser",
                        "lineCount", lines.size()
                )
        );
    }

    private List<AwrDtos.WaitEventResponse> parseWaitEvents(List<AwrDtos.SectionResponse> sections) {
        List<AwrDtos.WaitEventResponse> waitEvents = new ArrayList<>();
        for (AwrDtos.SectionResponse section : sections) {
            String name = section.sectionName().toLowerCase(Locale.ROOT);
            if (!name.contains("wait") && !name.contains("foreground event")) {
                continue;
            }

            for (String line : section.rawText().split("\\R")) {
                if (line.length() < 12 || looksLikeHeader(line)) {
                    continue;
                }
                List<Double> numbers = numbers(line);
                if (numbers.size() < 2) {
                    continue;
                }
                String eventName = removeNumbers(line).trim();
                eventName = eventName.replaceAll("\\s{2,}", " ");
                if (!isUsefulLine(eventName) || eventName.length() < 3) {
                    continue;
                }
                Double totalWait = numbers.get(0);
                Double avgWait = numbers.size() > 1 ? numbers.get(1) : null;
                Double dbTimePercent = numbers.get(numbers.size() - 1);
                waitEvents.add(new AwrDtos.WaitEventResponse(
                        inferWaitClass(eventName),
                        eventName,
                        totalWait,
                        avgWait,
                        dbTimePercent
                ));
            }
        }

        return waitEvents.stream()
                .sorted(Comparator.comparing(AwrDtos.WaitEventResponse::dbTimePercent, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(12)
                .toList();
    }

    private List<AwrDtos.SqlMetricResponse> parseSqlMetrics(List<AwrDtos.SectionResponse> sections) {
        List<AwrDtos.SqlMetricResponse> sqlMetrics = new ArrayList<>();
        for (AwrDtos.SectionResponse section : sections) {
            if (!section.sectionName().toLowerCase(Locale.ROOT).startsWith("sql ordered by")) {
                continue;
            }

            int rank = 1;
            List<AwrDtos.SqlMetricResponse> sectionMetrics = new ArrayList<>();
            for (String line : section.rawText().split("\\R")) {
                Matcher matcher = SQL_ID_PATTERN.matcher(line);
                if (!matcher.find() || looksLikeHeader(line)) {
                    continue;
                }
                String sqlId = matcher.group(1).toLowerCase(Locale.ROOT);
                String beforeSqlId = line.substring(0, matcher.start());
                String afterSqlId = line.substring(matcher.end()).trim();
                List<Double> nums = numbers(beforeSqlId);
                if (nums.isEmpty()) {
                    continue;
                }
                MetricValues values = mapMetricValues(section.sectionName(), nums);
                String sqlText = afterSqlId.isBlank() ? null : afterSqlId;
                Double score = score(values, rank);
                sectionMetrics.add(new AwrDtos.SqlMetricResponse(
                        sqlId,
                        section.sectionName(),
                        rank++,
                        values.elapsedTimeSec(),
                        values.cpuTimeSec(),
                        values.bufferGets(),
                        values.diskReads(),
                        values.executions(),
                        values.rowsProcessed(),
                        values.planHashValue(),
                        null,
                        sqlText,
                        score,
                        interpretationHint(values, section.sectionName())
                ));
            }
            if (sectionMetrics.isEmpty()) {
                sectionMetrics.addAll(parseVerticalSqlRows(section, rank));
            }
            sqlMetrics.addAll(sectionMetrics);
        }

        return sqlMetrics.stream()
                .sorted(Comparator.comparing(AwrDtos.SqlMetricResponse::score, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(50)
                .toList();
    }

    private List<AwrDtos.SqlMetricResponse> parseVerticalSqlRows(AwrDtos.SectionResponse section, int initialRank) {
        List<String> lines = section.rawText().lines()
                .map(this::clean)
                .filter(this::isUsefulLine)
                .toList();
        List<AwrDtos.SqlMetricResponse> rows = new ArrayList<>();
        boolean hasSqlModule = lines.stream().anyMatch(line -> "SQL Module".equalsIgnoreCase(line));
        int rank = initialRank;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!isSqlIdLine(line)) {
                continue;
            }

            String sqlId = line.toLowerCase(Locale.ROOT);
            List<Double> nums = numbersBefore(lines, index);
            if (nums.isEmpty()) {
                continue;
            }

            int textStart = index + 1;
            if (hasSqlModule && textStart < lines.size() && !looksLikeSqlText(lines.get(textStart))) {
                textStart++;
            }
            String sqlText = collectSqlText(lines, textStart, nums.size());
            MetricValues values = mapMetricValues(section.sectionName(), nums);
            Double score = score(values, rank);
            rows.add(new AwrDtos.SqlMetricResponse(
                    sqlId,
                    section.sectionName(),
                    rank++,
                    values.elapsedTimeSec(),
                    values.cpuTimeSec(),
                    values.bufferGets(),
                    values.diskReads(),
                    values.executions(),
                    values.rowsProcessed(),
                    values.planHashValue(),
                    null,
                    sqlText.isBlank() ? null : sqlText,
                    score,
                    interpretationHint(values, section.sectionName())
            ));
        }

        return rows;
    }

    private MetricValues mapMetricValues(String sectionName, List<Double> nums) {
        String normalizedName = sectionName.toLowerCase(Locale.ROOT);
        Double first = nums.size() > 0 ? nums.get(0) : null;
        Double second = nums.size() > 1 ? nums.get(1) : null;
        Double third = nums.size() > 2 ? nums.get(2) : null;
        Long planHash = nums.size() > 3 && nums.get(nums.size() - 1) > 10_000_000
                ? nums.get(nums.size() - 1).longValue()
                : null;

        Double elapsed = null;
        Double cpu = null;
        Long gets = null;
        Long reads = null;
        Long executions = null;
        Long rows = null;

        if (normalizedName.contains("elapsed")) {
            elapsed = first;
            executions = toLong(second);
        } else if (normalizedName.contains("cpu")) {
            cpu = first;
            elapsed = second;
            executions = toLong(third);
        } else if (normalizedName.contains("gets")) {
            gets = toLong(first);
            executions = toLong(second);
        } else if (normalizedName.contains("reads") || normalizedName.contains("i/o") || normalizedName.contains("io ")) {
            reads = toLong(first);
            executions = toLong(second);
        } else if (normalizedName.contains("executions")) {
            executions = toLong(first);
            rows = toLong(second);
        } else if (normalizedName.contains("parse")) {
            executions = toLong(first);
        }

        return new MetricValues(elapsed, cpu, gets, reads, executions, rows, planHash);
    }

    private Double score(MetricValues values, int rank) {
        double score = 100.0 / Math.max(rank, 1);
        if (values.elapsedTimeSec() != null) {
            score += values.elapsedTimeSec() * 0.5;
        }
        if (values.cpuTimeSec() != null) {
            score += values.cpuTimeSec() * 0.35;
        }
        if (values.bufferGets() != null) {
            score += values.bufferGets() / 1_000_000.0;
        }
        if (values.diskReads() != null) {
            score += values.diskReads() / 100_000.0;
        }
        if (values.executions() != null && values.executions() > 10_000) {
            score += Math.log10(values.executions());
        }
        return Math.round(score * 10.0) / 10.0;
    }

    private String interpretationHint(MetricValues values, String sectionName) {
        if (values.bufferGets() != null && values.bufferGets() > 10_000_000) {
            return "High buffer gets. Check join order, index selectivity, stale statistics, and row estimate mismatch.";
        }
        if (values.diskReads() != null && values.diskReads() > 100_000) {
            return "High physical reads. Check full scans, partition pruning, object statistics, and storage wait profile.";
        }
        if (values.cpuTimeSec() != null && values.elapsedTimeSec() != null && values.cpuTimeSec() > values.elapsedTimeSec() * 0.7) {
            return "CPU-heavy SQL. Check filtering, sorting, hash joins, functions on predicates, and execution count.";
        }
        if (values.executions() != null && values.executions() > 10_000) {
            return "High execution count. Check application loop, bind variable usage, parse calls, and per-exec elapsed time.";
        }
        return "Prioritized from " + sectionName + ". Validate with DBMS_XPLAN, ASH/SQL Monitor, and object statistics.";
    }

    private Optional<String> findSectionMarker(String line) {
        if (!isUsefulLine(line) || line.length() > 120) {
            return Optional.empty();
        }
        String normalizedLine = normalizeForCompare(line);
        return SECTION_MARKERS.stream()
                .filter(marker -> isSectionTitle(normalizedLine, marker))
                .findFirst();
    }

    private boolean shouldStartNewSection(String currentName, String marker) {
        return currentName == null
                || !"SQL Text".equals(marker)
                || !currentName.toLowerCase(Locale.ROOT).startsWith("sql ordered by");
    }

    private boolean isSectionTitle(String normalizedLine, String marker) {
        String normalizedMarker = normalizeForCompare(marker);
        if ("sql text".equals(normalizedMarker)) {
            return normalizedLine.equals(normalizedMarker);
        }
        if ("complete list of sql text".equals(normalizedMarker)) {
            return normalizedLine.contains(normalizedMarker);
        }
        return normalizedLine.contains(normalizedMarker);
    }

    private String chunkTypeOf(String sectionName) {
        String name = sectionName.toLowerCase(Locale.ROOT);
        if (name.contains("time model")) {
            return "time_model";
        }
        if (name.contains("wait")) {
            return "wait_event";
        }
        if (name.startsWith("sql ordered")) {
            return "sql_metric_row";
        }
        if (name.contains("sql text")) {
            return "sql_text";
        }
        if (name.contains("plan")) {
            return "execution_plan";
        }
        return "summary";
    }

    private String inferWaitClass(String eventName) {
        String lower = eventName.toLowerCase(Locale.ROOT);
        if (lower.contains("db cpu")) {
            return "CPU";
        }
        if (lower.contains("read") || lower.contains("write") || lower.contains("file")) {
            return "User I/O";
        }
        if (lower.contains("log file")) {
            return "Commit";
        }
        if (lower.contains("gc ") || lower.contains("global cache")) {
            return "Cluster";
        }
        if (lower.contains("latch") || lower.contains("mutex") || lower.contains("enqueue")) {
            return "Concurrency";
        }
        return "Other";
    }

    private List<String> deduplicate(List<String> lines) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            if (isUsefulLine(line)) {
                seen.add(line);
            }
        }
        return new ArrayList<>(seen);
    }

    private boolean isUsefulLine(String value) {
        return value != null && !value.isBlank() && value.trim().length() > 2;
    }

    private boolean looksLikeHeader(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("sql id")
                || lower.contains("elapsed time")
                || lower.contains("event waits")
                || lower.contains("wait event")
                || lower.contains("total wait")
                || lower.contains("executions")
                || lower.contains("buffer gets")
                || lower.contains("physical reads")
                || lower.startsWith("---");
    }

    private boolean isSqlIdLine(String line) {
        return SQL_ID_PATTERN.matcher(line).matches();
    }

    private boolean isNumericLine(String line) {
        return !numbers(line).isEmpty() && removeNumbers(line).isBlank();
    }

    private boolean isControlLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("back to ") || lower.startsWith("sql ordered by ");
    }

    private boolean looksLikeSqlText(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("select ")
                || lower.startsWith("insert ")
                || lower.startsWith("update ")
                || lower.startsWith("delete ")
                || lower.startsWith("merge ")
                || lower.startsWith("with ")
                || lower.startsWith("begin ")
                || lower.startsWith("call ")
                || lower.contains("/*");
    }

    private List<Double> numbersBefore(List<String> lines, int index) {
        List<Double> nums = new ArrayList<>();
        for (int cursor = index - 1; cursor >= 0; cursor--) {
            String line = lines.get(cursor);
            if (!isNumericLine(line)) {
                break;
            }
            nums.add(0, numbers(line).get(0));
        }
        return nums;
    }

    private String collectSqlText(List<String> lines, int start, int metricCount) {
        List<String> parts = new ArrayList<>();
        for (int cursor = start; cursor < lines.size(); cursor++) {
            String line = lines.get(cursor);
            if (isControlLine(line) || isRecordStart(lines, cursor, metricCount)) {
                break;
            }
            if (!isSqlIdLine(line)) {
                parts.add(line);
            }
        }
        return String.join(" ", parts).replaceAll("\\s+", " ").trim();
    }

    private boolean isRecordStart(List<String> lines, int index, int metricCount) {
        if (metricCount < 1 || index + metricCount >= lines.size()) {
            return false;
        }
        for (int offset = 0; offset < metricCount; offset++) {
            if (!isNumericLine(lines.get(index + offset))) {
                return false;
            }
        }
        return isSqlIdLine(lines.get(index + metricCount));
    }

    private List<Double> numbers(String text) {
        List<Double> values = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            values.add(toDouble(matcher.group()));
        }
        return values;
    }

    private String removeNumbers(String text) {
        return NUMBER_PATTERN.matcher(text).replaceAll(" ");
    }

    private Double toDouble(String value) {
        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long toLong(Double value) {
        return value == null ? null : value.longValue();
    }

    private Optional<String> firstMatch(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        if (matcher.find()) {
            return Optional.ofNullable(clean(matcher.group(1)));
        }
        return Optional.empty();
    }

    private String deriveDbName(String filename) {
        String base = filename == null ? "AWR" : filename.replaceFirst("\\.[^.]+$", "");
        return base.replaceAll("[^A-Za-z0-9_$#.-]", "_");
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String normalizeForCompare(String text) {
        return clean(text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ");
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').trim().replaceAll("[ \\t]+", " ");
    }

    private String preview(String value) {
        if (value == null || value.length() <= PREVIEW_LIMIT) {
            return value;
        }
        return value.substring(0, PREVIEW_LIMIT) + "\n... truncated ...";
    }

    private String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') < 0) {
            return utf8;
        }
        return new String(bytes, Charset.forName("MS949"));
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    public record ExtractedText(String text, List<String> warnings) {
    }

    public record ParsedAwr(
            Header header,
            List<AwrDtos.SectionResponse> sections,
            List<AwrDtos.SqlMetricResponse> sqlMetrics,
            List<AwrDtos.WaitEventResponse> waitEvents,
            String rawTextPreview
    ) {
    }

    public record Header(
            String dbName,
            String instanceName,
            String snapBegin,
            String snapEnd,
            String elapsedTime,
            String dbTime
    ) {
    }

    private record MetricValues(
            Double elapsedTimeSec,
            Double cpuTimeSec,
            Long bufferGets,
            Long diskReads,
            Long executions,
            Long rowsProcessed,
            Long planHashValue
    ) {
    }
}

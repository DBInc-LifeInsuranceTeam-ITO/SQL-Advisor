package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@DependsOn("authRepository")
public class AwrRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AwrRepository(@Qualifier("primaryDataSource") DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS awr_report (
                    id BIGSERIAL PRIMARY KEY,
                    filename TEXT NOT NULL,
                    db_name TEXT,
                    instance_name TEXT,
                    snap_begin TEXT,
                    snap_end TEXT,
                    elapsed_time TEXT,
                    db_time TEXT,
                    raw_file_path TEXT,
                    text_path TEXT,
                    raw_text_preview TEXT,
                    status TEXT NOT NULL,
                    warnings JSONB DEFAULT '[]'::jsonb,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        addColumnIfMissing("awr_report", "raw_text_preview", "TEXT");
        addColumnIfMissing("awr_report", "warnings", "JSONB DEFAULT '[]'::jsonb");
        addColumnIfMissing("awr_report", "uploaded_by", "BIGINT REFERENCES app_user(id) ON DELETE SET NULL");
        addColumnIfMissing("awr_report", "visibility", "TEXT NOT NULL DEFAULT 'SHARED'");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS awr_section (
                    id BIGSERIAL PRIMARY KEY,
                    report_id BIGINT REFERENCES awr_report(id) ON DELETE CASCADE,
                    section_name TEXT NOT NULL,
                    section_order INTEGER NOT NULL,
                    raw_text TEXT,
                    parsed_json JSONB
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS awr_sql_metric (
                    id BIGSERIAL PRIMARY KEY,
                    report_id BIGINT REFERENCES awr_report(id) ON DELETE CASCADE,
                    sql_id TEXT NOT NULL,
                    section_name TEXT NOT NULL,
                    rank_no INTEGER,
                    elapsed_time NUMERIC,
                    cpu_time NUMERIC,
                    buffer_gets BIGINT,
                    disk_reads BIGINT,
                    executions BIGINT,
                    rows_processed BIGINT,
                    plan_hash_value BIGINT,
                    module TEXT,
                    sql_text TEXT,
                    score NUMERIC,
                    interpretation_hint TEXT
                )
                """);
        addColumnIfMissing("awr_sql_metric", "score", "NUMERIC");
        addColumnIfMissing("awr_sql_metric", "interpretation_hint", "TEXT");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS awr_wait_event (
                    id BIGSERIAL PRIMARY KEY,
                    report_id BIGINT REFERENCES awr_report(id) ON DELETE CASCADE,
                    wait_class TEXT,
                    event_name TEXT NOT NULL,
                    total_wait_time_sec NUMERIC,
                    avg_wait_ms NUMERIC,
                    db_time_percent NUMERIC
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_chunk (
                    id BIGSERIAL PRIMARY KEY,
                    report_id BIGINT REFERENCES awr_report(id) ON DELETE CASCADE,
                    section_name TEXT,
                    sql_id TEXT,
                    chunk_text TEXT NOT NULL,
                    chunk_type TEXT NOT NULL,
                    metric_json JSONB,
                    embedding VECTOR(1536),
                    embedding_provider TEXT,
                    embedding_model TEXT,
                    embedding_dimension INTEGER,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        addColumnIfMissing("rag_chunk", "embedding_provider", "TEXT");
        addColumnIfMissing("rag_chunk", "embedding_model", "TEXT");
        addColumnIfMissing("rag_chunk", "embedding_dimension", "INTEGER");
        addColumnIfMissing("rag_chunk", "created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS analysis_result (
                    id BIGSERIAL PRIMARY KEY,
                    report_id BIGINT REFERENCES awr_report(id) ON DELETE CASCADE,
                    question TEXT,
                    answer_json JSONB NOT NULL,
                    result_type TEXT DEFAULT 'analysis',
                    model TEXT,
                    citations JSONB,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        addColumnIfMissing("analysis_result", "result_type", "TEXT DEFAULT 'analysis'");
        addColumnIfMissing("analysis_result", "user_id", "BIGINT REFERENCES app_user(id) ON DELETE CASCADE");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS feedback (
                    id BIGSERIAL PRIMARY KEY,
                    analysis_id BIGINT REFERENCES analysis_result(id) ON DELETE CASCADE,
                    rating INTEGER,
                    comment TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        addColumnIfMissing("feedback", "user_id", "BIGINT REFERENCES app_user(id) ON DELETE SET NULL");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS awr_ai_setting (
                    setting_key TEXT PRIMARY KEY,
                    setting_value TEXT,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        addColumnIfMissing("awr_ai_setting", "updated_by", "BIGINT REFERENCES app_user(id) ON DELETE SET NULL");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_awr_sql_metric_report_sql ON awr_sql_metric(report_id, sql_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_awr_wait_event_report ON awr_wait_event(report_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunk_report_sql ON rag_chunk(report_id, sql_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunk_report_type ON rag_chunk(report_id, chunk_type)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_awr_report_uploaded_by ON awr_report(uploaded_by)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_awr_report_visibility_owner ON awr_report(visibility, uploaded_by, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_analysis_result_user_chat ON analysis_result(user_id, report_id, result_type, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_feedback_user ON feedback(user_id)");
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding ON rag_chunk USING ivfflat (embedding vector_cosine_ops)");
        } catch (RuntimeException exception) {
            log.warn("pgvector index creation skipped: {}", exception.getMessage());
        }
    }

    public long createReport(String filename, Long uploadedBy, String visibility) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO awr_report(filename, uploaded_by, visibility, status, warnings) VALUES (?, ?, ?, ?, ?::jsonb)",
                    new String[]{"id"}
            );
            statement.setString(1, filename);
            if (uploadedBy == null) {
                statement.setObject(2, null);
            } else {
                statement.setLong(2, uploadedBy);
            }
            statement.setString(3, visibility == null || visibility.isBlank() ? "SHARED" : visibility);
            statement.setString(4, "UPLOADING");
            statement.setString(5, "[]");
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Map<String, String> findAiSettings() {
        return jdbcTemplate.query("""
                        SELECT setting_key, setting_value
                          FROM awr_ai_setting
                         ORDER BY setting_key
                        """,
                rs -> {
                    Map<String, String> settings = new LinkedHashMap<>();
                    while (rs.next()) {
                        settings.put(rs.getString("setting_key"), rs.getString("setting_value"));
                    }
                    return settings;
                }
        );
    }

    public void upsertAiSetting(String key, String value) {
        jdbcTemplate.update("""
                        INSERT INTO awr_ai_setting(setting_key, setting_value, updated_at)
                        VALUES (?, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (setting_key)
                        DO UPDATE SET setting_value = EXCLUDED.setting_value,
                                      updated_at = CURRENT_TIMESTAMP
                        """,
                key,
                value
        );
    }

    public void deleteAiSetting(String key) {
        jdbcTemplate.update("DELETE FROM awr_ai_setting WHERE setting_key = ?", key);
    }

    public void updateReport(long reportId, AwrParser.Header header, String status, String rawFilePath,
                             String textPath, String rawTextPreview, List<String> warnings) {
        jdbcTemplate.update("""
                        UPDATE awr_report
                           SET db_name = ?,
                               instance_name = ?,
                               snap_begin = ?,
                               snap_end = ?,
                               elapsed_time = ?,
                               db_time = ?,
                               raw_file_path = ?,
                               text_path = ?,
                               raw_text_preview = ?,
                               status = ?,
                               warnings = ?::jsonb
                         WHERE id = ?
                        """,
                header.dbName(),
                header.instanceName(),
                header.snapBegin(),
                header.snapEnd(),
                header.elapsedTime(),
                header.dbTime(),
                rawFilePath,
                textPath,
                rawTextPreview,
                status,
                toJson(warnings),
                reportId
        );
    }

    public void updateReportQueued(long reportId, String rawFilePath, List<String> warnings) {
        jdbcTemplate.update("""
                        UPDATE awr_report
                           SET raw_file_path = ?,
                               status = ?,
                               warnings = ?::jsonb
                         WHERE id = ?
                        """,
                rawFilePath,
                "QUEUED",
                toJson(warnings),
                reportId
        );
    }

    public void updateReportStatus(long reportId, String status, List<String> warnings) {
        jdbcTemplate.update("""
                        UPDATE awr_report
                           SET status = ?,
                               warnings = ?::jsonb
                         WHERE id = ?
                        """,
                status,
                toJson(warnings),
                reportId
        );
    }

    public void replaceSections(long reportId, List<AwrDtos.SectionResponse> sections) {
        jdbcTemplate.update("DELETE FROM awr_section WHERE report_id = ?", reportId);
        for (AwrDtos.SectionResponse section : sections) {
            jdbcTemplate.update("""
                            INSERT INTO awr_section(report_id, section_name, section_order, raw_text, parsed_json)
                            VALUES (?, ?, ?, ?, ?::jsonb)
                            """,
                    reportId,
                    section.sectionName(),
                    section.sectionOrder(),
                    section.rawText(),
                    toJson(section.parsedJson())
            );
        }
    }

    public void replaceSqlMetrics(long reportId, List<AwrDtos.SqlMetricResponse> metrics) {
        jdbcTemplate.update("DELETE FROM awr_sql_metric WHERE report_id = ?", reportId);
        for (AwrDtos.SqlMetricResponse metric : metrics) {
            jdbcTemplate.update("""
                            INSERT INTO awr_sql_metric(
                                report_id, sql_id, section_name, rank_no, elapsed_time, cpu_time,
                                buffer_gets, disk_reads, executions, rows_processed, plan_hash_value,
                                module, sql_text, score, interpretation_hint
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    reportId,
                    metric.sqlId(),
                    metric.sectionName(),
                    metric.rankNo(),
                    metric.elapsedTimeSec(),
                    metric.cpuTimeSec(),
                    metric.bufferGets(),
                    metric.diskReads(),
                    metric.executions(),
                    metric.rowsProcessed(),
                    metric.planHashValue(),
                    metric.module(),
                    metric.sqlText(),
                    metric.score(),
                    metric.interpretationHint()
            );
        }
    }

    public void replaceWaitEvents(long reportId, List<AwrDtos.WaitEventResponse> waitEvents) {
        jdbcTemplate.update("DELETE FROM awr_wait_event WHERE report_id = ?", reportId);
        for (AwrDtos.WaitEventResponse waitEvent : waitEvents) {
            jdbcTemplate.update("""
                            INSERT INTO awr_wait_event(
                                report_id, wait_class, event_name, total_wait_time_sec, avg_wait_ms, db_time_percent
                            )
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    reportId,
                    waitEvent.waitClass(),
                    waitEvent.eventName(),
                    waitEvent.totalWaitTimeSec(),
                    waitEvent.avgWaitMs(),
                    waitEvent.dbTimePercent()
            );
        }
    }

    public void replaceChunks(long reportId, List<AwrRagChunk> chunks, AwrAiClient.EmbeddingResult embeddingResult) {
        jdbcTemplate.update("DELETE FROM rag_chunk WHERE report_id = ?", reportId);
        for (AwrRagChunk chunk : chunks) {
            String vector = chunk.metricJson().containsKey("_embedding")
                    ? String.valueOf(chunk.metricJson().get("_embedding"))
                    : null;
            Map<String, Object> cleanMetricJson = new LinkedHashMap<>(chunk.metricJson());
            cleanMetricJson.remove("_embedding");
            if (vector == null) {
                jdbcTemplate.update("""
                                INSERT INTO rag_chunk(report_id, section_name, sql_id, chunk_text, chunk_type, metric_json)
                                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                                """,
                        reportId,
                        chunk.sectionName(),
                        chunk.sqlId(),
                        chunk.chunkText(),
                        chunk.chunkType(),
                        toJson(cleanMetricJson)
                );
            } else {
                jdbcTemplate.update("""
                                INSERT INTO rag_chunk(
                                    report_id, section_name, sql_id, chunk_text, chunk_type, metric_json,
                                    embedding, embedding_provider, embedding_model, embedding_dimension
                                )
                                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::vector, ?, ?, ?)
                                """,
                        reportId,
                        chunk.sectionName(),
                        chunk.sqlId(),
                        chunk.chunkText(),
                        chunk.chunkType(),
                        toJson(cleanMetricJson),
                        vector,
                        embeddingResult.provider(),
                        embeddingResult.model(),
                        embeddingResult.dimension()
                );
            }
        }
    }

    public List<AwrDtos.ReportSummaryResponse> listReports(Long userId, boolean includePrivateReports) {
        return jdbcTemplate.query("""
                        SELECT r.*,
                               (SELECT count(*) FROM awr_section s WHERE s.report_id = r.id) section_count,
                               (SELECT count(*) FROM awr_sql_metric m WHERE m.report_id = r.id) top_sql_count,
                               (SELECT count(*) FROM awr_wait_event w WHERE w.report_id = r.id) wait_event_count
                          FROM awr_report r
                         WHERE r.visibility = 'SHARED'
                            OR ?
                            OR (?::bigint IS NULL AND r.uploaded_by IS NULL)
                            OR r.uploaded_by = ?::bigint
                         ORDER BY r.created_at DESC, r.id DESC
                        """, (rs, rowNum) -> new AwrDtos.ReportSummaryResponse(
                        rs.getLong("id"),
                        rs.getString("filename"),
                        rs.getString("db_name"),
                        rs.getString("instance_name"),
                        rs.getString("snap_begin"),
                        rs.getString("snap_end"),
                        rs.getString("elapsed_time"),
                        rs.getString("db_time"),
                        rs.getString("status"),
                        toLocalDateTime(rs.getTimestamp("created_at")),
                        getLong(rs.getObject("uploaded_by")),
                        rs.getString("visibility"),
                        rs.getInt("section_count"),
                        rs.getInt("top_sql_count"),
                        rs.getInt("wait_event_count")
                ),
                includePrivateReports,
                userId,
                userId
        );
    }

    public Optional<ReportRecord> findReport(long reportId) {
        List<ReportRecord> records = jdbcTemplate.query("""
                        SELECT *
                          FROM awr_report
                         WHERE id = ?
                        """,
                reportMapper(),
                reportId
        );
        return records.stream().findFirst();
    }

    public Optional<ReportRecord> findAccessibleReport(long reportId, Long userId, boolean includePrivateReports) {
        List<ReportRecord> records = jdbcTemplate.query("""
                        SELECT *
                          FROM awr_report
                         WHERE id = ?
                           AND (visibility = 'SHARED'
                                OR ?
                                OR (?::bigint IS NULL AND uploaded_by IS NULL)
                                OR uploaded_by = ?::bigint)
                        """,
                reportMapper(),
                reportId,
                includePrivateReports,
                userId,
                userId
        );
        return records.stream().findFirst();
    }

    public Optional<ReportRecord> findDeletableReport(long reportId, Long userId, boolean isAdmin) {
        if (isAdmin) {
            return findReport(reportId);
        }

        if (userId == null) {
            return Optional.empty();
        }

        List<ReportRecord> records = jdbcTemplate.query("""
                    SELECT *
                      FROM awr_report
                     WHERE id = ?
                       AND uploaded_by = ?::bigint
                    """,
                reportMapper(),
                reportId,
                userId
        );

        return records.stream().findFirst();
    }

    public int deleteReport(long reportId) {
        return jdbcTemplate.update("DELETE FROM awr_report WHERE id = ?", reportId);
    }

    public List<AwrDtos.SectionResponse> findSections(long reportId) {
        return jdbcTemplate.query("""
                        SELECT *
                          FROM awr_section
                         WHERE report_id = ?
                         ORDER BY section_order, id
                        """,
                (rs, rowNum) -> new AwrDtos.SectionResponse(
                        rs.getString("section_name"),
                        rs.getInt("section_order"),
                        rs.getString("raw_text"),
                        jsonToMap(rs.getString("parsed_json"))
                ),
                reportId
        );
    }

    public List<AwrDtos.SqlMetricResponse> findSqlMetrics(long reportId) {
        return jdbcTemplate.query("""
                        SELECT *
                          FROM awr_sql_metric
                         WHERE report_id = ?
                         ORDER BY score DESC NULLS LAST, rank_no, id
                        """,
                sqlMetricMapper(),
                reportId
        );
    }

    public List<AwrDtos.WaitEventResponse> findWaitEvents(long reportId) {
        return jdbcTemplate.query("""
                        SELECT *
                          FROM awr_wait_event
                         WHERE report_id = ?
                         ORDER BY db_time_percent DESC NULLS LAST, total_wait_time_sec DESC NULLS LAST, id
                        """,
                (rs, rowNum) -> new AwrDtos.WaitEventResponse(
                        rs.getString("wait_class"),
                        rs.getString("event_name"),
                        getDouble(rs.getObject("total_wait_time_sec")),
                        getDouble(rs.getObject("avg_wait_ms")),
                        getDouble(rs.getObject("db_time_percent"))
                ),
                reportId
        );
    }

    public Optional<AwrDtos.AnalysisResponse> findLatestAnalysis(long reportId, Long userId) {
        List<AwrDtos.AnalysisResponse> results = jdbcTemplate.query("""
                        SELECT answer_json
                          FROM analysis_result
                         WHERE report_id = ?
                           AND result_type = 'analysis'
                           AND ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint)
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1
                        """,
                (rs, rowNum) -> fromJson(rs.getString("answer_json"), AwrDtos.AnalysisResponse.class),
                reportId,
                userId,
                userId
        );
        return results.stream().findFirst();
    }

    public Optional<AwrDtos.AnalysisResponse> findAnalysis(long analysisId, Long userId) {
        List<AwrDtos.AnalysisResponse> results = jdbcTemplate.query("""
                        SELECT answer_json
                          FROM analysis_result
                         WHERE id = ?
                           AND result_type = 'analysis'
                           AND ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint)
                        """,
                (rs, rowNum) -> fromJson(rs.getString("answer_json"), AwrDtos.AnalysisResponse.class),
                analysisId,
                userId,
                userId
        );
        return results.stream().findFirst();
    }

    public Optional<AwrDtos.SqlTuningResponse> findLatestSqlTuning(long reportId, String sqlId, Long userId) {
        List<AwrDtos.SqlTuningResponse> results = jdbcTemplate.query("""
                        SELECT answer_json
                          FROM analysis_result
                         WHERE report_id = ?
                           AND result_type = 'sql_tuning'
                           AND lower(answer_json ->> 'sqlId') = lower(?)
                           AND ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint)
                         ORDER BY created_at DESC, id DESC
                         LIMIT 1
                        """,
                (rs, rowNum) -> fromJson(rs.getString("answer_json"), AwrDtos.SqlTuningResponse.class),
                reportId,
                sqlId,
                userId,
                userId
        );
        return results.stream().findFirst();
    }

    public List<AwrDtos.SqlTuningResponse> findSqlTuningHistory(long reportId, Long userId, boolean includeAllUsers) {
        return jdbcTemplate.query("""
                        SELECT answer_json
                          FROM analysis_result
                         WHERE report_id = ?
                           AND result_type = 'sql_tuning'
                           AND (? OR ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint))
                         ORDER BY created_at DESC, id DESC
                         LIMIT 100
                        """,
                (rs, rowNum) -> fromJson(rs.getString("answer_json"), AwrDtos.SqlTuningResponse.class),
                reportId,
                includeAllUsers,
                userId,
                userId
        );
    }

    public List<AwrDtos.ChatHistoryResponse> findChatHistory(long reportId, Long userId, boolean includeAllUsers) {
        return jdbcTemplate.query("""
                        SELECT id, report_id, user_id, question, answer_json, model, created_at
                          FROM analysis_result
                         WHERE report_id = ?
                           AND result_type = 'chat'
                           AND (? OR ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint))
                         ORDER BY created_at DESC, id DESC
                         LIMIT 100
                        """,
                (rs, rowNum) -> {
                    AwrDtos.ChatResponse response = fromJson(rs.getString("answer_json"), AwrDtos.ChatResponse.class);
                    String question = rs.getString("question");
                    return new AwrDtos.ChatHistoryResponse(
                            rs.getLong("id"),
                            rs.getLong("report_id"),
                            getLong(rs.getObject("user_id")),
                            question == null || question.isBlank() ? response.question() : question,
                            response.answer(),
                            response.citations() == null ? List.of() : response.citations(),
                            response.evidenceSql() == null ? List.of() : response.evidenceSql(),
                            response.evidenceWaitEvents() == null ? List.of() : response.evidenceWaitEvents(),
                            response.confidence(),
                            rs.getString("model"),
                            toLocalDateTime(rs.getTimestamp("created_at"))
                    );
                },
                reportId,
                includeAllUsers,
                userId,
                userId
        );
    }

    public long saveAnalysisResult(long reportId, Long userId, String question, Object answer, String resultType, String model, List<String> citations) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO analysis_result(report_id, user_id, question, answer_json, result_type, model, citations)
                            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
                            """, new String[]{"id"});
            statement.setLong(1, reportId);
            if (userId == null) {
                statement.setObject(2, null);
            } else {
                statement.setLong(2, userId);
            }
            statement.setString(3, question);
            statement.setString(4, toJson(answer));
            statement.setString(5, resultType);
            statement.setString(6, model);
            statement.setString(7, toJson(citations));
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateAnalysisJson(long analysisId, Object answer) {
        jdbcTemplate.update("UPDATE analysis_result SET answer_json = ?::jsonb WHERE id = ?", toJson(answer), analysisId);
    }

    public List<AwrRagChunk> findChunksBySqlId(long reportId, String sqlId, int limit) {
        return jdbcTemplate.query("""
                        SELECT *, NULL::float8 AS similarity
                          FROM rag_chunk
                         WHERE report_id = ?
                           AND lower(sql_id) = lower(?)
                         ORDER BY id
                         LIMIT ?
                        """,
                ragChunkMapper(),
                reportId,
                sqlId,
                limit
        );
    }

    public List<AwrRagChunk> findSimilarChunks(long reportId, String vector, String sqlId, int limit) {
        if (sqlId == null || sqlId.isBlank()) {
            return jdbcTemplate.query("""
                            SELECT *, 1 - (embedding <=> ?::vector) AS similarity
                              FROM rag_chunk
                             WHERE report_id = ?
                               AND embedding IS NOT NULL
                             ORDER BY embedding <=> ?::vector
                             LIMIT ?
                            """,
                    ragChunkMapper(),
                    vector,
                    reportId,
                    vector,
                    limit
            );
        }
        return jdbcTemplate.query("""
                        SELECT *, 1 - (embedding <=> ?::vector) AS similarity
                          FROM rag_chunk
                         WHERE report_id = ?
                           AND embedding IS NOT NULL
                           AND (sql_id IS NULL OR lower(sql_id) = lower(?))
                         ORDER BY embedding <=> ?::vector
                         LIMIT ?
                        """,
                ragChunkMapper(),
                vector,
                reportId,
                sqlId,
                vector,
                limit
        );
    }

    public List<AwrRagChunk> findFallbackChunks(long reportId, String sqlId, int limit) {
        if (sqlId != null && !sqlId.isBlank()) {
            List<AwrRagChunk> bySqlId = findChunksBySqlId(reportId, sqlId, limit);
            if (!bySqlId.isEmpty()) {
                return bySqlId;
            }
        }
        return jdbcTemplate.query("""
                        SELECT *, NULL::float8 AS similarity
                          FROM rag_chunk
                         WHERE report_id = ?
                         ORDER BY CASE chunk_type
                             WHEN 'sql_metric_row' THEN 1
                             WHEN 'wait_event' THEN 2
                             WHEN 'time_model' THEN 3
                             WHEN 'summary' THEN 4
                             ELSE 5
                         END, id
                         LIMIT ?
                        """,
                ragChunkMapper(),
                reportId,
                limit
        );
    }

    public boolean hasEmbeddedChunks(long reportId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT count(*)
                          FROM rag_chunk
                         WHERE report_id = ?
                           AND embedding IS NOT NULL
                        """,
                Integer.class,
                reportId
        );
        return count != null && count > 0;
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + definition);
    }

    private RowMapper<ReportRecord> reportMapper() {
        return (rs, rowNum) -> new ReportRecord(
                rs.getLong("id"),
                rs.getString("filename"),
                rs.getString("db_name"),
                rs.getString("instance_name"),
                rs.getString("snap_begin"),
                rs.getString("snap_end"),
                rs.getString("elapsed_time"),
                rs.getString("db_time"),
                rs.getString("status"),
                toLocalDateTime(rs.getTimestamp("created_at")),
                getLong(rs.getObject("uploaded_by")),
                rs.getString("visibility"),
                rs.getString("raw_file_path"),
                rs.getString("text_path"),
                rs.getString("raw_text_preview"),
                jsonToStringList(rs.getString("warnings"))
        );
    }

    private RowMapper<AwrDtos.SqlMetricResponse> sqlMetricMapper() {
        return (rs, rowNum) -> new AwrDtos.SqlMetricResponse(
                rs.getString("sql_id"),
                rs.getString("section_name"),
                rs.getInt("rank_no"),
                getDouble(rs.getObject("elapsed_time")),
                getDouble(rs.getObject("cpu_time")),
                getLong(rs.getObject("buffer_gets")),
                getLong(rs.getObject("disk_reads")),
                getLong(rs.getObject("executions")),
                getLong(rs.getObject("rows_processed")),
                getLong(rs.getObject("plan_hash_value")),
                rs.getString("module"),
                rs.getString("sql_text"),
                getDouble(rs.getObject("score")),
                rs.getString("interpretation_hint")
        );
    }

    private RowMapper<AwrRagChunk> ragChunkMapper() {
        return (rs, rowNum) -> new AwrRagChunk(
                rs.getLong("id"),
                rs.getLong("report_id"),
                rs.getString("section_name"),
                rs.getString("sql_id"),
                rs.getString("chunk_text"),
                rs.getString("chunk_type"),
                jsonToMap(rs.getString("metric_json")),
                getDouble(rs.getObject("similarity"))
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON 직렬화에 실패했습니다.", exception);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("저장된 분석 결과 JSON을 읽지 못했습니다.", exception);
        }
    }

    private Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            return Map.of("raw", json);
        }
    }

    private List<String> jsonToStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of(json);
        }
    }

    private String vectorLiteral(List<Double> vector) {
        return vector.stream()
                .map(value -> String.format(Locale.ROOT, "%.8f", value))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private Double getDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    private Long getLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public String toVectorLiteral(List<Double> vector) {
        return vectorLiteral(vector);
    }

    public record ReportRecord(
            Long id,
            String filename,
            String dbName,
            String instanceName,
            String snapBegin,
            String snapEnd,
            String elapsedTime,
            String dbTime,
            String status,
            LocalDateTime uploadedAt,
            Long uploadedBy,
            String visibility,
            String rawFilePath,
            String textPath,
            String rawTextPreview,
            List<String> warnings
    ) {
    }
}

package dbinc.sqladvisor.domain.sqltuning.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SqlTuningRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqlTuningRepository(@Qualifier("primaryDataSource") DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sql_tuning_result (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
                    source_type TEXT NOT NULL,
                    report_id BIGINT REFERENCES awr_report(id) ON DELETE SET NULL,
                    sql_id TEXT,
                    sql_text TEXT NOT NULL,
                    input_json JSONB NOT NULL,
                    result_json JSONB NOT NULL,
                    model TEXT,
                    confidence TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_sql_tuning_result_user ON sql_tuning_result(user_id, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_sql_tuning_result_source ON sql_tuning_result(source_type, created_at DESC)");
    }

    public long save(Long userId, String sourceType, String sqlId, String sqlText, Object input, AwrDtos.SqlTuningResponse result) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO sql_tuning_result(user_id, source_type, sql_id, sql_text, input_json, result_json, model, confidence)
                            VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                            """, new String[]{"id"});
            if (userId == null) {
                statement.setObject(1, null);
            } else {
                statement.setLong(1, userId);
            }
            statement.setString(2, sourceType);
            statement.setString(3, sqlId);
            statement.setString(4, sqlText);
            statement.setString(5, toJson(input));
            statement.setString(6, toJson(result));
            statement.setString(7, result.model());
            statement.setString(8, result.confidence());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateResult(long tuningId, AwrDtos.SqlTuningResponse result) {
        jdbcTemplate.update("""
                        UPDATE sql_tuning_result
                           SET result_json = ?::jsonb,
                               model = ?,
                               confidence = ?
                         WHERE id = ?
                        """,
                toJson(result),
                result.model(),
                result.confidence(),
                tuningId
        );
    }

    public List<AwrDtos.SqlTuningResponse> findHistory(Long userId, boolean includeAllUsers) {
        return jdbcTemplate.query("""
                        SELECT input_json, result_json
                          FROM sql_tuning_result
                         WHERE source_type = 'MANUAL_SQL'
                           AND (? OR ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint))
                         ORDER BY created_at DESC, id DESC
                         LIMIT 100
                        """,
                (rs, rowNum) -> withInput(rs.getString("result_json"), rs.getString("input_json")),
                includeAllUsers,
                userId,
                userId
        );
    }

    public Optional<AwrDtos.SqlTuningResponse> findById(long tuningId, Long userId, boolean includeAllUsers) {
        List<AwrDtos.SqlTuningResponse> results = jdbcTemplate.query("""
                        SELECT input_json, result_json
                          FROM sql_tuning_result
                         WHERE id = ?
                           AND (? OR ((?::bigint IS NULL AND user_id IS NULL) OR user_id = ?::bigint))
                        """,
                (rs, rowNum) -> withInput(rs.getString("result_json"), rs.getString("input_json")),
                tuningId,
                includeAllUsers,
                userId,
                userId
        );
        return results.stream().findFirst();
    }

    private AwrDtos.SqlTuningResponse withInput(String resultJson, String inputJson) {
        AwrDtos.SqlTuningResponse result = fromJson(resultJson, AwrDtos.SqlTuningResponse.class);
        AwrDtos.SqlTuningRequest input = result.input() == null
                ? fromJson(inputJson, AwrDtos.SqlTuningRequest.class)
                : result.input();
        return new AwrDtos.SqlTuningResponse(
                result.tuningId(),
                result.reportId(),
                result.sqlId(),
                result.question(),
                input,
                result.metric(),
                result.summary(),
                result.symptoms(),
                result.indexRecommendations(),
                result.rewriteRecommendations(),
                result.validationSteps(),
                result.missingInputs(),
                result.citations(),
                result.model(),
                result.confidence(),
                result.createdAt()
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
            throw new IllegalArgumentException("저장된 SQL 튜닝 결과를 읽을 수 없습니다.", exception);
        }
    }
}

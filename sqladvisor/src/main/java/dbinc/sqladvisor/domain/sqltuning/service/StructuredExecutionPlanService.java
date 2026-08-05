package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.ExecutionPlanDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StructuredExecutionPlanService {

    private record ChildCursor(Integer instanceId, Integer childNumber, Long planHashValue) {
    }

    private final TargetDbConnectionService connectionService;

    public ExecutionPlanDtos.StructuredExecutionPlanResponse collect(
            long connectionId,
            String sqlId,
            Integer requestedChildNumber
    ) {
        String normalizedSqlId = normalizeSqlId(sqlId);
        TargetDbConnectionRepository.TargetDbConnectionRecord target = connectionService.getVisibleRecord(connectionId);
        List<String> warnings = new ArrayList<>();

        try (Connection connection = connectionService.openConnection(target)) {
            ChildCursor cursor = resolveCursor(connection, normalizedSqlId, requestedChildNumber, warnings);
            if (cursor == null) {
                throw new IllegalArgumentException("SQL_ID에 해당하는 실행 커서를 찾을 수 없습니다: " + normalizedSqlId);
            }

            try {
                List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes = queryPlan(
                        connection,
                        "gv$sql_plan_statistics_all",
                        true,
                        normalizedSqlId,
                        cursor
                );
                if (!nodes.isEmpty()) {
                    return response(connectionId, normalizedSqlId, cursor, "gv$sql_plan_statistics_all", nodes, warnings);
                }
            } catch (SQLException exception) {
                warnings.add("gv$sql_plan_statistics_all 조회 실패: " + exception.getMessage());
            }

            try {
                List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes = queryPlan(
                        connection,
                        "v$sql_plan_statistics_all",
                        false,
                        normalizedSqlId,
                        cursor
                );
                if (!nodes.isEmpty()) {
                    return response(connectionId, normalizedSqlId, cursor, "v$sql_plan_statistics_all", nodes, warnings);
                }
            } catch (SQLException exception) {
                warnings.add("v$sql_plan_statistics_all 조회 실패: " + exception.getMessage());
            }

            try {
                List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes = queryPlanOnly(
                        connection,
                        "gv$sql_plan",
                        true,
                        normalizedSqlId,
                        cursor
                );
                if (!nodes.isEmpty()) {
                    warnings.add("실제 실행 통계를 찾지 못해 GV$SQL_PLAN 예상값만 반환합니다.");
                    return response(connectionId, normalizedSqlId, cursor, "gv$sql_plan", nodes, warnings);
                }
            } catch (SQLException exception) {
                warnings.add("gv$sql_plan 조회 실패: " + exception.getMessage());
            }

            try {
                List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes = queryPlanOnly(
                        connection,
                        "v$sql_plan",
                        false,
                        normalizedSqlId,
                        cursor
                );
                if (!nodes.isEmpty()) {
                    warnings.add("실제 실행 통계를 찾지 못해 V$SQL_PLAN 예상값만 반환합니다.");
                    return response(connectionId, normalizedSqlId, cursor, "v$sql_plan", nodes, warnings);
                }
            } catch (SQLException exception) {
                warnings.add("v$sql_plan 조회 실패: " + exception.getMessage());
            }

            throw new IllegalArgumentException("실행계획을 찾을 수 없습니다. SQL이 Shared Pool에서 내려갔거나 실행 통계가 없을 수 있습니다.");
        } catch (SQLException exception) {
            throw new IllegalArgumentException("구조화 실행계획 조회에 실패했습니다: " + exception.getMessage(), exception);
        }
    }

    private ChildCursor resolveCursor(
            Connection connection,
            String sqlId,
            Integer requestedChildNumber,
            List<String> warnings
    ) {
        try {
            return resolveCursorFromView(connection, "gv$sql", true, sqlId, requestedChildNumber);
        } catch (SQLException exception) {
            warnings.add("gv$sql 커서 조회 실패로 v$sql을 사용합니다: " + exception.getMessage());
            try {
                return resolveCursorFromView(connection, "v$sql", false, sqlId, requestedChildNumber);
            } catch (SQLException fallbackException) {
                warnings.add("v$sql 커서 조회 실패: " + fallbackException.getMessage());
                return null;
            }
        }
    }

    private ChildCursor resolveCursorFromView(
            Connection connection,
            String viewName,
            boolean includeInstance,
            String sqlId,
            Integer requestedChildNumber
    ) throws SQLException {
        String childFilter = requestedChildNumber == null ? "" : " AND child_number = ?";
        String sql = """
                SELECT *
                  FROM (
                        SELECT %s inst_id,
                               child_number,
                               plan_hash_value
                          FROM %s
                         WHERE sql_id = ?
                           AND plan_hash_value IS NOT NULL
                           %s
                         ORDER BY last_active_time DESC NULLS LAST,
                                  elapsed_time DESC,
                                  child_number DESC
                       )
                 WHERE ROWNUM <= 1
                """.formatted(includeInstance ? "inst_id" : "CAST(NULL AS NUMBER)", viewName, childFilter);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sqlId);
            if (requestedChildNumber != null) {
                statement.setInt(2, requestedChildNumber);
            }
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ChildCursor(
                        nullableInteger(resultSet, "inst_id"),
                        nullableInteger(resultSet, "child_number"),
                        nullableLong(resultSet, "plan_hash_value")
                );
            }
        }
    }

    private List<ExecutionPlanDtos.ExecutionPlanNodeResponse> queryPlan(
            Connection connection,
            String viewName,
            boolean includeInstance,
            String sqlId,
            ChildCursor cursor
    ) throws SQLException {
        String instanceFilter = includeInstance && cursor.instanceId() != null ? " AND inst_id = ?" : "";
        String sql = """
                SELECT id,
                       parent_id,
                       depth,
                       operation,
                       options,
                       object_owner,
                       object_name,
                       object_type,
                       cost,
                       cardinality,
                       bytes,
                       access_predicates,
                       filter_predicates,
                       starts,
                       output_rows,
                       cr_buffer_gets,
                       disk_reads,
                       last_starts,
                       last_output_rows,
                       last_cr_buffer_gets,
                       last_disk_reads
                  FROM %s
                 WHERE sql_id = ?
                   AND child_number = ?
                   AND plan_hash_value = ?
                   %s
                 ORDER BY id
                """.formatted(viewName, instanceFilter);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, sqlId);
            statement.setInt(index++, cursor.childNumber());
            statement.setLong(index++, cursor.planHashValue());
            if (!instanceFilter.isBlank()) {
                statement.setInt(index, cursor.instanceId());
            }
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes = new ArrayList<>();
                while (resultSet.next()) {
                    nodes.add(mapNode(resultSet, true));
                }
                return nodes;
            }
        }
    }

    private List<ExecutionPlanDtos.ExecutionPlanNodeResponse> queryPlanOnly(
            Connection connection,
            String viewName,
            boolean includeInstance,
            String sqlId,
            ChildCursor cursor
    ) throws SQLException {
        String instanceFilter = includeInstance && cursor.instanceId() != null ? " AND inst_id = ?" : "";
        String sql = """
                SELECT id,
                       parent_id,
                       depth,
                       operation,
                       options,
                       object_owner,
                       object_name,
                       object_type,
                       cost,
                       cardinality,
                       bytes,
                       access_predicates,
                       filter_predicates
                  FROM %s
                 WHERE sql_id = ?
                   AND child_number = ?
                   AND plan_hash_value = ?
                   %s
                 ORDER BY id
                """.formatted(viewName, instanceFilter);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, sqlId);
            statement.setInt(index++, cursor.childNumber());
            statement.setLong(index++, cursor.planHashValue());
            if (!instanceFilter.isBlank()) {
                statement.setInt(index, cursor.instanceId());
            }
            statement.setQueryTimeout(10);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes = new ArrayList<>();
                while (resultSet.next()) {
                    nodes.add(mapNode(resultSet, false));
                }
                return nodes;
            }
        }
    }

    private ExecutionPlanDtos.ExecutionPlanNodeResponse mapNode(ResultSet resultSet, boolean includeRuntime) throws SQLException {
        return new ExecutionPlanDtos.ExecutionPlanNodeResponse(
                nullableInteger(resultSet, "id"),
                nullableInteger(resultSet, "parent_id"),
                nullableInteger(resultSet, "depth"),
                resultSet.getString("operation"),
                resultSet.getString("options"),
                resultSet.getString("object_owner"),
                resultSet.getString("object_name"),
                resultSet.getString("object_type"),
                nullableLong(resultSet, "cost"),
                nullableLong(resultSet, "cardinality"),
                nullableLong(resultSet, "bytes"),
                resultSet.getString("access_predicates"),
                resultSet.getString("filter_predicates"),
                includeRuntime ? nullableLong(resultSet, "starts") : null,
                includeRuntime ? nullableLong(resultSet, "output_rows") : null,
                includeRuntime ? nullableLong(resultSet, "cr_buffer_gets") : null,
                includeRuntime ? nullableLong(resultSet, "disk_reads") : null,
                includeRuntime ? nullableLong(resultSet, "last_starts") : null,
                includeRuntime ? nullableLong(resultSet, "last_output_rows") : null,
                includeRuntime ? nullableLong(resultSet, "last_cr_buffer_gets") : null,
                includeRuntime ? nullableLong(resultSet, "last_disk_reads") : null
        );
    }

    private ExecutionPlanDtos.StructuredExecutionPlanResponse response(
            long connectionId,
            String sqlId,
            ChildCursor cursor,
            String sourceView,
            List<ExecutionPlanDtos.ExecutionPlanNodeResponse> nodes,
            List<String> warnings
    ) {
        return new ExecutionPlanDtos.StructuredExecutionPlanResponse(
                connectionId,
                sqlId,
                cursor.instanceId(),
                cursor.childNumber(),
                cursor.planHashValue(),
                sourceView,
                List.copyOf(nodes),
                List.copyOf(warnings)
        );
    }

    private String normalizeSqlId(String sqlId) {
        if (sqlId == null || sqlId.isBlank()) {
            throw new IllegalArgumentException("SQL_ID는 필수입니다.");
        }
        String normalized = sqlId.trim().toLowerCase();
        if (!normalized.matches("[0-9a-z]{13}")) {
            throw new IllegalArgumentException("SQL_ID 형식이 올바르지 않습니다: " + sqlId);
        }
        return normalized;
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}

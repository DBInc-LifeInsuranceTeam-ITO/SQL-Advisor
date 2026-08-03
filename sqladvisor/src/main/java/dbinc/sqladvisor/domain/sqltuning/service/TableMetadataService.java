package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.sqltuning.dto.ExecutionPlanDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.TableMetadataDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TableMetadataService {

    private final TargetDbConnectionService connectionService;

    public TableMetadataDtos.TableMetadataListResponse collect(
            long connectionId,
            ExecutionPlanDtos.StructuredExecutionPlanResponse plan
    ) {
        TargetDbConnectionRepository.TargetDbConnectionRecord target = connectionService.getVisibleRecord(connectionId);
        List<String> warnings = new ArrayList<>();
        Set<TableKey> tableKeys = new LinkedHashSet<>();
        Set<String> usedIndexes = new LinkedHashSet<>();

        for (ExecutionPlanDtos.ExecutionPlanNodeResponse node : plan.nodes()) {
            if (node.objectName() == null || node.objectName().isBlank()) {
                continue;
            }
            if (node.operation() != null && node.operation().toUpperCase().startsWith("TABLE ACCESS")) {
                tableKeys.add(new TableKey(node.objectOwner(), node.objectName()));
            }
            if (node.operation() != null && node.operation().toUpperCase().startsWith("INDEX")) {
                usedIndexes.add(qualified(node.objectOwner(), node.objectName()));
            }
        }

        try (Connection connection = connectionService.openConnection(target)) {
            List<TableMetadataDtos.TableMetadataResponse> tables = new ArrayList<>();
            for (TableKey key : tableKeys) {
                tables.add(loadTable(connection, key, usedIndexes, warnings));
            }
            return new TableMetadataDtos.TableMetadataListResponse(List.copyOf(tables), List.copyOf(warnings));
        } catch (SQLException exception) {
            throw new IllegalArgumentException("테이블/인덱스 메타데이터 조회에 실패했습니다: " + exception.getMessage(), exception);
        }
    }

    private TableMetadataDtos.TableMetadataResponse loadTable(
            Connection connection,
            TableKey key,
            Set<String> usedIndexes,
            List<String> warnings
    ) throws SQLException {
        TableRow table = queryTable(connection, key, warnings);
        List<TableMetadataDtos.IndexMetadataResponse> indexes = queryIndexes(connection, key, usedIndexes, warnings);
        return new TableMetadataDtos.TableMetadataResponse(
                table.owner(),
                table.tableName(),
                table.numRows(),
                table.blocks(),
                table.avgRowLength(),
                table.sampleSize(),
                table.lastAnalyzed(),
                table.partitioned(),
                table.temporary(),
                indexes
        );
    }

    private TableRow queryTable(Connection connection, TableKey key, List<String> warnings) throws SQLException {
        SQLException first = null;
        try {
            return queryTableView(connection, "dba_tables", key, true);
        } catch (SQLException exception) {
            first = exception;
        }
        try {
            return queryTableView(connection, "all_tables", key, true);
        } catch (SQLException exception) {
            warnings.add("DBA_TABLES 조회 실패로 ALL_TABLES를 시도했으나 실패했습니다: " + exception.getMessage());
            if (first != null) {
                warnings.add("DBA_TABLES 오류: " + first.getMessage());
            }
            return new TableRow(key.owner(), key.tableName(), null, null, null, null, null, null, null);
        }
    }

    private TableRow queryTableView(Connection connection, String view, TableKey key, boolean ownerColumn) throws SQLException {
        String sql = "SELECT owner, table_name, num_rows, blocks, avg_row_len, sample_size, last_analyzed, partitioned, temporary "
                + "FROM " + view + " WHERE owner = ? AND table_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.owner());
            statement.setString(2, key.tableName());
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return new TableRow(key.owner(), key.tableName(), null, null, null, null, null, null, null);
                }
                return new TableRow(
                        rs.getString("owner"), rs.getString("table_name"), nullableLong(rs, "num_rows"),
                        nullableLong(rs, "blocks"), nullableLong(rs, "avg_row_len"), nullableLong(rs, "sample_size"),
                        timestamp(rs, "last_analyzed"), rs.getString("partitioned"), rs.getString("temporary")
                );
            }
        }
    }

    private List<TableMetadataDtos.IndexMetadataResponse> queryIndexes(
            Connection connection,
            TableKey key,
            Set<String> usedIndexes,
            List<String> warnings
    ) throws SQLException {
        try {
            return queryIndexViews(connection, "dba_indexes", "dba_ind_columns", key, usedIndexes);
        } catch (SQLException dbaException) {
            try {
                return queryIndexViews(connection, "all_indexes", "all_ind_columns", key, usedIndexes);
            } catch (SQLException allException) {
                warnings.add("인덱스 메타데이터 조회 실패: " + allException.getMessage());
                return List.of();
            }
        }
    }

    private List<TableMetadataDtos.IndexMetadataResponse> queryIndexViews(
            Connection connection,
            String indexView,
            String columnView,
            TableKey key,
            Set<String> usedIndexes
    ) throws SQLException {
        String sql = "SELECT i.owner, i.index_name, i.index_type, i.uniqueness, i.status, i.visibility, "
                + "i.num_rows, i.distinct_keys, i.leaf_blocks, i.clustering_factor, i.last_analyzed, "
                + "c.column_name, c.column_position "
                + "FROM " + indexView + " i JOIN " + columnView + " c "
                + "ON c.index_owner = i.owner AND c.index_name = i.index_name "
                + "WHERE i.table_owner = ? AND i.table_name = ? ORDER BY i.owner, i.index_name, c.column_position";
        Map<String, IndexBuilder> builders = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.owner());
            statement.setString(2, key.tableName());
            statement.setQueryTimeout(10);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String mapKey = qualified(rs.getString("owner"), rs.getString("index_name"));
                    IndexBuilder builder = builders.computeIfAbsent(mapKey, ignored -> new IndexBuilder(
                            rsGet(rs, "owner"), rsGet(rs, "index_name"), rsGet(rs, "index_type"),
                            rsGet(rs, "uniqueness"), rsGet(rs, "status"), rsGet(rs, "visibility"),
                            safeLong(rs, "num_rows"), safeLong(rs, "distinct_keys"), safeLong(rs, "leaf_blocks"),
                            safeLong(rs, "clustering_factor"), safeTimestamp(rs, "last_analyzed"), new ArrayList<>()
                    ));
                    builder.columns().add(rs.getString("column_name"));
                }
            }
        }
        return builders.values().stream().map(builder -> new TableMetadataDtos.IndexMetadataResponse(
                builder.owner(), builder.indexName(), builder.indexType(), builder.uniqueness(), builder.status(),
                builder.visibility(), builder.numRows(), builder.distinctKeys(), builder.leafBlocks(),
                builder.clusteringFactor(), builder.lastAnalyzed(), List.copyOf(builder.columns()),
                usedIndexes.contains(qualified(builder.owner(), builder.indexName()))
        )).toList();
    }

    private String rsGet(ResultSet rs, String column) {
        try { return rs.getString(column); } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    private Long safeLong(ResultSet rs, String column) {
        try { return nullableLong(rs, column); } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    private LocalDateTime safeTimestamp(ResultSet rs, String column) {
        try { return timestamp(rs, column); } catch (SQLException e) { throw new IllegalStateException(e); }
    }
    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }
    private LocalDateTime timestamp(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toLocalDateTime();
    }
    private String qualified(String owner, String name) {
        return (owner == null ? "" : owner.toUpperCase()) + "." + (name == null ? "" : name.toUpperCase());
    }

    private record TableKey(String owner, String tableName) {}
    private record TableRow(String owner, String tableName, Long numRows, Long blocks, Long avgRowLength,
                            Long sampleSize, LocalDateTime lastAnalyzed, String partitioned, String temporary) {}
    private record IndexBuilder(String owner, String indexName, String indexType, String uniqueness, String status,
                                String visibility, Long numRows, Long distinctKeys, Long leafBlocks,
                                Long clusteringFactor, LocalDateTime lastAnalyzed, List<String> columns) {}
}

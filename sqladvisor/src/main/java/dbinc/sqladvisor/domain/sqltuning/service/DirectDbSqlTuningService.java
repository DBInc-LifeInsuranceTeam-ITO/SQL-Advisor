package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class DirectDbSqlTuningService {

    private static final Duration CONTEXT_CACHE_TTL = Duration.ofMinutes(5);

    private record ContextCacheKey(Long connectionId, String sqlId, String sqlText) {
    }

    private record CachedContext(SqlTuningDtos.DirectDbContextResponse context, LocalDateTime cachedAt) {
        private boolean fresh() {
            return cachedAt.plus(CONTEXT_CACHE_TTL).isAfter(LocalDateTime.now());
        }
    }

    private final TargetDbConnectionService connectionService;
    private final TargetDbContextCollector contextCollector;
    private final SqlTuningService sqlTuningService;
    private final Map<ContextCacheKey, CachedContext> contextCache = new ConcurrentHashMap<>();

    public SqlTuningDtos.DirectDbContextResponse collectContext(SqlTuningDtos.DirectTuningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Direct DB tuning request is required.");
        }
        TargetDbConnectionRepository.TargetDbConnectionRecord connection = connection(request.connectionId());
        SqlTuningDtos.DirectDbContextResponse context = contextCollector.collect(connection, request);
        contextCache.put(cacheKey(request), new CachedContext(context, LocalDateTime.now()));
        return context;
    }

    public AwrDtos.SqlTuningResponse tune(SqlTuningDtos.DirectTuningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Direct DB tuning request is required.");
        }
        CachedContext cached = contextCache.get(cacheKey(request));
        if (cached != null && cached.fresh()) {
            return sqlTuningService.tuneDirect(cached.context());
        }
        return sqlTuningService.tuneDirect(collectContext(request));
    }

    public List<AwrDtos.SqlMetricResponse> topSql(Long connectionId) {
        return topSql(new SqlTuningDtos.DirectTopSqlRequest(connectionId, null, null, null, null, null, null, null, null));
    }

    public List<AwrDtos.SqlMetricResponse> topSql(SqlTuningDtos.DirectTopSqlRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Direct DB Top SQL request is required.");
        }
        TargetDbConnectionRepository.TargetDbConnectionRecord connection = connection(request.connectionId());
        return contextCollector.topSql(connection, request);
    }

    private TargetDbConnectionRepository.TargetDbConnectionRecord connection(Long connectionId) {
        if (connectionId == null) {
            throw new IllegalArgumentException("Target DB connection is required.");
        }
        return connectionService.getVisibleRecord(connectionId);
    }

    private ContextCacheKey cacheKey(SqlTuningDtos.DirectTuningRequest request) {
        return new ContextCacheKey(
                request.connectionId(),
                normalizeKey(request.sqlId()),
                normalizeSqlText(request.sqlText())
        );
    }

    private String normalizeKey(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSqlText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}

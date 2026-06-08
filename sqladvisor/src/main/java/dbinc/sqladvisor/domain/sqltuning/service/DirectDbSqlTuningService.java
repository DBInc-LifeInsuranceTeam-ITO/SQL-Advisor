package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DirectDbSqlTuningService {

    private final TargetDbConnectionService connectionService;
    private final TargetDbContextCollector contextCollector;
    private final SqlTuningService sqlTuningService;

    public SqlTuningDtos.DirectDbContextResponse collectContext(SqlTuningDtos.DirectTuningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Direct DB tuning request is required.");
        }
        TargetDbConnectionRepository.TargetDbConnectionRecord connection = connection(request.connectionId());
        return contextCollector.collect(connection, request);
    }

    public AwrDtos.SqlTuningResponse tune(SqlTuningDtos.DirectTuningRequest request) {
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
}

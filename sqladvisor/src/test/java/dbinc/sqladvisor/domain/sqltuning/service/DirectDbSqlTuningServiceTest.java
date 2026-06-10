package dbinc.sqladvisor.domain.sqltuning.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectDbSqlTuningServiceTest {

    @Test
    void reusesFreshCollectedContextWhenTuningSameRequest() {
        TargetDbConnectionService connectionService = mock(TargetDbConnectionService.class);
        TargetDbContextCollector contextCollector = mock(TargetDbContextCollector.class);
        SqlTuningService sqlTuningService = mock(SqlTuningService.class);
        DirectDbSqlTuningService service = new DirectDbSqlTuningService(
                connectionService,
                contextCollector,
                sqlTuningService
        );
        SqlTuningDtos.DirectTuningRequest request = new SqlTuningDtos.DirectTuningRequest(3L, "7p6k1x9s2m3ab", null);
        TargetDbConnectionRepository.TargetDbConnectionRecord connection = new TargetDbConnectionRepository.TargetDbConnectionRecord(
                3L,
                7L,
                "PROD readonly",
                "ORACLE",
                "jdbc:oracle:thin:@//db:1521/service",
                "SQLADVISOR_RO",
                "encrypted",
                "PRIVATE",
                false,
                600,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        AwrDtos.SqlMetricResponse metric = new AwrDtos.SqlMetricResponse(
                "7p6k1x9s2m3ab",
                "Direct DB SQL",
                0,
                12.0,
                3.0,
                1_000_000L,
                1_000L,
                10L,
                10L,
                12345L,
                "JDBC",
                "SELECT * FROM APP.ORDERS WHERE ORDER_ID = :1",
                null,
                "Collected from target database."
        );
        AwrDtos.SqlTuningRequest input = new AwrDtos.SqlTuningRequest(
                metric.sqlText(),
                "Tune direct DB SQL",
                "TABLE ACCESS FULL APP.ORDERS",
                "APP.ORDERS num_rows=1000000, blocks=100000, avg_row_len=120, sample_size=1000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N",
                null,
                ":1=100"
        );
        SqlTuningDtos.DirectDbContextResponse context = new SqlTuningDtos.DirectDbContextResponse(
                3L,
                "PROD readonly",
                metric,
                input,
                List.of(),
                LocalDateTime.now()
        );
        AwrDtos.SqlTuningResponse tuning = new AwrDtos.SqlTuningResponse(
                99L,
                null,
                metric.sqlId(),
                input.question(),
                input,
                metric,
                "summary",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "rule-based-local-advisor",
                "medium",
                LocalDateTime.now()
        );

        when(connectionService.getVisibleRecord(3L)).thenReturn(connection);
        when(contextCollector.collect(connection, request)).thenReturn(context);
        when(sqlTuningService.tuneDirect(context)).thenReturn(tuning);

        service.collectContext(request);
        AwrDtos.SqlTuningResponse response = service.tune(request);

        assertThat(response).isSameAs(tuning);
        verify(contextCollector, times(1)).collect(connection, request);
        verify(sqlTuningService).tuneDirect(context);
    }
}

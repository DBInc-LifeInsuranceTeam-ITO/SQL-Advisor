package dbinc.sqladvisor.domain.sqltuning.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import dbinc.sqladvisor.domain.sqltuning.service.DirectDbSqlTuningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/sql-tuning/direct")
public class DirectDbSqlTuningController {

    private final DirectDbSqlTuningService directDbSqlTuningService;

    @PostMapping("/context")
    public ResponseEntity<ApiResponse<SqlTuningDtos.DirectDbContextResponse>> collectContext(
            @RequestBody SqlTuningDtos.DirectTuningRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(directDbSqlTuningService.collectContext(request)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AwrDtos.SqlTuningResponse>> tune(
            @RequestBody SqlTuningDtos.DirectTuningRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(directDbSqlTuningService.tune(request)));
    }

    @GetMapping("/top-sql")
    public ResponseEntity<ApiResponse<List<AwrDtos.SqlMetricResponse>>> topSql(
            @RequestParam Long connectionId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(required = false) String schema,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String program
    ) {
        return ResponseEntity.ok(ApiResponse.success(directDbSqlTuningService.topSql(new SqlTuningDtos.DirectTopSqlRequest(
                connectionId,
                source,
                limit,
                sortBy,
                startTime,
                endTime,
                schema,
                module,
                program
        ))));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        log.warn("Direct DB SQL tuning request failed: {}", exception.getMessage(), exception);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.badRequest(exception.getMessage()));
    }
}

package dbinc.sqladvisor.domain.sqltuning.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.DirectSqlMetricDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.ExecutionPlanDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlDiagnosisDtos;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import dbinc.sqladvisor.domain.sqltuning.service.DirectDbSqlTuningService;
import dbinc.sqladvisor.domain.sqltuning.service.DirectSqlMetricService;
import dbinc.sqladvisor.domain.sqltuning.service.RuleBasedSqlDiagnosisService;
import dbinc.sqladvisor.domain.sqltuning.service.StructuredExecutionPlanService;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/sql-tuning/direct")
public class DirectDbSqlTuningController {

    private final DirectDbSqlTuningService directDbSqlTuningService;
    private final DirectSqlMetricService directSqlMetricService;
    private final StructuredExecutionPlanService structuredExecutionPlanService;
    private final RuleBasedSqlDiagnosisService ruleBasedSqlDiagnosisService;

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

    @GetMapping("/top-sql/metrics")
    public ResponseEntity<ApiResponse<DirectSqlMetricDtos.DirectSqlMetricListResponse>> detailedTopSqlMetrics(
            @RequestParam Long connectionId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "false") boolean includeSystemSql
    ) {
        return ResponseEntity.ok(ApiResponse.success(directSqlMetricService.topSql(
                connectionId,
                limit,
                sortBy,
                includeSystemSql
        )));
    }

    @GetMapping("/execution-plan")
    public ResponseEntity<ApiResponse<ExecutionPlanDtos.StructuredExecutionPlanResponse>> structuredExecutionPlan(
            @RequestParam Long connectionId,
            @RequestParam String sqlId,
            @RequestParam(required = false) Integer instanceId,
            @RequestParam(required = false) Integer childNumber
    ) {
        return ResponseEntity.ok(ApiResponse.success(structuredExecutionPlanService.collect(
                connectionId,
                sqlId,
                instanceId,
                childNumber
        )));
    }

    @GetMapping("/diagnosis")
    public ResponseEntity<ApiResponse<SqlDiagnosisDtos.SqlDiagnosisResponse>> diagnose(
            @RequestParam Long connectionId,
            @RequestParam String sqlId,
            @RequestParam(required = false) Integer instanceId,
            @RequestParam(required = false) Integer childNumber
    ) {
        return ResponseEntity.ok(ApiResponse.success(ruleBasedSqlDiagnosisService.diagnose(
                connectionId,
                sqlId,
                instanceId,
                childNumber
        )));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.badRequest(exception.getMessage()));
    }
}

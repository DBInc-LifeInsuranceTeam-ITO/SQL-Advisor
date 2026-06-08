package dbinc.sqladvisor.domain.sqltuning.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.sqltuning.service.SqlTuningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/sql-tuning")
public class SqlTuningController {

    private final SqlTuningService sqlTuningService;

    @PostMapping
    public ResponseEntity<ApiResponse<AwrDtos.SqlTuningResponse>> tune(@RequestBody AwrDtos.SqlTuningRequest request) {
        return ResponseEntity.ok(ApiResponse.success(sqlTuningService.tune(request)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<List<AwrDtos.SqlTuningResponse>>> listHistory() {
        return ResponseEntity.ok(ApiResponse.success(sqlTuningService.listHistory()));
    }

    @GetMapping("/{tuningId}")
    public ResponseEntity<ApiResponse<AwrDtos.SqlTuningResponse>> getTuning(@PathVariable long tuningId) {
        return ResponseEntity.ok(ApiResponse.success(sqlTuningService.getTuning(tuningId)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.badRequest(exception.getMessage()));
    }
}

package dbinc.sqladvisor.domain.sqltuning.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.common.enums.ErrorCode;
import dbinc.sqladvisor.domain.sqltuning.dto.SqlTuningDtos;
import dbinc.sqladvisor.domain.sqltuning.service.TargetDbConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/db-connections")
public class TargetDbConnectionController {

    private final TargetDbConnectionService connectionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SqlTuningDtos.TargetDbConnectionResponse>>> listConnections() {
        return ResponseEntity.ok(ApiResponse.success(connectionService.listConnections()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SqlTuningDtos.TargetDbConnectionResponse>> createConnection(
            @RequestBody SqlTuningDtos.TargetDbConnectionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(connectionService.createConnection(request)));
    }

    @PatchMapping("/{connectionId}")
    public ResponseEntity<ApiResponse<SqlTuningDtos.TargetDbConnectionResponse>> updateConnection(
            @PathVariable long connectionId,
            @RequestBody SqlTuningDtos.TargetDbConnectionRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(connectionService.updateConnection(connectionId, request)));
    }

    @DeleteMapping("/{connectionId}")
    public ResponseEntity<ApiResponse<Void>> deleteConnection(@PathVariable long connectionId) {
        connectionService.deleteConnection(connectionId);
        return ResponseEntity.ok(ApiResponse.success("Target DB connection deleted."));
    }

    @PostMapping("/test")
    public ResponseEntity<ApiResponse<SqlTuningDtos.TargetDbConnectionTestResponse>> testConnection(
            @RequestBody SqlTuningDtos.TargetDbConnectionTestRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(connectionService.testConnection(request)));
    }

    @PostMapping("/{connectionId}/test")
    public ResponseEntity<ApiResponse<SqlTuningDtos.TargetDbConnectionTestResponse>> testSavedConnection(
            @PathVariable long connectionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(connectionService.testSavedConnection(connectionId)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest(exception.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(403).body(ApiResponse.error(ErrorCode.FORBIDDEN, exception.getMessage()));
    }
}

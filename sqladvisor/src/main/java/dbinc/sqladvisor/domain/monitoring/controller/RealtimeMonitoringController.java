package dbinc.sqladvisor.domain.monitoring.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.monitoring.dto.MonitoringDtos;
import dbinc.sqladvisor.domain.monitoring.service.RealtimeMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/monitoring")
public class RealtimeMonitoringController {

    private final RealtimeMonitoringService monitoringService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<MonitoringDtos.DashboardResponse>> dashboard(
            @RequestParam long connectionId
    ) {
        return ResponseEntity.ok(ApiResponse.success(monitoringService.collect(connectionId)));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest(exception.getMessage()));
    }
}

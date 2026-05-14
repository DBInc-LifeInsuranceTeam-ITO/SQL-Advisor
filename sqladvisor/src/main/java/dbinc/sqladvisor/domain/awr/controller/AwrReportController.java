package dbinc.sqladvisor.domain.awr.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.awr.service.AwrAiConfigService;
import dbinc.sqladvisor.domain.awr.service.AwrReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class AwrReportController {

    private final AwrReportService awrReportService;
    private final AwrAiConfigService awrAiConfigService;

    @GetMapping("/config/ai")
    public ResponseEntity<ApiResponse<AwrDtos.AiConfigResponse>> getAiConfig() {
        return ResponseEntity.ok(ApiResponse.success(awrAiConfigService.getConfig()));
    }

    @PostMapping("/config/ai")
    public ResponseEntity<ApiResponse<AwrDtos.AiConfigResponse>> updateAiConfig(@RequestBody AwrDtos.AiConfigUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(awrAiConfigService.updateConfig(request)));
    }

    @GetMapping("/config/ai/models")
    public ResponseEntity<ApiResponse<AwrDtos.AiModelOptionsResponse>> getAiModelOptions() {
        return ResponseEntity.ok(ApiResponse.success(awrAiConfigService.getModelOptions()));
    }

    @PostMapping(value = "/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AwrDtos.UploadResponse>> uploadReport(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "SHARED") String visibility
    ) throws IOException {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.created(awrReportService.upload(file, visibility)));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<List<AwrDtos.ReportSummaryResponse>>> listReports() {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.listReports()));
    }

    @GetMapping("/reports/{reportId}")
    public ResponseEntity<ApiResponse<AwrDtos.ReportDetailResponse>> getReport(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.getReport(reportId)));
    }

    @GetMapping("/reports/{reportId}/status")
    public ResponseEntity<ApiResponse<AwrDtos.StatusResponse>> getStatus(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.getStatus(reportId)));
    }

    @PostMapping("/internal/worker/reports/{reportId}/status")
    public ResponseEntity<ApiResponse<AwrDtos.StatusResponse>> updateWorkerStatus(
            @PathVariable Long reportId,
            @RequestBody AwrDtos.WorkerStatusRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.updateWorkerStatus(reportId, request)));
    }

    @PostMapping("/internal/worker/reports/{reportId}/extraction")
    public ResponseEntity<ApiResponse<AwrDtos.StatusResponse>> completeWorkerExtraction(
            @PathVariable Long reportId,
            @RequestBody AwrDtos.WorkerExtractionRequest request
    ) throws IOException {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.completeWorkerExtraction(reportId, request)));
    }

    @PostMapping("/reports/{reportId}/analyze")
    public ResponseEntity<ApiResponse<AwrDtos.AnalysisResponse>> analyze(
            @PathVariable Long reportId,
            @RequestBody(required = false) AwrDtos.AnalyzeRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.analyze(reportId, request)));
    }

    @PostMapping("/reports/{reportId}/chat")
    public ResponseEntity<ApiResponse<AwrDtos.ChatResponse>> chat(
            @PathVariable Long reportId,
            @RequestBody AwrDtos.ChatRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.chat(reportId, request)));
    }

    @GetMapping("/reports/{reportId}/chat/history")
    public ResponseEntity<ApiResponse<List<AwrDtos.ChatHistoryResponse>>> listChatHistory(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.listChatHistory(reportId)));
    }

    @GetMapping("/reports/{reportId}/sql")
    public ResponseEntity<ApiResponse<List<AwrDtos.SqlMetricResponse>>> listSql(@PathVariable Long reportId) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.listSql(reportId)));
    }

    @GetMapping("/reports/{reportId}/sql/{sqlId}")
    public ResponseEntity<ApiResponse<AwrDtos.SqlMetricResponse>> getSql(
            @PathVariable Long reportId,
            @PathVariable String sqlId
    ) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.getSql(reportId, sqlId)));
    }

    @GetMapping("/analysis/{analysisId}")
    public ResponseEntity<ApiResponse<AwrDtos.AnalysisResponse>> getAnalysis(@PathVariable Long analysisId) {
        return ResponseEntity.ok(ApiResponse.success(awrReportService.getAnalysis(analysisId)));
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.badRequest(exception.getMessage()));
    }
}

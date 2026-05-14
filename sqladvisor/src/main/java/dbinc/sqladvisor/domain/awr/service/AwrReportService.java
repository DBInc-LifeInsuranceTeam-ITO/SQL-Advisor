package dbinc.sqladvisor.domain.awr.service;

import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AwrReportService {

    private final AwrParser parser;
    private final AwrAdvisor localAdvisor;
    private final AwrLlmAdvisor llmAdvisor;
    private final AwrRagService ragService;
    private final AwrRepository repository;
    private final AwrAiClient aiClient;
    private final AwrWorkerClient workerClient;
    private final CurrentUserService currentUserService;

    @Value("${awr.storage.root:./data/awr}")
    private String storageRoot;

    private Path rawDirectory;
    private Path textDirectory;

    @PostConstruct
    public void init() throws IOException {
        Path root = Paths.get(storageRoot).toAbsolutePath().normalize();
        rawDirectory = root.resolve("raw");
        textDirectory = root.resolve("text");
        Files.createDirectories(rawDirectory);
        Files.createDirectories(textDirectory);
        log.info("AWR storage initialized at {}", root);
    }

    public AwrDtos.UploadResponse upload(MultipartFile file, String visibility) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("AWR 파일을 선택해주세요.");
        }

        String originalFilename = Optional.ofNullable(file.getOriginalFilename()).orElse("awr-report.txt");
        Long userId = currentUserService.currentUserIdOrNull();
        String normalizedVisibility = normalizeVisibility(visibility);
        if ("PRIVATE".equals(normalizedVisibility) && userId == null) {
            throw new IllegalArgumentException("비공유 AWR 리포트는 로그인한 사용자만 업로드할 수 있습니다.");
        }
        long reportId = repository.createReport(originalFilename, userId, normalizedVisibility);

        byte[] bytes = file.getBytes();
        String safeFilename = reportId + "-" + sanitize(originalFilename);
        Path rawPath = rawDirectory.resolve(safeFilename);
        Files.write(rawPath, bytes, StandardOpenOption.CREATE_NEW);

        List<String> warnings = new ArrayList<>();
        warnings.add("worker job queued");
        repository.updateReportQueued(reportId, rawPath.toString(), warnings);

        try {
            String jobId = workerClient.enqueueExtraction(reportId, originalFilename, rawPath.toString());
            warnings.add("worker_job_id=" + jobId);
            repository.updateReportStatus(reportId, "QUEUED", warnings);
            return new AwrDtos.UploadResponse(
                    reportId,
                    originalFilename,
                    normalizedVisibility,
                    "QUEUED",
                    "AWR 파일을 저장했고 worker 큐에 등록했습니다. status API에서 추출/파싱/indexing 진행률을 확인할 수 있습니다."
            );
        } catch (RuntimeException exception) {
            warnings.add("worker enqueue failed: " + exception.getMessage());
            repository.updateReportStatus(reportId, "FAILED", warnings);
            return new AwrDtos.UploadResponse(
                    reportId,
                    originalFilename,
                    normalizedVisibility,
                    "FAILED",
                    "AWR 파일은 저장했지만 worker 큐 등록에 실패했습니다: " + exception.getMessage()
            );
        }
    }

    public List<AwrDtos.ReportSummaryResponse> listReports() {
        return repository.listReports(
                currentUserService.currentUserIdOrNull(),
                currentUserService.isCurrentUserAdmin()
        );
    }

    public AwrDtos.ReportDetailResponse getReport(Long reportId) {
        AwrRepository.ReportRecord report = getReportRecord(reportId);
        List<AwrDtos.SqlMetricResponse> sqlMetrics = repository.findSqlMetrics(reportId);
        return new AwrDtos.ReportDetailResponse(
                report.id(),
                report.filename(),
                report.dbName(),
                report.instanceName(),
                report.snapBegin(),
                report.snapEnd(),
                report.elapsedTime(),
                report.dbTime(),
                report.status(),
                report.uploadedAt(),
                report.uploadedBy(),
                report.visibility(),
                report.rawTextPreview(),
                repository.findSections(reportId),
                sqlMetrics.stream().limit(20).toList(),
                repository.findWaitEvents(reportId),
                repository.findLatestAnalysis(reportId, currentUserService.currentUserIdOrNull()).orElse(null)
        );
    }

    public AwrDtos.StatusResponse getStatus(Long reportId) {
        AwrRepository.ReportRecord report = getReportRecord(reportId);
        return buildStatus(reportId, report);
    }

    private AwrDtos.StatusResponse buildStatus(Long reportId, AwrRepository.ReportRecord report) {
        List<AwrDtos.SectionResponse> sections = repository.findSections(reportId);
        List<AwrDtos.SqlMetricResponse> sqlMetrics = repository.findSqlMetrics(reportId);
        boolean hasEmbeddedChunks = repository.hasEmbeddedChunks(reportId);
        List<String> completed = new ArrayList<>();
        completed.add("file-upload");
        if (report.textPath() != null) {
            completed.add("text-extraction");
        }
        if (!sections.isEmpty()) {
            completed.add("awr-section-parser");
        }
        if (!sqlMetrics.isEmpty()) {
            completed.add("sql-metric-extractor");
        }
        if ("INDEXED".equals(report.status())) {
            completed.add("rag-chunk-ready");
            if (hasEmbeddedChunks) {
                completed.add("pgvector-embedding-index");
            }
        }

        int progress = switch (report.status()) {
            case "INDEXED" -> 100;
            case "INDEXING" -> 75;
            case "EXTRACTING" -> 50;
            case "NEEDS_TEXT_EXTRACTION" -> 45;
            case "QUEUED" -> 30;
            case "FAILED" -> 100;
            case "UPLOADING" -> 20;
            default -> 70;
        };
        return new AwrDtos.StatusResponse(
                report.id(),
                report.status(),
                progress,
                currentStep(report, sqlMetrics, hasEmbeddedChunks),
                completed,
                report.warnings()
        );
    }

    public AwrDtos.AnalysisResponse analyze(Long reportId, AwrDtos.AnalyzeRequest request) {
        getReportRecord(reportId);
        String question = request == null ? null : request.question();
        List<AwrDtos.SqlMetricResponse> sqlMetrics = repository.findSqlMetrics(reportId);
        List<AwrDtos.WaitEventResponse> waitEvents = repository.findWaitEvents(reportId);
        List<AwrRagChunk> ragChunks = ragService.retrieve(reportId, question);

        AwrDtos.AnalysisResponse local = localAdvisor.analyze(reportId, question, sqlMetrics, waitEvents);
        AwrDtos.AnalysisResponse selected = llmAdvisor.analyze(reportId, question, local, ragChunks).orElse(local);

        long analysisId = repository.saveAnalysisResult(
                reportId,
                currentUserService.currentUserIdOrNull(),
                selected.question(),
                selected,
                "analysis",
                selected.model(),
                selected.citations()
        );
        AwrDtos.AnalysisResponse persisted = new AwrDtos.AnalysisResponse(
                analysisId,
                reportId,
                selected.question(),
                selected.summary(),
                selected.topFindings(),
                selected.missingInputs(),
                selected.citations(),
                selected.model(),
                selected.createdAt() == null ? LocalDateTime.now() : selected.createdAt()
        );
        repository.updateAnalysisJson(analysisId, persisted);
        return persisted;
    }

    public AwrDtos.ChatResponse chat(Long reportId, AwrDtos.ChatRequest request) {
        getReportRecord(reportId);
        String question = request == null ? null : request.question();
        List<AwrDtos.SqlMetricResponse> sqlMetrics = repository.findSqlMetrics(reportId);
        List<AwrDtos.WaitEventResponse> waitEvents = repository.findWaitEvents(reportId);
        List<AwrRagChunk> ragChunks = ragService.retrieve(reportId, question);

        AwrDtos.ChatResponse local = localAdvisor.chat(reportId, question, sqlMetrics, waitEvents);
        AwrDtos.ChatResponse selected = llmAdvisor.chat(reportId, question, local, ragChunks).orElse(local);
        repository.saveAnalysisResult(
                reportId,
                currentUserService.currentUserIdOrNull(),
                selected.question(),
                selected,
                "chat",
                aiClient.isExternalLlmEnabled() ? aiClient.activeLlmModel() : "rule-based-local-advisor",
                selected.citations()
        );
        return selected;
    }

    public List<AwrDtos.ChatHistoryResponse> listChatHistory(Long reportId) {
        getReportRecord(reportId);
        return repository.findChatHistory(
                reportId,
                currentUserService.currentUserIdOrNull(),
                currentUserService.isCurrentUserAdmin()
        );
    }

    public List<AwrDtos.SqlMetricResponse> listSql(Long reportId) {
        getReportRecord(reportId);
        return repository.findSqlMetrics(reportId);
    }

    public AwrDtos.SqlMetricResponse getSql(Long reportId, String sqlId) {
        getReportRecord(reportId);
        return repository.findSqlMetrics(reportId).stream()
                .filter(metric -> metric.sqlId().equalsIgnoreCase(sqlId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SQL_ID를 찾을 수 없습니다: " + sqlId));
    }

    public AwrDtos.AnalysisResponse getAnalysis(Long analysisId) {
        return repository.findAnalysis(analysisId, currentUserService.currentUserIdOrNull())
                .orElseThrow(() -> new IllegalArgumentException("분석 결과를 찾을 수 없습니다: " + analysisId));
    }

    public AwrDtos.StatusResponse updateWorkerStatus(Long reportId, AwrDtos.WorkerStatusRequest request) {
        AwrRepository.ReportRecord report = getReportRecordInternal(reportId);
        String status = request == null || request.status() == null || request.status().isBlank()
                ? report.status()
                : request.status().trim().toUpperCase();
        List<String> warnings = mergeWarnings(report.warnings(), request == null ? List.of() : request.warnings());
        repository.updateReportStatus(reportId, status, warnings);
        return buildStatus(reportId, getReportRecordInternal(reportId));
    }

    public AwrDtos.StatusResponse completeWorkerExtraction(Long reportId, AwrDtos.WorkerExtractionRequest request) throws IOException {
        AwrRepository.ReportRecord report = getReportRecordInternal(reportId);
        if (request == null || request.textPath() == null || request.textPath().isBlank()) {
            throw new IllegalArgumentException("worker extraction textPath가 필요합니다.");
        }

        repository.updateReportStatus(reportId, "INDEXING", mergeWarnings(report.warnings(), request.warnings()));

        Path textPath = Paths.get(request.textPath()).toAbsolutePath().normalize();
        String extractedText = Files.readString(textPath);
        AwrParser.ParsedAwr parsed = parser.parse(report.filename(), extractedText);
        String status = extractedText.isBlank() || extractedText.length() < 80
                ? "NEEDS_TEXT_EXTRACTION"
                : "INDEXED";
        List<String> warnings = mergeWarnings(report.warnings(), request.warnings());

        repository.updateReport(
                reportId,
                parsed.header(),
                status,
                report.rawFilePath(),
                textPath.toString(),
                parsed.rawTextPreview(),
                warnings
        );
        repository.replaceSections(reportId, parsed.sections());
        repository.replaceSqlMetrics(reportId, parsed.sqlMetrics());
        repository.replaceWaitEvents(reportId, parsed.waitEvents());
        if ("INDEXED".equals(status)) {
            ragService.indexReport(reportId, parsed.sections(), parsed.sqlMetrics(), parsed.waitEvents());
        }
        return buildStatus(reportId, getReportRecordInternal(reportId));
    }

    private AwrRepository.ReportRecord getReportRecord(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("AWR 리포트 ID가 필요합니다.");
        }
        return repository.findAccessibleReport(
                        reportId,
                        currentUserService.currentUserIdOrNull(),
                        currentUserService.isCurrentUserAdmin()
                )
                .orElseThrow(() -> new IllegalArgumentException("AWR 리포트를 찾을 수 없습니다: " + reportId));
    }

    private AwrRepository.ReportRecord getReportRecordInternal(Long reportId) {
        if (reportId == null) {
            throw new IllegalArgumentException("AWR report ID is required.");
        }
        return repository.findReport(reportId)
                .orElseThrow(() -> new IllegalArgumentException("AWR report not found: " + reportId));
    }

    private String normalizeVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) {
            return "SHARED";
        }
        String normalized = visibility.trim().toUpperCase();
        if ("SHARED".equals(normalized) || "PRIVATE".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("AWR visibility must be SHARED or PRIVATE.");
    }

    private String uploadMessage(String status, AwrRagService.AwrIndexResult indexResult) {
        if ("NEEDS_TEXT_EXTRACTION".equals(status)) {
            return "파일은 저장됐지만 PDF/OCR worker adapter가 필요합니다. AWR HTML/TXT를 업로드하면 구조화 파싱이 바로 동작합니다.";
        }
        if (indexResult.embeddedChunkCount() > 0) {
            return "AWR 텍스트 추출, 섹션 파싱, SQL metric 구조화, embedding 생성, pgvector indexing이 완료되었습니다. embedded_chunks="
                    + indexResult.embeddedChunkCount() + "/" + indexResult.chunkCount()
                    + ", model=" + indexResult.embeddingModel();
        }
        if (aiClient.isEmbeddingEnabled()) {
            return "AWR 텍스트 추출, 섹션 파싱, SQL metric 구조화, RAG chunk 저장은 완료했지만 embedding provider 호출이 실패해 vector는 저장하지 못했습니다.";
        }
        return "AWR 텍스트 추출, 섹션 파싱, SQL metric 구조화, RAG chunk 저장이 완료되었습니다. embedding provider가 없어서 vector는 생성하지 않았습니다.";
    }

    private String currentStep(AwrRepository.ReportRecord report, List<AwrDtos.SqlMetricResponse> sqlMetrics, boolean hasEmbeddedChunks) {
        if ("FAILED".equals(report.status())) {
            return "worker 처리 실패";
        }
        if ("QUEUED".equals(report.status())) {
            return "worker queue 대기";
        }
        if ("EXTRACTING".equals(report.status())) {
            return "worker 텍스트 추출/OCR 진행 중";
        }
        if ("INDEXING".equals(report.status())) {
            return "AWR parser/RAG indexing 진행 중";
        }
        if ("NEEDS_TEXT_EXTRACTION".equals(report.status())) {
            return "PDF/OCR fallback worker 대기";
        }
        if (sqlMetrics.isEmpty()) {
            return "SQL metric parser rule 보강 필요";
        }
        if (hasEmbeddedChunks) {
            return "pgvector RAG 검색 준비 완료";
        }
        return "RAG chunk 저장 완료, embedding provider 미설정";
    }

    private List<String> mergeWarnings(List<String> current, List<String> incoming) {
        List<String> merged = new ArrayList<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (incoming != null) {
            for (String warning : incoming) {
                if (warning != null && !warning.isBlank() && !merged.contains(warning)) {
                    merged.add(warning);
                }
            }
        }
        return merged;
    }

    private String sanitize(String filename) {
        String sanitized = filename.replaceAll("[^A-Za-z0-9가-힣._-]", "_");
        return sanitized.isBlank() ? "awr-report.txt" : sanitized;
    }
}

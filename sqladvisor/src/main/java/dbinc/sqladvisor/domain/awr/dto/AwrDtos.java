package dbinc.sqladvisor.domain.awr.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class AwrDtos {

    private AwrDtos() {
    }

    public record UploadResponse(
            Long id,
            String filename,
            String visibility,
            String status,
            String message
    ) {
    }

    public record ReportSummaryResponse(
            Long id,
            String filename,
            String dbName,
            String instanceName,
            String snapBegin,
            String snapEnd,
            String elapsedTime,
            String dbTime,
            String status,
            LocalDateTime uploadedAt,
            Long uploadedBy,
            String visibility,
            int sectionCount,
            int topSqlCount,
            int waitEventCount
    ) {
    }

    public record ReportDetailResponse(
            Long id,
            String filename,
            String dbName,
            String instanceName,
            String snapBegin,
            String snapEnd,
            String elapsedTime,
            String dbTime,
            String status,
            LocalDateTime uploadedAt,
            Long uploadedBy,
            String visibility,
            String rawTextPreview,
            List<SectionResponse> sections,
            List<SqlMetricResponse> topSql,
            List<WaitEventResponse> topWaitEvents,
            AnalysisResponse latestAnalysis
    ) {
    }

    public record StatusResponse(
            Long reportId,
            String status,
            int progress,
            String currentStep,
            List<String> completedSteps,
            List<String> warnings
    ) {
    }

    public record SectionResponse(
            String sectionName,
            int sectionOrder,
            String rawText,
            Map<String, Object> parsedJson
    ) {
    }

    public record SqlMetricResponse(
            String sqlId,
            String sectionName,
            int rankNo,
            Double elapsedTimeSec,
            Double cpuTimeSec,
            Long bufferGets,
            Long diskReads,
            Long executions,
            Long rowsProcessed,
            Long planHashValue,
            String module,
            String sqlText,
            Double score,
            String interpretationHint
    ) {
    }

    public record WaitEventResponse(
            String waitClass,
            String eventName,
            Double totalWaitTimeSec,
            Double avgWaitMs,
            Double dbTimePercent
    ) {
    }

    public record AnalyzeRequest(
            String question,
            String modelProvider
    ) {
    }

    public record AnalysisResponse(
            Long analysisId,
            Long reportId,
            String question,
            String summary,
            List<FindingResponse> topFindings,
            List<String> missingInputs,
            List<String> citations,
            String model,
            LocalDateTime createdAt
    ) {
    }

    public record FindingResponse(
            int priority,
            String sqlId,
            String symptom,
            List<String> evidence,
            List<String> likelyCauses,
            List<String> recommendedActions,
            List<String> validationSteps,
            String risk,
            String confidence
    ) {
    }

    public record ChatRequest(
            String question,
            String modelProvider
    ) {
    }

    public record ChatResponse(
            Long reportId,
            String question,
            String answer,
            List<String> citations,
            List<SqlMetricResponse> evidenceSql,
            List<WaitEventResponse> evidenceWaitEvents,
            String confidence
    ) {
    }

    public record ChatHistoryResponse(
            Long chatId,
            Long reportId,
            Long userId,
            String question,
            String answer,
            List<String> citations,
            List<SqlMetricResponse> evidenceSql,
            List<WaitEventResponse> evidenceWaitEvents,
            String confidence,
            String model,
            LocalDateTime createdAt
    ) {
    }

    public record SqlTuningRequest(
            String sqlText,
            String question,
            String executionPlan,
            String schemaDdl,
            String existingIndexes,
            String bindSamples
    ) {
    }

    public record SqlTuningResponse(
            Long tuningId,
            Long reportId,
            String sqlId,
            String question,
            SqlTuningRequest input,
            SqlMetricResponse metric,
            String summary,
            List<String> symptoms,
            List<IndexRecommendationResponse> indexRecommendations,
            List<String> rewriteRecommendations,
            List<String> validationSteps,
            List<String> missingInputs,
            List<String> citations,
            String model,
            String confidence,
            LocalDateTime createdAt
    ) {
        public SqlTuningResponse(
                Long tuningId,
                Long reportId,
                String sqlId,
                String question,
                SqlMetricResponse metric,
                String summary,
                List<String> symptoms,
                List<IndexRecommendationResponse> indexRecommendations,
                List<String> rewriteRecommendations,
                List<String> validationSteps,
                List<String> missingInputs,
                List<String> citations,
                String model,
                String confidence,
                LocalDateTime createdAt
        ) {
            this(
                    tuningId,
                    reportId,
                    sqlId,
                    question,
                    null,
                    metric,
                    summary,
                    symptoms,
                    indexRecommendations,
                    rewriteRecommendations,
                    validationSteps,
                    missingInputs,
                    citations,
                    model,
                    confidence,
                    createdAt
            );
        }
    }

    public record IndexRecommendationResponse(
            String tableName,
            List<String> columns,
            String ddlCandidate,
            String reason,
            String expectedBenefit,
            String risk,
            String validationSql
    ) {
    }

    public record AiConfigResponse(
            String llmProvider,
            String embeddingProvider,
            String chatModel,
            String embeddingModel,
            String openaiChatModel,
            String openaiEmbeddingModel,
            String geminiChatModel,
            String geminiEmbeddingModel,
            String internalBaseUrl,
            String internalChatModel,
            String ollamaBaseUrl,
            String ollamaChatModel,
            String ollamaEmbeddingModel,
            boolean externalLlmEnabled,
            boolean llmApiKeyConfigured,
            boolean embeddingApiKeyConfigured,
            List<String> configuredProviders,
            List<String> missingProviderKeys,
            Map<String, String> settingSources,
            List<AiProviderConfigResponse> providerConfigs
    ) {
    }

    public record AiProviderConfigResponse(
            String provider,
            String displayName,
            boolean selectedForChat,
            boolean selectedForEmbedding,
            boolean apiKeyConfigured,
            String apiKeySource,
            String chatModel,
            String chatModelSource,
            String embeddingModel,
            String embeddingModelSource,
            String baseUrl,
            String baseUrlSource
    ) {
    }

    public record AiConfigUpdateRequest(
            String llmProvider,
            String embeddingProvider,
            String openaiApiKey,
            String openaiChatModel,
            String openaiEmbeddingModel,
            String geminiApiKey,
            String geminiChatModel,
            String geminiEmbeddingModel,
            String internalApiKey,
            String internalBaseUrl,
            String internalChatModel,
            String ollamaBaseUrl,
            String ollamaChatModel,
            String ollamaEmbeddingModel,
            Boolean clearOpenaiApiKey,
            Boolean clearGeminiApiKey,
            Boolean clearInternalApiKey
    ) {
    }

    public record AiModelOptionsResponse(
            List<String> openaiChatModels,
            List<String> openaiEmbeddingModels,
            List<String> geminiChatModels,
            List<String> geminiEmbeddingModels,
            List<String> internalChatModels,
            List<String> ollamaChatModels,
            List<String> ollamaEmbeddingModels,
            List<String> warnings
    ) {
    }

    public record WorkerStatusRequest(
            String status,
            List<String> warnings
    ) {
    }

    public record WorkerExtractionRequest(
            String textPath,
            List<String> warnings
    ) {
    }
}

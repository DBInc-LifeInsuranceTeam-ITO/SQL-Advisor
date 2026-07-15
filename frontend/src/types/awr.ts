export type ReportVisibility = 'SHARED' | 'PRIVATE'

export interface UploadResponse {
  id: number
  filename: string
  visibility: ReportVisibility
  status: string
  message: string
}

export interface ReportSummaryResponse {
  id: number
  filename: string
  dbName: string
  instanceName: string
  snapBegin?: string | null
  snapEnd?: string | null
  elapsedTime?: string | null
  dbTime?: string | null
  status: string
  uploadedAt: string
  uploadedBy?: number | null
  uploadedByName?: string | null
  visibility: ReportVisibility
  sectionCount: number
  topSqlCount: number
  waitEventCount: number
}

export interface ReportDetailResponse extends ReportSummaryResponse {
  rawTextPreview: string
  sections: SectionResponse[]
  topSql: SqlMetricResponse[]
  topWaitEvents: WaitEventResponse[]
  latestAnalysis?: AnalysisResponse | null
}

export interface StatusResponse {
  reportId: number
  status: string
  progress: number
  currentStep: string
  completedSteps: string[]
  warnings: string[]
}

export interface SectionResponse {
  sectionName: string
  sectionOrder: number
  rawText: string
  parsedJson: Record<string, unknown>
}

export interface SqlMetricResponse {
  sqlId: string
  sectionName: string
  rankNo: number
  elapsedTimeSec?: number | null
  cpuTimeSec?: number | null
  bufferGets?: number | null
  diskReads?: number | null
  executions?: number | null
  rowsProcessed?: number | null
  planHashValue?: number | null
  module?: string | null
  sqlText?: string | null
  score?: number | null
  interpretationHint: string
}

export interface DirectTopSqlOptions {
  source?: 'CURRENT' | 'HISTORY'
  limit?: 20 | 50 | 100
  sortBy?: 'ELAPSED' | 'BUFFER_GETS' | 'DISK_READS' | 'EXECUTIONS'
  startTime?: string
  endTime?: string
  schema?: string
  module?: string
  program?: string
}

export interface WaitEventResponse {
  waitClass: string
  eventName: string
  totalWaitTimeSec?: number | null
  avgWaitMs?: number | null
  dbTimePercent?: number | null
}

export interface AnalysisResponse {
  analysisId: number
  reportId: number
  question: string
  summary: string
  topFindings: FindingResponse[]
  missingInputs: string[]
  citations: string[]
  model: string
  createdAt: string
}

export interface FindingResponse {
  priority: number
  sqlId: string | null
  symptom: string
  evidence: string[]
  likelyCauses: string[]
  recommendedActions: string[]
  validationSteps: string[]
  risk: string
  confidence: string
}

export interface ChatResponse {
  reportId: number
  question: string
  answer: string
  citations: string[]
  evidenceSql: SqlMetricResponse[]
  evidenceWaitEvents: WaitEventResponse[]
  confidence: string
}

export interface ChatHistoryResponse extends ChatResponse {
  chatId: number
  userId?: number | null
  model?: string | null
  createdAt: string
}

export interface SqlTuningRequest {
  sqlText?: string
  question?: string
  executionPlan?: string
  schemaDdl?: string
  existingIndexes?: string
  bindSamples?: string
}

export interface SqlTuningQuestionRequest {
  question: string
}

export interface SqlTuningQuestionResponse {
  questionId: number
  tuningId: number
  question: string
  answer: string
  citations: string[]
  model: string
  confidence: string
  createdAt: string
}

export interface SqlTuningResponse {
  tuningId: number
  reportId?: number | null
  sqlId: string
  question: string
  input?: SqlTuningRequest | null
  metric: SqlMetricResponse
  summary: string
  symptoms: string[]
  indexRecommendations: IndexRecommendationResponse[]
  rewriteRecommendations: string[]
  validationSteps: string[]
  missingInputs: string[]
  citations: string[]
  model: string
  confidence: string
  createdAt: string
}

export interface IndexRecommendationResponse {
  tableName?: string | null
  columns: string[]
  ddlCandidate?: string | null
  buildSteps?: string[]
  postCreateSteps?: string[]
  reason: string
  expectedBenefit: string
  risk: string
  validationSql: string
}

export interface TargetDbConnectionRequest {
  name?: string
  dbType?: string
  jdbcUrl?: string
  username?: string
  password?: string
  visibility?: string
  monitoringEnabled?: boolean
  monitoringIntervalSec?: number
}

export interface TargetDbConnectionTestRequest {
  dbType?: string
  jdbcUrl?: string
  username?: string
  password?: string
}

export interface TargetDbConnectionResponse {
  id: number
  name: string
  dbType: string
  jdbcUrl: string
  username: string
  visibility: string
  monitoringEnabled: boolean
  monitoringIntervalSec: number
  createdAt: string
  updatedAt: string
}

export interface TargetDbConnectionTestResponse {
  success: boolean
  message: string
  databaseProductName?: string | null
  databaseProductVersion?: string | null
  capabilities?: string[]
  warnings?: string[]
}

export interface DirectTuningRequest {
  connectionId?: number | null
  sqlId?: string
  sqlText?: string
}

export interface DirectDbContextResponse {
  connectionId: number
  connectionName: string
  metric: SqlMetricResponse
  input: SqlTuningRequest
  warnings: string[]
  collectedAt: string
}

export interface AiConfigResponse {
  llmProvider: string
  embeddingProvider: string
  chatModel: string
  embeddingModel: string
  openaiChatModel: string
  openaiEmbeddingModel: string
  geminiChatModel: string
  geminiEmbeddingModel: string
  internalBaseUrl: string
  internalChatModel: string
  internalEmbeddingBaseUrl: string
  internalEmbeddingModel: string
  ollamaBaseUrl: string
  ollamaChatModel: string
  ollamaEmbeddingModel: string
  externalLlmEnabled: boolean
  llmApiKeyConfigured: boolean
  embeddingApiKeyConfigured: boolean
  configuredProviders: string[]
  missingProviderKeys: string[]
  settingSources: Record<string, ConfigSource>
  providerConfigs: AiProviderConfigResponse[]
}

export type ConfigSource = 'web' | 'env' | 'builtin' | 'none' | string

export interface AiProviderConfigResponse {
  provider: string
  displayName: string
  selectedForChat: boolean
  selectedForEmbedding: boolean
  apiKeyConfigured: boolean
  apiKeySource: ConfigSource
  chatModel: string
  chatModelSource: ConfigSource
  embeddingModel: string
  embeddingModelSource: ConfigSource
  baseUrl: string
  baseUrlSource: ConfigSource
}

export interface AiConfigUpdateRequest {
  llmProvider: string
  embeddingProvider: string
  openaiApiKey?: string
  openaiChatModel?: string
  openaiEmbeddingModel?: string
  geminiApiKey?: string
  geminiChatModel?: string
  geminiEmbeddingModel?: string
  internalApiKey?: string
  internalBaseUrl?: string
  internalChatModel?: string
  internalEmbeddingBaseUrl?: string
  internalEmbeddingModel?: string
  ollamaBaseUrl?: string
  ollamaChatModel?: string
  ollamaEmbeddingModel?: string
  clearOpenaiApiKey?: boolean
  clearGeminiApiKey?: boolean
  clearInternalApiKey?: boolean
}

export interface AiModelOptionsResponse {
  openaiChatModels: string[]
  openaiEmbeddingModels: string[]
  geminiChatModels: string[]
  geminiEmbeddingModels: string[]
  internalChatModels: string[]
  internalEmbeddingModels: string[]
  ollamaChatModels: string[]
  ollamaEmbeddingModels: string[]
  warnings: string[]
}

export interface DeleteReportResponse {
  reportId: number
  deleted: boolean
  deletedFiles: string[]
  warnings: string[]
}

export interface UploadResponse {
  id: number
  filename: string
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
  sqlId: string
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
  model?: string | null
  createdAt: string
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
  ollamaBaseUrl?: string
  ollamaChatModel?: string
  ollamaEmbeddingModel?: string
  clearOpenaiApiKey?: boolean
  clearGeminiApiKey?: boolean
}

export interface AiModelOptionsResponse {
  openaiChatModels: string[]
  openaiEmbeddingModels: string[]
  geminiChatModels: string[]
  geminiEmbeddingModels: string[]
  ollamaChatModels: string[]
  ollamaEmbeddingModels: string[]
  warnings: string[]
}

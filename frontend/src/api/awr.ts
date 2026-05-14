import api from '@/services/api'
import type {
  AnalysisResponse,
  AiConfigResponse,
  AiModelOptionsResponse,
  AiConfigUpdateRequest,
  ChatHistoryResponse,
  ChatResponse,
  ReportDetailResponse,
  ReportSummaryResponse,
  SqlMetricResponse,
  StatusResponse,
  UploadResponse
} from '@/types/awr'

export async function uploadAwrReport(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  const response = await api.post<UploadResponse>('/reports', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
  return response.data
}

export async function getAiConfig() {
  const response = await api.get<AiConfigResponse>('/config/ai')
  return response.data
}

export async function updateAiConfig(payload: AiConfigUpdateRequest) {
  const response = await api.post<AiConfigResponse>('/config/ai', payload)
  return response.data
}

export async function getAiModelOptions() {
  const response = await api.get<AiModelOptionsResponse>('/config/ai/models')
  return response.data
}

export async function getAwrReports() {
  const response = await api.get<ReportSummaryResponse[]>('/reports')
  return response.data
}

export async function getAwrReport(reportId: number) {
  const response = await api.get<ReportDetailResponse>(`/reports/${reportId}`)
  return response.data
}

export async function getAwrStatus(reportId: number) {
  const response = await api.get<StatusResponse>(`/reports/${reportId}/status`)
  return response.data
}

export async function analyzeAwrReport(reportId: number, question?: string) {
  const response = await api.post<AnalysisResponse>(`/reports/${reportId}/analyze`, {
    question: question || '이 AWR에서 제일 먼저 봐야 할 SQL과 병목을 분석해줘',
    modelProvider: 'local-rule-advisor'
  })
  return response.data
}

export async function chatWithAwr(reportId: number, question: string) {
  const response = await api.post<ChatResponse>(`/reports/${reportId}/chat`, {
    question,
    modelProvider: 'local-rule-advisor'
  })
  return response.data
}

export async function getAwrChatHistory(reportId: number) {
  const response = await api.get<ChatHistoryResponse[]>(`/reports/${reportId}/chat/history`)
  return response.data
}

export async function getAwrSql(reportId: number) {
  const response = await api.get<SqlMetricResponse[]>(`/reports/${reportId}/sql`)
  return response.data
}

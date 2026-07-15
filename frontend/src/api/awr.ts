import api from '@/services/api'
import type {
  AnalysisResponse,
  AiConfigResponse,
  AiModelOptionsResponse,
  AiConfigUpdateRequest,
  ChatHistoryResponse,
  ChatResponse,
  ReportVisibility,
  ReportDetailResponse,
  ReportSummaryResponse,
  SqlTuningRequest,
  SqlTuningResponse,
  SqlMetricResponse,
  StatusResponse,
  UploadResponse,
  DeleteReportResponse
} from '@/types/awr'

export async function uploadAwrReport(file: File, visibility: ReportVisibility) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('visibility', visibility)
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
    question: question || '이 AWR 리포트의 전체 부하 특성, 주요 대기 이벤트, 병목 의심 지점과 Top SQL의 수행시간 특성을 일반적인 관점에서 리뷰해줘. 인덱스 생성, SQL 재작성, 튜닝 우선순위는 제시하지 말아줘.',
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

export async function tuneAwrSql(reportId: number, sqlId: string, payload?: SqlTuningRequest) {
  const response = await api.post<SqlTuningResponse>(`/reports/${reportId}/sql/${sqlId}/tune`, payload || {})
  return response.data
}

export async function getLatestAwrSqlTuning(reportId: number, sqlId: string) {
  const response = await api.get<SqlTuningResponse | { success: boolean }>(`/reports/${reportId}/sql/${sqlId}/tuning/latest`)
  return 'success' in response.data ? null : response.data
}

export async function getAwrSqlTuningHistory(reportId: number) {
  const response = await api.get<SqlTuningResponse[]>(`/reports/${reportId}/sql/tuning/history`)
  return response.data
}

export async function deleteAwrReport(reportId: number) {
  const response = await api.delete<DeleteReportResponse>(`/reports/${reportId}`)
  return response.data
}

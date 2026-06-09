import api from '@/services/api'
import type {
  DirectDbContextResponse,
  DirectTopSqlOptions,
  DirectTuningRequest,
  SqlMetricResponse,
  SqlTuningQuestionRequest,
  SqlTuningQuestionResponse,
  SqlTuningRequest,
  SqlTuningResponse,
  TargetDbConnectionRequest,
  TargetDbConnectionResponse,
  TargetDbConnectionTestRequest,
  TargetDbConnectionTestResponse
} from '@/types/awr'

export async function tuneSql(payload: SqlTuningRequest) {
  const response = await api.post<SqlTuningResponse>('/sql-tuning', payload)
  return response.data
}

export async function getSqlTuningHistory() {
  const response = await api.get<SqlTuningResponse[]>('/sql-tuning/history')
  return response.data
}

export async function getSqlTuning(tuningId: number) {
  const response = await api.get<SqlTuningResponse>(`/sql-tuning/${tuningId}`)
  return response.data
}

export async function getSqlTuningQuestions(tuningId: number) {
  const response = await api.get<SqlTuningQuestionResponse[]>(`/sql-tuning/${tuningId}/questions`)
  return response.data
}

export async function askSqlTuningQuestion(tuningId: number, payload: SqlTuningQuestionRequest) {
  const response = await api.post<SqlTuningQuestionResponse>(`/sql-tuning/${tuningId}/questions`, payload)
  return response.data
}

export async function getTargetDbConnections() {
  const response = await api.get<TargetDbConnectionResponse[]>('/db-connections')
  return response.data
}

export async function createTargetDbConnection(payload: TargetDbConnectionRequest) {
  const response = await api.post<TargetDbConnectionResponse>('/db-connections', payload)
  return response.data
}

export async function testTargetDbConnection(payload: TargetDbConnectionRequest) {
  const request: TargetDbConnectionTestRequest = {
    dbType: payload.dbType,
    jdbcUrl: payload.jdbcUrl,
    username: payload.username,
    password: payload.password
  }
  const response = await api.post<TargetDbConnectionTestResponse>('/db-connections/test', request)
  return response.data
}

export async function testSavedTargetDbConnection(connectionId: number) {
  const response = await api.post<TargetDbConnectionTestResponse>(`/db-connections/${connectionId}/test`)
  return response.data
}

export async function deleteTargetDbConnection(connectionId: number) {
  await api.delete(`/db-connections/${connectionId}`)
}

export async function collectDirectDbContext(payload: DirectTuningRequest) {
  const response = await api.post<DirectDbContextResponse>('/sql-tuning/direct/context', payload)
  return response.data
}

export async function tuneDirectSql(payload: DirectTuningRequest) {
  const response = await api.post<SqlTuningResponse>('/sql-tuning/direct', payload)
  return response.data
}

export async function getDirectTopSql(connectionId: number, options: DirectTopSqlOptions = {}) {
  const response = await api.get<SqlMetricResponse[]>('/sql-tuning/direct/top-sql', {
    params: {
      connectionId,
      ...options
    }
  })
  return response.data
}

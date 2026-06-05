import api from '@/services/api'
import type { SqlTuningRequest, SqlTuningResponse } from '@/types/awr'

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

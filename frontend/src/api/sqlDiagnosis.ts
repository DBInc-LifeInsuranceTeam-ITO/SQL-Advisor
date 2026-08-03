import api from '@/services/api'
import type { TargetDbConnectionResponse } from '@/types/awr'
import type { DirectSqlMetricListResponse, SqlDiagnosisResponse } from '@/types/sqlDiagnosis'

export async function getDiagnosisConnections() {
  const response = await api.get<TargetDbConnectionResponse[]>('/db-connections')
  return response.data
}

export async function getDetailedTopSql(connectionId: number, limit: 20 | 50 | 100 = 20) {
  const response = await api.get<DirectSqlMetricListResponse>('/sql-tuning/direct/top-sql/metrics', {
    params: { connectionId, limit, sortBy: 'TOTAL_ELAPSED_TIME' }
  })
  return response.data
}

export async function getSqlDiagnosis(connectionId: number, sqlId: string, childNumber?: number | null) {
  const response = await api.get<SqlDiagnosisResponse>('/sql-tuning/direct/diagnosis', {
    params: { connectionId, sqlId, childNumber: childNumber ?? undefined }
  })
  return response.data
}

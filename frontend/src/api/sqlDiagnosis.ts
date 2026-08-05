import api from '@/services/api'
import type { TargetDbConnectionResponse } from '@/types/awr'
import type { DirectSqlMetricListResponse, SqlDiagnosisResponse } from '@/types/sqlDiagnosis'

export async function getDiagnosisConnections() {
  const response = await api.get<TargetDbConnectionResponse[]>('/db-connections')
  return response.data
}

export async function getDetailedTopSql(
  connectionId: number,
  _limit?: number,
  sortBy = 'TOTAL_ELAPSED_TIME'
) {
  const response = await api.get<DirectSqlMetricListResponse>('/sql-tuning/direct/top-sql/metrics', {
    params: { connectionId, sortBy }
  })
  return response.data
}

export async function getSqlDiagnosis(connectionId: number, sqlId: string, childNumber?: number | null) {
  const response = await api.get<SqlDiagnosisResponse>('/sql-tuning/direct/diagnosis', {
    params: { connectionId, sqlId, childNumber: childNumber ?? undefined }
  })
  return response.data
}

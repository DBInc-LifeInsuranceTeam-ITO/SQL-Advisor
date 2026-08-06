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

type SqlLoadSnapshot = Pick<SqlMetricResponse, 'elapsedTimeSec' | 'cpuTimeSec' | 'bufferGets' | 'diskReads' | 'executions' | 'rowsProcessed'>

const currentLoadSnapshots = new Map<number, Map<string, SqlLoadSnapshot>>()

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
  if (options.source === 'CURRENT') {
    return getCurrentLoadTopSql(connectionId, options)
  }

  const response = await api.get<SqlMetricResponse[]>('/sql-tuning/direct/top-sql', {
    params: {
      connectionId,
      ...options
    }
  })
  return response.data
}

async function getCurrentLoadTopSql(connectionId: number, options: DirectTopSqlOptions) {
  const requestedLimit = Number(options.limit || 20)
  const candidateLimit = 100
  const sortCriteria = ['ELAPSED', 'BUFFER_GETS', 'DISK_READS', 'EXECUTIONS'] as const
  const responses = await Promise.all(sortCriteria.map(sortBy =>
    api.get<SqlMetricResponse[]>('/sql-tuning/direct/top-sql', {
      params: {
        connectionId,
        source: 'CURRENT',
        limit: candidateLimit,
        sortBy
      }
    })
  ))

  const candidates = new Map<string, SqlMetricResponse>()
  for (const response of responses) {
    for (const row of response.data) {
      if (row.sqlId && !candidates.has(row.sqlId)) candidates.set(row.sqlId, row)
    }
  }

  const previous = currentLoadSnapshots.get(connectionId)
  const current = new Map<string, SqlLoadSnapshot>()
  for (const row of candidates.values()) current.set(row.sqlId, snapshot(row))
  currentLoadSnapshots.set(connectionId, current)

  if (!previous) return []

  return [...candidates.values()]
    .map(row => toLoadDelta(row, previous.get(row.sqlId)))
    .filter(row => Number(row.cpuTimeSec || 0) > 0
      || Number(row.elapsedTimeSec || 0) > 0
      || Number(row.bufferGets || 0) > 0
      || Number(row.diskReads || 0) > 0
      || Number(row.executions || 0) > 0)
    .sort((left, right) =>
      Number(right.cpuTimeSec || 0) - Number(left.cpuTimeSec || 0)
      || Number(right.elapsedTimeSec || 0) - Number(left.elapsedTimeSec || 0)
      || Number(right.bufferGets || 0) - Number(left.bufferGets || 0))
    .slice(0, requestedLimit)
}

function snapshot(row: SqlMetricResponse): SqlLoadSnapshot {
  return {
    elapsedTimeSec: Number(row.elapsedTimeSec || 0),
    cpuTimeSec: Number(row.cpuTimeSec || 0),
    bufferGets: Number(row.bufferGets || 0),
    diskReads: Number(row.diskReads || 0),
    executions: Number(row.executions || 0),
    rowsProcessed: Number(row.rowsProcessed || 0)
  }
}

function toLoadDelta(row: SqlMetricResponse, previous?: SqlLoadSnapshot): SqlMetricResponse {
  const before = previous || {
    elapsedTimeSec: Number(row.elapsedTimeSec || 0),
    cpuTimeSec: Number(row.cpuTimeSec || 0),
    bufferGets: Number(row.bufferGets || 0),
    diskReads: Number(row.diskReads || 0),
    executions: Number(row.executions || 0),
    rowsProcessed: Number(row.rowsProcessed || 0)
  }
  return {
    ...row,
    elapsedTimeSec: delta(row.elapsedTimeSec, before.elapsedTimeSec),
    cpuTimeSec: delta(row.cpuTimeSec, before.cpuTimeSec),
    bufferGets: Math.round(delta(row.bufferGets, before.bufferGets)),
    diskReads: Math.round(delta(row.diskReads, before.diskReads)),
    executions: Math.round(delta(row.executions, before.executions)),
    rowsProcessed: Math.round(delta(row.rowsProcessed, before.rowsProcessed))
  }
}

function delta(current?: number | null, previous?: number | null) {
  return Math.max(0, Number(current || 0) - Number(previous || 0))
}

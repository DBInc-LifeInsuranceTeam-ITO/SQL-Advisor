import api from '@/services/api'

export interface MonitoringConnection {
  id: number
  name: string
  status: string
  collectedAt: string
  message: string
}

export interface MonitoringSummary {
  activeSqlCount: number
  longRunningSqlCount: number
  warningSqlCount: number
  blockingSessionCount: number
}

export interface ActivityPoint {
  collectedAt: string
  activeSessions: number
  executions: number
  cpu: number
  io: number
}

export interface PrioritySql {
  riskLevel: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'NORMAL'
  riskLabel: string
  sqlId: string
  username: string
  module: string
  elapsedSec: number
  cpuPercent: number
  bufferGets: number
  diskReads: number
  executions: number
  issueType: string
  issueLabel: string
  waitEvent: string
  sqlText: string
}

export interface MonitoringDashboardResponse {
  connection: MonitoringConnection
  summary: MonitoringSummary
  activity: { points: ActivityPoint[] }
  prioritySql: PrioritySql[]
  issues: {
    longRunning: number
    logicalReads: number
    physicalReads: number
    blocking: number
  }
}

export async function getMonitoringDashboard(connectionId: number) {
  const response = await api.get<MonitoringDashboardResponse>('/monitoring/dashboard', {
    params: { connectionId }
  })
  return response.data
}

<template>
  <div class="realtime-dashboard">
    <header class="dashboard-header">
      <div>
        <span class="eyebrow">REAL-TIME SQL ADVISOR</span>
        <h1>SQL 실시간 모니터링</h1>
        <p>연계 분석에 등록된 DB의 실행 SQL을 5초 주기로 확인합니다.</p>
      </div>

      <div class="header-actions">
        <label class="db-select">
          <span>대상 DB</span>
          <select v-model.number="selectedConnectionId" :disabled="connections.length === 0">
            <option v-if="connections.length === 0" :value="0">등록된 DB 없음</option>
            <option v-for="connection in connections" :key="connection.id" :value="connection.id">
              {{ connection.name }}
            </option>
          </select>
        </label>

        <div class="collector-state" :class="{ error: Boolean(errorMessage) }">
          <span class="live-dot"></span>
          <div>
            <strong>{{ errorMessage ? '수집 실패' : loading ? '수집 중' : '수집 정상' }}</strong>
            <small>{{ errorMessage || `마지막 갱신 ${lastUpdated}` }}</small>
          </div>
        </div>
      </div>
    </header>

    <section class="summary-grid">
      <article v-for="item in summaries" :key="item.label" class="summary-card" :class="item.tone">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.description }}</small>
      </article>
    </section>

    <section class="dashboard-grid">
      <article class="panel activity-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">DB ACTIVITY</span>
            <h2>실시간 DB 활동 추이</h2>
          </div>
          <div class="metric-tabs">
            <button
              v-for="metric in metricOptions"
              :key="metric.key"
              type="button"
              :class="{ active: selectedMetric === metric.key }"
              @click="selectedMetric = metric.key"
            >
              {{ metric.label }}
            </button>
          </div>
        </div>

        <div class="chart-summary">
          <div><span>현재</span><strong>{{ currentMetricValue }}</strong></div>
          <div><span>최근 평균</span><strong>{{ averageMetricValue }}</strong></div>
          <div><span>최고</span><strong>{{ maxMetricValue }}</strong></div>
        </div>

        <div class="line-chart" aria-label="실시간 DB 활동 차트">
          <div class="chart-grid-lines"><i v-for="line in 5" :key="line"></i></div>
          <svg viewBox="0 0 720 220" preserveAspectRatio="none">
            <defs>
              <linearGradient id="areaGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#16a34a" stop-opacity="0.28" />
                <stop offset="100%" stop-color="#16a34a" stop-opacity="0" />
              </linearGradient>
            </defs>
            <path :d="areaPath" fill="url(#areaGradient)" />
            <polyline :points="chartPoints" fill="none" stroke="#0b8f49" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <div class="chart-labels">
            <span v-for="(label, index) in timeLabels" :key="`${label}-${index}`">{{ label }}</span>
          </div>
        </div>
      </article>

      <article class="panel priority-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">PRIORITY SQL</span>
            <h2>지금 확인해야 할 SQL</h2>
          </div>
          <span class="reference-badge">실행 중 SQL 기준</span>
        </div>

        <div v-if="prioritySql.length === 0" class="empty-panel">
          현재 실행 중인 점검 대상 SQL이 없습니다.
        </div>
        <div v-else class="sql-table-wrap">
          <table class="sql-table">
            <thead>
              <tr>
                <th>위험도</th><th>SQL ID</th><th>경과</th><th>CPU</th><th>Buffer Gets</th><th>문제 유형</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="sql in prioritySql" :key="`${sql.sqlId}-${sql.username}`">
                <td><span class="severity" :class="severityClass(sql.riskLevel)">{{ sql.riskLabel }}</span></td>
                <td><strong class="sql-id">{{ sql.sqlId }}</strong><small>{{ sql.username }}</small></td>
                <td>{{ formatSeconds(sql.elapsedSec) }}</td>
                <td>{{ sql.cpuPercent.toFixed(1) }}%</td>
                <td>{{ formatCompact(sql.bufferGets) }}</td>
                <td><span class="issue-tag">{{ sql.issueLabel }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getTargetDbConnections } from '@/api/sqlTuning'
import { getMonitoringDashboard, type MonitoringDashboardResponse } from '@/api/monitoring'
import type { TargetDbConnectionResponse } from '@/types/awr'

type MetricKey = 'activeSessions' | 'executions' | 'cpu' | 'io'

const connections = ref<TargetDbConnectionResponse[]>([])
const selectedConnectionId = ref(0)
const dashboard = ref<MonitoringDashboardResponse | null>(null)
const selectedMetric = ref<MetricKey>('activeSessions')
const loading = ref(false)
const errorMessage = ref('')
let timer: number | undefined

const metricOptions: { key: MetricKey; label: string }[] = [
  { key: 'activeSessions', label: 'Active Sessions' },
  { key: 'executions', label: 'Executions' },
  { key: 'cpu', label: 'CPU' },
  { key: 'io', label: 'I/O' }
]

const activityPoints = computed(() => dashboard.value?.activity.points || [])
const selectedSeries = computed(() => activityPoints.value.map(point => point[selectedMetric.value]))
const lastUpdated = computed(() => formatTime(dashboard.value?.connection.collectedAt))
const prioritySql = computed(() => dashboard.value?.prioritySql || [])

const summaries = computed(() => [
  { label: '현재 실행 SQL', value: `${dashboard.value?.summary.activeSqlCount || 0}건`, description: 'Active 세션 기준', tone: 'normal' },
  { label: '장기 실행 SQL', value: `${dashboard.value?.summary.longRunningSqlCount || 0}건`, description: '30초 이상 수행', tone: 'warning' },
  { label: '주의 SQL', value: `${dashboard.value?.summary.warningSqlCount || 0}건`, description: '임계값 초과', tone: 'danger' },
  { label: 'Blocking 세션', value: `${dashboard.value?.summary.blockingSessionCount || 0}건`, description: '즉시 확인 필요', tone: 'danger' }
])

const currentMetricValue = computed(() => formatMetric(selectedSeries.value.at(-1) || 0))
const averageMetricValue = computed(() => {
  if (selectedSeries.value.length === 0) return formatMetric(0)
  return formatMetric(selectedSeries.value.reduce((sum, value) => sum + value, 0) / selectedSeries.value.length)
})
const maxMetricValue = computed(() => formatMetric(Math.max(...selectedSeries.value, 0)))

const chartPoints = computed(() => {
  const values = selectedSeries.value.length > 1 ? selectedSeries.value : [0, selectedSeries.value[0] || 0]
  const max = Math.max(...values, 1)
  return values.map((value, index) => `${(index / (values.length - 1)) * 720},${205 - (value / max) * 175}`).join(' ')
})
const areaPath = computed(() => `M 0 220 L ${chartPoints.value.replaceAll(' ', ' L ')} L 720 220 Z`)
const timeLabels = computed(() => activityPoints.value.map(point => formatTime(point.collectedAt)))

async function loadConnections() {
  try {
    connections.value = await getTargetDbConnections()
    const monitored = connections.value.find(item => item.monitoringEnabled)
    selectedConnectionId.value = monitored?.id || connections.value[0]?.id || 0
  } catch (error) {
    errorMessage.value = extractError(error)
  }
}

async function refreshDashboard() {
  if (!selectedConnectionId.value || loading.value) return
  loading.value = true
  try {
    dashboard.value = await getMonitoringDashboard(selectedConnectionId.value)
    errorMessage.value = ''
  } catch (error) {
    errorMessage.value = extractError(error)
  } finally {
    loading.value = false
  }
}

function restartPolling() {
  if (timer) window.clearInterval(timer)
  dashboard.value = null
  void refreshDashboard()
  timer = window.setInterval(() => void refreshDashboard(), 5000)
}

function formatMetric(value: number) {
  if (selectedMetric.value === 'cpu') return `${value.toFixed(1)}초`
  return Math.round(value).toLocaleString()
}
function formatSeconds(value: number) { return `${value.toLocaleString()}초` }
function formatCompact(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`
  if (value >= 1_000) return `${Math.round(value / 1_000)}K`
  return value.toLocaleString()
}
function formatTime(value?: string) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value))
}
function severityClass(level: string) {
  if (level === 'CRITICAL') return 'critical'
  if (level === 'HIGH') return 'high'
  return 'medium'
}
function extractError(error: unknown) {
  if (typeof error === 'object' && error && 'message' in error) return String(error.message)
  return '실시간 데이터 조회에 실패했습니다.'
}

watch(selectedConnectionId, value => { if (value) restartPolling() })
onMounted(async () => { await loadConnections() })
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style src="./AwrDashboard.css"></style>
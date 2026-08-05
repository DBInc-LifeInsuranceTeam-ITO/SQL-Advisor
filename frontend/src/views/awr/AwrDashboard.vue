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

    <section class="main-grid">
      <article class="panel activity-panel">
        <div class="panel-title-row">
          <div><span class="panel-kicker">DB ACTIVITY</span><h2>실시간 DB 활동 추이</h2></div>
          <div class="metric-tabs">
            <button v-for="metric in metricOptions" :key="metric.key" type="button"
              :class="{ active: selectedMetric === metric.key }" @click="selectedMetric = metric.key">
              {{ metric.label }}
            </button>
          </div>
        </div>

        <div class="chart-summary">
          <div><span>현재</span><strong>{{ currentMetricValue }}</strong></div>
          <div><span>최근 평균</span><strong>{{ averageMetricValue }}</strong></div>
          <div><span>최고</span><strong>{{ maxMetricValue }}</strong></div>
        </div>

        <div class="line-chart">
          <div class="chart-grid-lines"><i v-for="line in 5" :key="line"></i></div>
          <svg viewBox="0 0 720 220" preserveAspectRatio="none">
            <defs><linearGradient id="areaGradient" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#16a34a" stop-opacity="0.28"/><stop offset="100%" stop-color="#16a34a" stop-opacity="0"/></linearGradient></defs>
            <path :d="areaPath" fill="url(#areaGradient)" />
            <polyline :points="chartPoints" fill="none" stroke="#0b8f49" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <div class="chart-labels"><span v-for="label in chartTimeLabels" :key="label">{{ label }}</span></div>
        </div>
      </article>

      <article class="panel top-sql-panel">
        <div class="panel-title-row">
          <div><span class="panel-kicker">TOP SQL</span><h2>부하 상위 SQL</h2></div>
          <span class="badge">총 수행시간 기준</span>
        </div>

        <div v-if="topSql.length === 0" class="empty-panel">수집된 업무 SQL이 없습니다.</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr><th>순위</th><th>SQL ID</th><th>수행시간</th><th>Buffer Gets</th><th>Disk Reads</th><th>실행</th></tr>
            </thead>
            <tbody>
              <tr v-for="(sql, index) in topSql.slice(0, 10)" :key="`${sql.sqlId}-${index}`">
                <td><span class="rank-badge">{{ index + 1 }}</span></td>
                <td><strong class="sql-id">{{ sql.sqlId }}</strong><small>{{ sql.module || sql.sectionName || '모듈 정보 없음' }}</small></td>
                <td>{{ formatMetricNumber(sql.elapsedTimeSec) }}초</td>
                <td>{{ formatCompact(sql.bufferGets || 0) }}</td>
                <td>{{ formatCompact(sql.diskReads || 0) }}</td>
                <td>{{ formatCompact(sql.executions || 0) }}</td>
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
import { getDirectTopSql, getTargetDbConnections } from '@/api/sqlTuning'
import { getMonitoringDashboard, type MonitoringDashboardResponse } from '@/api/monitoring'
import type { SqlMetricResponse, TargetDbConnectionResponse } from '@/types/awr'

type MetricKey = 'activeSessions' | 'executions' | 'cpu' | 'io'
type ActivityPoint = MonitoringDashboardResponse['activity']['points'][number]

const CHART_SLOT_COUNT = 12
const CHART_INTERVAL_MS = 5000

const connections = ref<TargetDbConnectionResponse[]>([])
const selectedConnectionId = ref(0)
const dashboard = ref<MonitoringDashboardResponse | null>(null)
const topSql = ref<SqlMetricResponse[]>([])
const selectedMetric = ref<MetricKey>('activeSessions')
const loading = ref(false)
const errorMessage = ref('')
let timer: number | undefined
let topSqlTick = 0

const metricOptions: { key: MetricKey; label: string }[] = [
  { key: 'activeSessions', label: 'Active Sessions' },
  { key: 'executions', label: 'Executions' },
  { key: 'cpu', label: 'CPU' },
  { key: 'io', label: 'I/O' }
]

const activityPoints = computed(() => dashboard.value?.activity.points || [])
const chartWindow = computed(() => buildChartWindow(activityPoints.value))
const selectedSeries = computed(() => chartWindow.value.map(point => point[selectedMetric.value]))
const lastUpdated = computed(() => formatTime(dashboard.value?.connection.collectedAt))
const summaries = computed(() => [
  { label: '현재 실행 SQL', value: `${dashboard.value?.summary.activeSqlCount || 0}건`, description: 'Active 세션 기준', tone: 'normal' },
  { label: '장기 실행 SQL', value: `${dashboard.value?.summary.longRunningSqlCount || 0}건`, description: '30초 이상 수행', tone: 'warning' },
  { label: '주의 SQL', value: `${dashboard.value?.summary.warningSqlCount || 0}건`, description: '임계값 초과', tone: 'danger' },
  { label: 'Blocking 세션', value: `${dashboard.value?.summary.blockingSessionCount || 0}건`, description: '즉시 확인 필요', tone: 'danger' }
])
const currentMetricValue = computed(() => formatMetric(selectedSeries.value.at(-1) || 0))
const averageMetricValue = computed(() => formatMetric(selectedSeries.value.reduce((sum, value) => sum + value, 0) / CHART_SLOT_COUNT))
const maxMetricValue = computed(() => formatMetric(Math.max(...selectedSeries.value, 0)))
const chartPoints = computed(() => {
  const values = selectedSeries.value
  const max = Math.max(...values, 1)
  return values.map((value, index) => `${(index / (CHART_SLOT_COUNT - 1)) * 720},${205 - (value / max) * 175}`).join(' ')
})
const areaPath = computed(() => `M 0 220 L ${chartPoints.value.replaceAll(' ', ' L ')} L 720 220 Z`)
const chartTimeLabels = computed(() => [0, 2, 4, 6, 8, 10, 11].map(index => formatTime(chartWindow.value[index]?.collectedAt)))

function buildChartWindow(points: ActivityPoint[]): ActivityPoint[] {
  const sorted = [...points].sort((left, right) => new Date(left.collectedAt).getTime() - new Date(right.collectedAt).getTime())
  const end = dashboard.value?.connection.collectedAt ? new Date(dashboard.value.connection.collectedAt).getTime() : Date.now()
  const first = sorted[0]
  const fallback: ActivityPoint = first || {
    collectedAt: new Date(end).toISOString(),
    activeSessions: 0,
    executions: 0,
    cpu: 0,
    io: 0
  }

  let cursor = 0
  let latest = fallback
  return Array.from({ length: CHART_SLOT_COUNT }, (_, index) => {
    const slotTime = end - (CHART_SLOT_COUNT - 1 - index) * CHART_INTERVAL_MS
    while (cursor < sorted.length && new Date(sorted[cursor].collectedAt).getTime() <= slotTime + CHART_INTERVAL_MS / 2) {
      latest = sorted[cursor]
      cursor += 1
    }
    return { ...latest, collectedAt: new Date(slotTime).toISOString() }
  })
}

async function loadConnections() {
  try {
    connections.value = await getTargetDbConnections()
    selectedConnectionId.value = connections.value.find(item => item.monitoringEnabled)?.id || connections.value[0]?.id || 0
  } catch (error) {
    errorMessage.value = extractError(error)
  }
}

async function refreshDashboard() {
  if (!selectedConnectionId.value || loading.value) return
  loading.value = true
  try {
    dashboard.value = await getMonitoringDashboard(selectedConnectionId.value)
    if (topSqlTick++ % 3 === 0) {
      topSql.value = await getDirectTopSql(selectedConnectionId.value, { source: 'CURRENT', limit: 20, sortBy: 'ELAPSED' })
    }
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
  topSql.value = []
  topSqlTick = 0
  void refreshDashboard()
  timer = window.setInterval(() => void refreshDashboard(), CHART_INTERVAL_MS)
}

function formatMetric(value: number) { return selectedMetric.value === 'cpu' ? `${value.toFixed(1)}초` : Math.round(value).toLocaleString() }
function formatCompact(value: number) { if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`; if (value >= 1_000) return `${Math.round(value / 1_000)}K`; return value.toLocaleString() }
function formatMetricNumber(value?: number | null) { return (value || 0).toFixed(1) }
function formatTime(value?: string) { if (!value) return '-'; return new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(new Date(value)) }
function extractError(error: unknown) { return typeof error === 'object' && error && 'message' in error ? String(error.message) : '실시간 데이터 조회에 실패했습니다.' }

watch(selectedConnectionId, value => { if (value) restartPolling() })
onMounted(loadConnections)
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style src="./AwrDashboard.css"></style>

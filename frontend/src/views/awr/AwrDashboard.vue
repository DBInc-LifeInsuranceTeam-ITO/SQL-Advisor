<template>
  <div class="realtime-dashboard">
    <header class="dashboard-header">
      <div>
        <span class="eyebrow">REAL-TIME SQL ADVISOR</span>
        <h1>대시보드</h1>
      </div>
      <div class="collector-state" :class="{ error: Boolean(errorMessage), loading }">
        <span class="live-dot"></span>
        <div>
          <strong>{{ errorMessage ? '수집 실패' : loading ? '수집 중' : '수집 정상' }}</strong>
          <small>{{ errorMessage || `마지막 갱신 ${lastUpdated}` }}</small>
        </div>
      </div>
    </header>

    <nav class="db-tabs" aria-label="대상 DB 선택">
      <button
        v-for="connection in dbTabs"
        :key="connection.id"
        type="button"
        :class="{ active: selectedConnectionId === connection.id }"
        @click="selectedConnectionId = connection.id"
      >
        <span class="db-status" :class="connection.status"></span>
        {{ connection.name }}
      </button>
    </nav>

    <section class="summary-grid">
      <article v-for="item in summaries" :key="item.label" class="summary-card" :class="item.tone">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.description }}</small>
      </article>
    </section>

    <section class="dashboard-body">
      <div class="chart-grid">
        <article v-for="metric in metricCards" :key="metric.key" class="panel metric-card">
          <div class="metric-card-header">
            <div>
              <span class="panel-kicker">{{ metric.kicker }}</span>
              <h2>{{ metric.label }}</h2>
            </div>
            <strong>{{ metric.current }}</strong>
          </div>
          <div class="metric-meta">
            <span>평균 {{ metric.average }}</span>
            <span>최고 {{ metric.max }}</span>
          </div>
          <div class="mini-chart">
            <div class="chart-grid-lines"><i v-for="line in 4" :key="line"></i></div>
            <svg viewBox="0 0 420 120" preserveAspectRatio="none" aria-hidden="true">
              <path :d="metric.areaPath" class="chart-area" />
              <polyline :points="metric.points" class="chart-line" />
            </svg>
            <div class="chart-time"><span>{{ firstChartTime }}</span><span>{{ lastUpdated }}</span></div>
          </div>
        </article>
      </div>

      <article class="panel top-sql-panel">
        <div class="panel-title-row">
          <div><span class="panel-kicker">TOP SQL</span><h2>부하 상위 SQL</h2></div>
          <span class="badge">총 수행시간 기준</span>
        </div>

        <div v-if="topSql.length === 0" class="empty-panel">수집된 업무 SQL이 없습니다.</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr><th>순위</th><th>SQL ID</th><th>수행시간</th><th>Buffer Gets</th><th>실행</th></tr>
            </thead>
            <tbody>
              <tr v-for="(sql, index) in topSql.slice(0, 7)" :key="`${sql.sqlId}-${index}`">
                <td><span class="rank-badge">{{ index + 1 }}</span></td>
                <td><strong class="sql-id">{{ sql.sqlId }}</strong><small>{{ sql.module || sql.sectionName || '모듈 정보 없음' }}</small></td>
                <td>{{ formatMetricNumber(sql.elapsedTimeSec) }}초</td>
                <td>{{ formatCompact(sql.bufferGets || 0) }}</td>
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

const TEST_CONNECTION_ID = -1
const CHART_SLOT_COUNT = 12
const CHART_INTERVAL_MS = 2000

const connections = ref<TargetDbConnectionResponse[]>([])
const selectedConnectionId = ref(TEST_CONNECTION_ID)
const dashboard = ref<MonitoringDashboardResponse | null>(null)
const topSql = ref<SqlMetricResponse[]>([])
const loading = ref(false)
const errorMessage = ref('')
let timer: number | undefined
let topSqlTick = 0
let testTick = 0

const dbTabs = computed(() => [
  { id: TEST_CONNECTION_ID, name: 'TEST', status: 'normal' },
  ...connections.value.map(connection => ({ id: connection.id, name: connection.name, status: 'normal' }))
])
const activityPoints = computed(() => dashboard.value?.activity.points || [])
const chartWindow = computed(() => buildChartWindow(activityPoints.value))
const lastUpdated = computed(() => formatTime(dashboard.value?.connection.collectedAt))
const firstChartTime = computed(() => formatTime(chartWindow.value[0]?.collectedAt))
const summaries = computed(() => [
  { label: '현재 실행 SQL', value: `${dashboard.value?.summary.activeSqlCount || 0}건`, description: 'Active 세션 기준', tone: 'normal' },
  { label: '장기 실행 SQL', value: `${dashboard.value?.summary.longRunningSqlCount || 0}건`, description: '30초 이상 수행', tone: 'warning' },
  { label: '고부하 SQL', value: `${dashboard.value?.summary.warningSqlCount || 0}건`, description: '임계값 초과', tone: 'danger' },
  { label: 'Blocking 세션', value: `${dashboard.value?.summary.blockingSessionCount || 0}건`, description: '즉시 확인 필요', tone: 'danger' }
])

const metricCards = computed(() => [
  buildMetricCard('activeSessions', 'DB ACTIVITY', 'Active Sessions'),
  buildMetricCard('executions', 'SQL THROUGHPUT', 'SQL 실행량'),
  buildMetricCard('cpu', 'DB CPU', 'CPU 사용시간'),
  buildMetricCard('io', 'DB I/O', 'I/O 처리량')
])

function buildMetricCard(key: MetricKey, kicker: string, label: string) {
  const values = chartWindow.value.map(point => Number(point[key] || 0))
  const maxValue = Math.max(...values, 0)
  const ceiling = niceCeiling(maxValue, key)
  const points = values.map((value, index) => `${(index / (CHART_SLOT_COUNT - 1)) * 420},${110 - (value / ceiling) * 94}`).join(' ')
  const areaPath = `M 0 120 L ${points.replaceAll(' ', ' L ')} L 420 120 Z`
  const average = values.reduce((sum, value) => sum + value, 0) / CHART_SLOT_COUNT
  return {
    key,
    kicker,
    label,
    points,
    areaPath,
    current: formatMetric(values.at(-1) || 0, key),
    average: formatMetric(average, key),
    max: formatMetric(maxValue, key)
  }
}

function buildChartWindow(points: ActivityPoint[]): ActivityPoint[] {
  const sorted = [...points].sort((left, right) => parseServerTime(left.collectedAt).getTime() - parseServerTime(right.collectedAt).getTime())
  const end = dashboard.value?.connection.collectedAt ? parseServerTime(dashboard.value.connection.collectedAt).getTime() : Date.now()
  const fallback: ActivityPoint = sorted[0] || {
    collectedAt: new Date(end).toISOString(), activeSessions: 0, executions: 0, cpu: 0, io: 0
  }
  let cursor = 0
  let latest = fallback
  return Array.from({ length: CHART_SLOT_COUNT }, (_, index) => {
    const slotTime = end - (CHART_SLOT_COUNT - 1 - index) * CHART_INTERVAL_MS
    while (cursor < sorted.length && parseServerTime(sorted[cursor].collectedAt).getTime() <= slotTime + CHART_INTERVAL_MS / 2) {
      latest = sorted[cursor]
      cursor += 1
    }
    return { ...latest, collectedAt: new Date(slotTime).toISOString() }
  })
}

function niceCeiling(value: number, metric: MetricKey) {
  if (value <= 0) return metric === 'cpu' ? 1 : metric === 'activeSessions' ? 4 : 100
  const magnitude = 10 ** Math.floor(Math.log10(value))
  const normalized = value / magnitude
  return (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10) * magnitude
}

async function loadConnections() {
  try { connections.value = await getTargetDbConnections() }
  catch { connections.value = [] }
}

async function refreshDashboard() {
  if (loading.value) return
  loading.value = true
  try {
    if (selectedConnectionId.value === TEST_CONNECTION_ID) refreshTestDashboard()
    else {
      dashboard.value = await getMonitoringDashboard(selectedConnectionId.value)
      if (topSqlTick++ % 3 === 0) {
        topSql.value = await getDirectTopSql(selectedConnectionId.value, { source: 'CURRENT', limit: 20, sortBy: 'ELAPSED' })
      }
    }
    errorMessage.value = ''
  } catch (error) { errorMessage.value = extractError(error) }
  finally { loading.value = false }
}

function refreshTestDashboard() {
  const now = Date.now()
  const activePattern = [5, 7, 6, 9, 12, 10, 14, 11, 16, 13, 18, 15]
  const cpuPattern = [0.6, 0.9, 0.7, 1.2, 1.8, 1.4, 2.1, 1.6, 2.5, 2.0, 2.8, 2.3]
  const ioPattern = [120, 180, 150, 260, 420, 310, 560, 440, 720, 610, 840, 760]
  const shift = testTick % CHART_SLOT_COUNT
  const points = Array.from({ length: CHART_SLOT_COUNT }, (_, index) => {
    const patternIndex = (index + shift) % CHART_SLOT_COUNT
    return {
      collectedAt: new Date(now - (CHART_SLOT_COUNT - 1 - index) * CHART_INTERVAL_MS).toISOString(),
      activeSessions: activePattern[patternIndex], executions: 40 + patternIndex * 7,
      cpu: cpuPattern[patternIndex], io: ioPattern[patternIndex]
    }
  })
  dashboard.value = {
    connection: { collectedAt: new Date(now).toISOString() },
    summary: { activeSqlCount: 18, longRunningSqlCount: 3, warningSqlCount: 5, blockingSessionCount: 1 },
    activity: { points }
  } as MonitoringDashboardResponse
  topSql.value = [
    { sqlId: 'testsql00001', module: 'ORDER_BATCH', elapsedTimeSec: 182.4, bufferGets: 1280000, diskReads: 184000, executions: 12 },
    { sqlId: 'testsql00002', module: 'ONLINE_API', elapsedTimeSec: 96.8, bufferGets: 842000, diskReads: 92000, executions: 286 },
    { sqlId: 'testsql00003', module: 'CLAIM_BATCH', elapsedTimeSec: 74.1, bufferGets: 615000, diskReads: 121000, executions: 8 },
    { sqlId: 'testsql00004', module: 'SQL_ADVISOR', elapsedTimeSec: 52.6, bufferGets: 390000, diskReads: 44000, executions: 134 },
    { sqlId: 'testsql00005', module: 'CUSTOMER_API', elapsedTimeSec: 31.9, bufferGets: 248000, diskReads: 18000, executions: 612 },
    { sqlId: 'testsql00006', module: 'REPORT', elapsedTimeSec: 22.7, bufferGets: 176000, diskReads: 9000, executions: 43 }
  ] as SqlMetricResponse[]
  testTick += 1
}

function restartPolling() {
  if (timer) window.clearInterval(timer)
  dashboard.value = null
  topSql.value = []
  topSqlTick = 0
  testTick = 0
  void refreshDashboard()
  timer = window.setInterval(() => void refreshDashboard(), CHART_INTERVAL_MS)
}

function parseServerTime(value: string) {
  if (/[zZ]$|[+-]\d{2}:?\d{2}$/.test(value)) return new Date(value)
  return new Date(`${value}Z`)
}
function formatMetric(value: number, metric: MetricKey) {
  if (metric === 'cpu') return `${value.toFixed(1)}초`
  return Math.round(value).toLocaleString()
}
function formatCompact(value: number) { if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`; if (value >= 1_000) return `${Math.round(value / 1_000)}K`; return value.toLocaleString() }
function formatMetricNumber(value?: number | null) { return (value || 0).toFixed(1) }
function formatTime(value?: string) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(parseServerTime(value))
}
function extractError(error: unknown) { return typeof error === 'object' && error && 'message' in error ? String(error.message) : '실시간 데이터 조회에 실패했습니다.' }

watch(selectedConnectionId, restartPolling)
onMounted(async () => { await loadConnections(); restartPolling() })
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
</script>

<style src="./AwrDashboard.css"></style>

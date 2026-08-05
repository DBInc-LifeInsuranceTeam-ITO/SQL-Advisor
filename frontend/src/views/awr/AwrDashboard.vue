<template>
  <div class="realtime-dashboard">
    <header class="dashboard-header">
      <div>
        <span class="eyebrow">REAL-TIME SQL ADVISOR</span>
        <h1>SQL 실시간 모니터링</h1>
        <p>연계 분석에 등록된 DB의 실행 SQL과 이상 징후를 5초 주기로 확인합니다.</p>
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

      <article class="panel degradation-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">COLLECTION STATUS</span>
            <h2>수집 정보</h2>
          </div>
          <span class="reference-badge">실시간 조회</span>
        </div>
        <div class="degradation-list">
          <article class="degradation-item">
            <div class="degradation-head"><div><strong>{{ selectedConnectionName }}</strong><small>연계 분석 등록 DB</small></div><em>{{ activityPoints.length }}/12</em></div>
            <div class="comparison-row"><span>수집 주기</span><i>→</i><strong>5초</strong></div>
            <div class="progress-track"><span :style="{ width: `${Math.min((activityPoints.length / 12) * 100, 100)}%` }"></span></div>
          </article>
          <article class="degradation-item">
            <div class="degradation-head"><div><strong>성능 비교 기준</strong><small>현재 브랜치 1차 구현</small></div><em>수집 중</em></div>
            <div class="comparison-row"><span>현재 값</span><i>→</i><strong>이력 축적 후 비교</strong></div>
          </article>
        </div>
      </article>

      <article class="panel issue-panel">
        <div class="panel-title-row">
          <div><span class="panel-kicker">DETECTION RULE</span><h2>이상 SQL 탐지 현황</h2></div>
          <button class="text-button" type="button" @click="showRules = !showRules">기준 보기</button>
        </div>
        <div class="issue-list">
          <div v-for="issue in issueSummary" :key="issue.label" class="issue-row">
            <span class="issue-icon" :class="issue.tone">{{ issue.icon }}</span>
            <div><strong>{{ issue.label }}</strong><small>{{ issue.description }}</small></div>
            <em>{{ issue.count }}건</em>
          </div>
        </div>
        <div v-if="showRules" class="rule-box">
          <strong>기본 탐지 기준</strong>
          <p>장기 실행 30초 이상 · Buffer Gets 100,000 이상 · Disk Reads 10,000 이상 · Blocking Session 존재</p>
        </div>
      </article>

      <article class="panel recommendation-panel">
        <div class="panel-title-row">
          <div><span class="panel-kicker">LIVE DETAIL</span><h2>현재 수집 상태</h2></div>
          <span class="ai-badge">{{ dashboard?.connection.status || '대기' }}</span>
        </div>
        <div class="recommendation-list">
          <article>
            <span class="green">DB</span>
            <div><strong>{{ selectedConnectionName }}</strong><p>{{ dashboard?.connection.message || '대상 DB를 선택하면 실시간 수집을 시작합니다.' }}</p></div>
          </article>
          <article>
            <span class="blue">SQL</span>
            <div><strong>실행 SQL {{ dashboard?.summary.activeSqlCount || 0 }}건</strong><p>V$SESSION과 V$SQLSTATS에서 현재 실행 정보를 조회합니다.</p></div>
          </article>
          <article>
            <span class="orange">WAIT</span>
            <div><strong>{{ topWaitEvent }}</strong><p>우선 점검 SQL의 현재 대기 이벤트입니다.</p></div>
          </article>
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
const showRules = ref(false)
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
const selectedConnectionName = computed(() => connections.value.find(item => item.id === selectedConnectionId.value)?.name || '-')
const prioritySql = computed(() => dashboard.value?.prioritySql || [])
const topWaitEvent = computed(() => prioritySql.value[0]?.waitEvent || '대기 이벤트 없음')

const summaries = computed(() => [
  { label: '현재 실행 SQL', value: `${dashboard.value?.summary.activeSqlCount || 0}건`, description: 'Active 세션 기준', tone: 'normal' },
  { label: '장기 실행 SQL', value: `${dashboard.value?.summary.longRunningSqlCount || 0}건`, description: '30초 이상 수행', tone: 'warning' },
  { label: '주의 SQL', value: `${dashboard.value?.summary.warningSqlCount || 0}건`, description: '절대 임계값 기준', tone: 'danger' },
  { label: 'Blocking 세션', value: `${dashboard.value?.summary.blockingSessionCount || 0}건`, description: '즉시 확인 필요', tone: 'danger' },
  { label: '수집 상태', value: errorMessage.value ? '실패' : loading.value ? '수집 중' : '정상', description: '5초 주기 갱신', tone: errorMessage.value ? 'danger' : 'success' }
])

const issueSummary = computed(() => [
  { icon: '⏱', label: '장기 실행', description: '30초 이상 실행 중인 SQL', count: dashboard.value?.issues.longRunning || 0, tone: 'red' },
  { icon: '↕', label: '과다 Logical Read', description: 'Buffer Gets 100,000 이상', count: dashboard.value?.issues.logicalReads || 0, tone: 'orange' },
  { icon: '◫', label: 'Physical I/O 과다', description: 'Disk Reads 10,000 이상', count: dashboard.value?.issues.physicalReads || 0, tone: 'blue' },
  { icon: '⚠', label: 'Blocking 발생', description: '다른 세션의 진행을 차단', count: dashboard.value?.issues.blocking || 0, tone: 'purple' }
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

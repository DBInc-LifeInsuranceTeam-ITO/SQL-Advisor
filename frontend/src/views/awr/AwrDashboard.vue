<template>
  <div class="realtime-dashboard">
    <header class="dashboard-header">
      <div>
        <span class="eyebrow">REAL-TIME SQL ADVISOR</span>
        <h1>SQL 실시간 모니터링</h1>
        <p>현재 실행 SQL과 성능 이상 징후를 5초 주기로 확인합니다.</p>
      </div>

      <div class="header-actions">
        <label class="db-select">
          <span>대상 DB</span>
          <select v-model="selectedDb">
            <option value="DBLIFE">DBLIFE</option>
            <option value="DBLIFE-DEV">DBLIFE-DEV</option>
          </select>
        </label>
        <div class="collector-state">
          <span class="live-dot"></span>
          <div>
            <strong>수집 정상</strong>
            <small>마지막 갱신 {{ lastUpdated }}</small>
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
          <div>
            <span>현재</span>
            <strong>{{ currentMetricValue }}</strong>
          </div>
          <div>
            <span>최근 1분 평균</span>
            <strong>{{ averageMetricValue }}</strong>
          </div>
          <div>
            <span>최고</span>
            <strong>{{ maxMetricValue }}</strong>
          </div>
        </div>

        <div class="line-chart" aria-label="실시간 DB 활동 차트">
          <div class="chart-grid-lines">
            <i v-for="line in 5" :key="line"></i>
          </div>
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
            <span v-for="label in timeLabels" :key="label">{{ label }}</span>
          </div>
        </div>
      </article>

      <article class="panel priority-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">PRIORITY SQL</span>
            <h2>지금 확인해야 할 SQL</h2>
          </div>
          <button class="text-button" type="button">전체 보기</button>
        </div>

        <div class="sql-table-wrap">
          <table class="sql-table">
            <thead>
              <tr>
                <th>위험도</th>
                <th>SQL ID</th>
                <th>경과</th>
                <th>CPU</th>
                <th>Buffer Gets</th>
                <th>문제 유형</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="sql in prioritySql" :key="sql.sqlId">
                <td><span class="severity" :class="sql.severity">{{ sql.severityLabel }}</span></td>
                <td>
                  <strong class="sql-id">{{ sql.sqlId }}</strong>
                  <small>{{ sql.schema }}</small>
                </td>
                <td>{{ sql.elapsed }}</td>
                <td>{{ sql.cpu }}</td>
                <td>{{ sql.bufferGets }}</td>
                <td><span class="issue-tag">{{ sql.issue }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article class="panel degradation-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">PERFORMANCE CHANGE</span>
            <h2>성능 저하 SQL</h2>
          </div>
          <span class="reference-badge">최근 7일 기준</span>
        </div>

        <div class="degradation-list">
          <article v-for="item in degradedSql" :key="item.sqlId" class="degradation-item">
            <div class="degradation-head">
              <div>
                <strong>{{ item.sqlId }}</strong>
                <small>{{ item.module }}</small>
              </div>
              <em>+{{ item.change }}%</em>
            </div>
            <div class="comparison-row">
              <span>평균 {{ item.baseline }}</span>
              <i>→</i>
              <strong>현재 {{ item.current }}</strong>
            </div>
            <div class="progress-track"><span :style="{ width: `${Math.min(item.change / 10, 100)}%` }"></span></div>
          </article>
        </div>
      </article>

      <article class="panel issue-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">DETECTION RULE</span>
            <h2>이상 SQL 탐지 현황</h2>
          </div>
          <button class="text-button" type="button" @click="showRules = !showRules">기준 보기</button>
        </div>

        <div class="issue-list">
          <div v-for="issue in issueSummary" :key="issue.label" class="issue-row">
            <span class="issue-icon" :class="issue.tone">{{ issue.icon }}</span>
            <div>
              <strong>{{ issue.label }}</strong>
              <small>{{ issue.description }}</small>
            </div>
            <em>{{ issue.count }}건</em>
          </div>
        </div>

        <div v-if="showRules" class="rule-box">
          <strong>기본 탐지 기준</strong>
          <p>장기 실행 30초 이상 · Buffer Gets 100,000 이상 · Disk Reads 10,000 이상 · 동일 SQL 평균 수행시간 대비 200% 이상 증가 · Blocking Session 존재 · Plan Hash 변경</p>
        </div>
      </article>

      <article class="panel recommendation-panel">
        <div class="panel-title-row">
          <div>
            <span class="panel-kicker">AI ADVISOR</span>
            <h2>최근 튜닝 권고</h2>
          </div>
          <span class="ai-badge">AI 분석 3건</span>
        </div>

        <div class="recommendation-list">
          <article v-for="item in recommendations" :key="item.title">
            <span :class="item.tone">{{ item.type }}</span>
            <div>
              <strong>{{ item.title }}</strong>
              <p>{{ item.description }}</p>
            </div>
            <button type="button">권고안 검토</button>
          </article>
        </div>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

interface MetricOption {
  key: 'activeSessions' | 'executions' | 'cpu' | 'io'
  label: string
}

const selectedDb = ref('DBLIFE')
const selectedMetric = ref<MetricOption['key']>('activeSessions')
const lastUpdated = ref('10:27:00')
const showRules = ref(false)
const tick = ref(0)
let timer: number | undefined

const metricOptions: MetricOption[] = [
  { key: 'activeSessions', label: 'Active Sessions' },
  { key: 'executions', label: 'Executions' },
  { key: 'cpu', label: 'CPU' },
  { key: 'io', label: 'I/O' }
]

const metricSeries = computed<Record<MetricOption['key'], number[]>>(() => {
  const offset = tick.value % 6
  return {
    activeSessions: [7, 9, 8, 13, 15, 12, 18, 21, 17, 24, 22, 19 + offset],
    executions: [82, 96, 91, 124, 116, 140, 152, 147, 174, 169, 188, 181 + offset * 2],
    cpu: [24, 28, 26, 31, 39, 35, 42, 47, 44, 51, 46, 48 + offset],
    io: [31, 36, 34, 45, 41, 52, 49, 63, 58, 71, 66, 69 + offset]
  }
})

const selectedSeries = computed(() => metricSeries.value[selectedMetric.value])
const currentMetricValue = computed(() => formatMetric(selectedSeries.value.at(-1) || 0))
const averageMetricValue = computed(() => formatMetric(Math.round(selectedSeries.value.slice(-6).reduce((sum, value) => sum + value, 0) / 6)))
const maxMetricValue = computed(() => formatMetric(Math.max(...selectedSeries.value)))

const chartPoints = computed(() => {
  const values = selectedSeries.value
  const max = Math.max(...values, 1)
  return values.map((value, index) => `${(index / (values.length - 1)) * 720},${205 - (value / max) * 175}`).join(' ')
})

const areaPath = computed(() => `M 0 220 L ${chartPoints.value.replaceAll(' ', ' L ')} L 720 220 Z`)
const timeLabels = ['10:22', '10:23', '10:24', '10:25', '10:26', '10:27']

const summaries = computed(() => [
  { label: '현재 실행 SQL', value: `${18 + (tick.value % 3)}건`, description: 'Active 세션 기준', tone: 'normal' },
  { label: '장기 실행 SQL', value: '3건', description: '30초 이상 수행', tone: 'warning' },
  { label: '성능 저하 SQL', value: '5건', description: '평균 대비 2배 이상', tone: 'danger' },
  { label: 'Blocking 세션', value: '1건', description: '즉시 확인 필요', tone: 'danger' },
  { label: '수집 상태', value: '정상', description: '5초 주기 갱신', tone: 'success' }
])

const prioritySql = [
  { severity: 'critical', severityLabel: '긴급', sqlId: '5ioq123scan5a', schema: 'BATCH_APP', elapsed: '183초', cpu: '41.2%', bufferGets: '1.2M', issue: '장기 실행' },
  { severity: 'high', severityLabel: '높음', sqlId: '8ax12kq9pt3df', schema: 'ONLINE_APP', elapsed: '42초', cpu: '23.4%', bufferGets: '820K', issue: 'Logical Read 과다' },
  { severity: 'high', severityLabel: '높음', sqlId: '2bc44mx81u7kd', schema: 'API_USER', elapsed: '18초', cpu: '8.1%', bufferGets: '210K', issue: 'Physical I/O 증가' },
  { severity: 'medium', severityLabel: '주의', sqlId: '7zp10va4nh2qs', schema: 'BATCH_APP', elapsed: '11초', cpu: '12.6%', bufferGets: '96K', issue: '실행 횟수 급증' }
]

const degradedSql = [
  { sqlId: '5ioq123scan5a', module: '보험료 배치', baseline: '0.8초', current: '8.3초', change: 937 },
  { sqlId: '8ax12kq9pt3df', module: '계약 조회 API', baseline: '2.1초', current: '9.7초', change: 361 },
  { sqlId: '2bc44mx81u7kd', module: '고객 조회', baseline: '0.2초', current: '0.7초', change: 250 }
]

const issueSummary = [
  { icon: '⏱', label: '장기 실행', description: '30초 이상 실행 중인 SQL', count: 3, tone: 'red' },
  { icon: '↕', label: '과다 Logical Read', description: 'Buffer Gets 100,000 이상', count: 4, tone: 'orange' },
  { icon: '◫', label: '실행계획 변경', description: 'Plan Hash Value 변경 감지', count: 1, tone: 'blue' },
  { icon: '⚠', label: 'Blocking 발생', description: '다른 세션의 진행을 차단', count: 1, tone: 'purple' }
]

const recommendations = [
  { type: 'INDEX', title: 'ORDERS 조건절 인덱스 후보 검토', description: 'CUSTOMER_ID와 STATUS 복합 인덱스 적용 시 예상 읽기 블록 수를 줄일 수 있습니다.', tone: 'green' },
  { type: 'PLAN', title: '실행계획 변경 원인 확인', description: '통계정보 갱신 이후 Full Table Scan으로 변경되었습니다. 기존 Plan과 비교가 필요합니다.', tone: 'blue' },
  { type: 'SQL', title: '함수 적용 조건절 개선', description: '인덱스 컬럼에 적용된 TO_CHAR 함수로 인덱스 접근이 제한될 수 있습니다.', tone: 'orange' }
]

function formatMetric(value: number) {
  if (selectedMetric.value === 'cpu') return `${value}%`
  if (selectedMetric.value === 'io') return `${value} MB/s`
  return value.toLocaleString()
}

function updateClock() {
  lastUpdated.value = new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(new Date())
}

onMounted(() => {
  updateClock()
  timer = window.setInterval(() => {
    tick.value += 1
    updateClock()
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style src="./AwrDashboard.css"></style>

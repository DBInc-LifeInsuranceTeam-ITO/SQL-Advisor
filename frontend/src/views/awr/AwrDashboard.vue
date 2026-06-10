<template>
  <div class="awr-page dashboard-page">
    <div class="awr-page-header dashboard-header">
      <div>
        <span class="dashboard-eyebrow">SQL Advisor </span>
        <h1>Dashboard</h1>
<!--        <p></p>-->
      </div>
      <div class="awr-actions">
<!--        <button class="awr-btn primary" type="button" :disabled="!canUseWorkspace" @click="openDirectDb">-->
<!--          Direct DB 튜닝-->
<!--        </button>-->
<!--        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-upload' })">AWR 업로드</button>-->
<!--        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-reports' })">리포트 목록</button>-->
      </div>
    </div>

    <div v-if="publicLoadMessage" class="awr-empty compact">{{ publicLoadMessage }}</div>

    <section class="awr-panel">
      <div class="dashboard-command-copy">
<!--        <span class="dashboard-eyebrow">Primary Workflow</span>-->
        <h2>Direct DB 튜닝 </h2>
<!--        <p>저장된 DB 연결과 SQL_ID를 선택해 튜닝 화면으로 바로 이어갑니다.</p>-->
      </div>
<!--      <button class="awr-btn compact" type="button" :disabled="!canUseWorkspace" @click="openDirectDb">튜닝 화면</button>-->
      <template v-if="canUseWorkspace">
        <div v-if="workspaceLoadMessage" class="awr-empty compact">{{ workspaceLoadMessage }}</div>
        <div v-else class="dashboard-command-body">
          <div class="dashboard-command-form">
            <label class="awr-field compact">
              Saved Connection
              <select v-model.number="selectedConnectionId" class="awr-input">
                <option :value="null">연결 선택</option>
                <option v-for="connection in connections" :key="connection.id" :value="connection.id">
                  {{ connection.name }} · {{ connection.username }}
                </option>
              </select>
            </label>

<!--            <button class="awr-btn primary dashboard-command-button" type="button" @click="openDirectDb">-->
<!--              튜닝 화면 열기-->
<!--            </button>-->
          </div>

          <div class="dashboard-command-stats">
            <div>
              <span>Saved DB</span>
              <strong>{{ formatNumber(connectionCount) }}</strong>
            </div>
            <div>
              <span>Index Candidates</span>
              <strong>{{ formatNumber(indexCandidateCount) }}</strong>
            </div>
            <div>
              <span>Missing Inputs</span>
              <strong>{{ formatNumber(missingInputCount) }}</strong>
            </div>
          </div>
        </div>
      </template>
      <div v-else class="awr-empty compact">로그인 후 Direct DB 튜닝을 시작할 수 있습니다.</div>
    </section>

    <section class="dashboard-kpi-grid">
      <div class="dashboard-kpi-card accent-green">
        <span class="dashboard-kpi-index">01</span>
        <div class="awr-kpi-label">AWR Reports</div>
        <div class="awr-kpi-value">{{ formatNumber(reportCount) }}</div>
        <div class="awr-kpi-sub">접근 가능한 리포트</div>
      </div>
      <div class="dashboard-kpi-card accent-blue">
        <span class="dashboard-kpi-index">02</span>
        <div class="awr-kpi-label">Ready Reports</div>
        <div class="awr-kpi-value">{{ readyReportCount }}</div>
        <div class="awr-kpi-sub">전체의 {{ readyPercent }} 변환</div>
      </div>
      <div class="dashboard-kpi-card accent-amber">
        <span class="dashboard-kpi-index">03</span>
        <div class="awr-kpi-label">Top SQL Rows</div>
        <div class="awr-kpi-value">{{ formatNumber(totalSqlRows) }}</div>
        <div class="awr-kpi-sub">AWR에서 추출된 SQL</div>
      </div>
      <div class="dashboard-kpi-card accent-slate">
        <span class="dashboard-kpi-index">04</span>
        <div class="awr-kpi-label">Tuning Runs</div>
        <div class="awr-kpi-value">{{ workspaceMetric(tuningCount) }}</div>
        <div class="awr-kpi-sub">최근 SQL 튜닝 이력</div>
      </div>
    </section>

    <div class="dashboard-insight-grid">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">AWR 처리 현황</h2>
            <p class="dashboard-panel-text">리포트 상태 분포와 추출 규모입니다.</p>
          </div>
          <button class="awr-btn compact" type="button" @click="router.push({ name: 'awr-reports' })">전체 보기</button>
        </div>

        <div v-if="reportStatusSegments.every((segment) => segment.count === 0)" class="awr-empty compact">
          AWR HTML 또는 TXT 파일을 업로드하면 처리 현황이 표시됩니다.
        </div>
        <template v-else>
          <div class="dashboard-stack-chart">
            <div class="dashboard-stack-bar" aria-label="AWR status distribution">
              <span
                v-for="segment in reportStatusSegments"
                :key="segment.key"
                :style="{ width: `${segment.percent}%`, backgroundColor: segment.color }"
                :title="`${segment.label}: ${segment.count}`"
              ></span>
            </div>
            <div class="dashboard-chart-legend">
              <span v-for="segment in reportStatusSegments" :key="segment.key">
                <i :style="{ backgroundColor: segment.color }"></i>
                {{ segment.label }} {{ segment.count }}
              </span>
            </div>
          </div>

          <div class="dashboard-metric-row">
            <div>
              <span>Wait Events</span>
              <strong>{{ formatNumber(totalWaitEvents) }}</strong>
            </div>
            <div>
              <span>Ready Ratio</span>
              <strong>{{ readyPercent }}</strong>
            </div>
          </div>
        </template>
      </section>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">SQL 튜닝 추이</h2>
            <p class="dashboard-panel-text">최근 7일 실행 흐름입니다.</p>
          </div>
          <span class="awr-badge ok">7 days</span>
        </div>

        <div v-if="!canUseWorkspace" class="awr-empty compact">로그인 후 튜닝 추이를 확인할 수 있습니다.</div>
        <div v-else-if="trendDays.every((day) => day.count === 0)" class="awr-empty compact">최근 7일 튜닝 실행 기록이 없습니다.</div>
        <template v-else>
          <div class="dashboard-trend-chart" aria-label="Recent tuning trend">
            <div v-for="day in trendDays" :key="day.key" class="dashboard-trend-column">
              <div class="dashboard-trend-track">
                <span :style="{ height: `${day.percent}%` }"></span>
              </div>
              <strong>{{ day.count }}</strong>
              <em>{{ day.label }}</em>
            </div>
          </div>
          <div class="dashboard-chart-foot">최근 7일 총 {{ formatNumber(trendTotal) }}회 실행</div>
        </template>
      </section>
    </div>

    <div class="dashboard-list-grid">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">AWR 리포트</h2>
            <p class="dashboard-panel-text">AWR Report List</p>
          </div>
          <button class="awr-btn compact" type="button" @click="router.push({ name: 'awr-reports' })">전체 보기</button>
        </div>

        <div v-if="recentReports.length === 0" class="awr-empty compact">아직 표시할 AWR 리포트가 없습니다.</div>
        <ul v-else class="dashboard-compact-list">
          <li v-for="report in recentReports" :key="report.id">
            <button class="dashboard-list-item" type="button" @click="router.push({ name: 'awr-report-detail', params: { id: report.id } })">
              <span class="dashboard-list-main">
                <strong>{{ report.filename }}</strong>
                <span>{{ report.dbName || '-' }} / {{ report.instanceName || '-' }}</span>
              </span>
              <span class="dashboard-list-side">
                <span
                  class="dashboard-status-pill"
                  :style="{ color: statusColor(report.status), borderColor: statusTint(report.status), backgroundColor: statusTint(report.status) }"
                >
                  {{ report.status }}
                </span>
                <em>{{ formatDate(report.uploadedAt) }}</em>
              </span>
              <span class="dashboard-list-meta">
                SQL {{ report.topSqlCount }} · Wait {{ report.waitEventCount }}
              </span>
            </button>
          </li>
        </ul>
      </section>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">SQL 튜닝 결과</h2>
            <p class="dashboard-panel-text">SQL_ID List</p>
          </div>
          <button class="awr-btn compact" type="button" :disabled="!canUseWorkspace" @click="openDirectDb">튜닝 화면</button>
        </div>

        <div v-if="!canUseWorkspace" class="awr-empty compact">로그인 후 튜닝 결과를 확인할 수 있습니다.</div>
        <div v-else-if="workspaceLoadMessage" class="awr-empty compact">{{ workspaceLoadMessage }}</div>
        <div v-else-if="recentTuning.length === 0" class="awr-empty compact">아직 실행된 SQL 튜닝 결과가 없습니다.</div>
        <ul v-else class="dashboard-compact-list">
          <li v-for="item in recentTuning" :key="item.tuningId">
            <button class="dashboard-list-item" type="button" @click="openTuningItem(item)">
              <span class="dashboard-list-main">
                <strong>{{ item.sqlId }}</strong>
                <span>{{ item.summary }}</span>
              </span>
              <span class="dashboard-list-side">
                <span class="dashboard-status-pill neutral">{{ item.confidence }}</span>
                <em>{{ formatDate(item.createdAt) }}</em>
              </span>
              <span class="dashboard-list-meta">
                Index {{ item.indexRecommendations.length }} · Missing {{ item.missingInputs.length }}
              </span>
            </button>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAwrReports } from '@/api/awr'
import { getSqlTuningHistory, getTargetDbConnections } from '@/api/sqlTuning'
import { useAuthStore } from '@/stores/auth'
import type { ReportSummaryResponse, SqlTuningResponse, TargetDbConnectionResponse } from '@/types/awr'

const router = useRouter()
const authStore = useAuthStore()
const reports = ref<ReportSummaryResponse[]>([])
const history = ref<SqlTuningResponse[]>([])
const connections = ref<TargetDbConnectionResponse[]>([])
const selectedConnectionId = ref<number | null>(null)
const directSqlId = ref('')
const publicLoadMessage = ref('')
const workspaceLoadMessage = ref('')

const canUseWorkspace = computed(() => !authStore.authEnabled || authStore.isAuthenticated)
const reportCount = computed(() => reports.value.length)
const readyReportCount = computed(() => reports.value.filter((report) => report.status === 'INDEXED').length)
const totalSqlRows = computed(() => reports.value.reduce((sum, report) => sum + report.topSqlCount, 0))
const totalWaitEvents = computed(() => reports.value.reduce((sum, report) => sum + report.waitEventCount, 0))
const tuningCount = computed(() => history.value.length)
const connectionCount = computed(() => connections.value.length)
const recentReports = computed(() => reports.value.slice(0, 5))
const recentTuning = computed(() => history.value.slice(0, 5))
const readyPercent = computed(() => {
  if (reportCount.value === 0) return '0%'
  return `${Math.round((readyReportCount.value / reportCount.value) * 100)}%`
})
const missingInputCount = computed(() =>
  history.value.reduce((sum, item) => sum + item.missingInputs.length, 0)
)
const indexCandidateCount = computed(() =>
  history.value.reduce((sum, item) => sum + item.indexRecommendations.length, 0)
)
const trendDays = computed(() => {
  const days = lastSevenDays()
  const counts = new Map(days.map((day) => [day.key, 0]))
  history.value.forEach((item) => {
    const key = dateKey(new Date(item.createdAt))
    if (counts.has(key)) {
      counts.set(key, (counts.get(key) || 0) + 1)
    }
  })
  const max = Math.max(...Array.from(counts.values()), 1)
  return days.map((day) => {
    const count = counts.get(day.key) || 0
    return {
      ...day,
      count,
      percent: count === 0 ? 0 : Math.max((count / max) * 100, 12)
    }
  })
})
const trendTotal = computed(() => trendDays.value.reduce((sum, day) => sum + day.count, 0))
const reportStatusCounts = computed(() => {
  const counts = new Map<string, number>()
  reports.value.forEach((report) => {
    counts.set(report.status, (counts.get(report.status) || 0) + 1)
  })
  return Array.from(counts.entries())
    .sort(([left], [right]) => statusRank(left) - statusRank(right))
    .map(([status, count]) => ({ status, count }))
})
const reportStatusSegments = computed(() => {
  const total = Math.max(reportStatusCounts.value.reduce((sum, item) => sum + item.count, 0), 1)
  return reportStatusCounts.value.map((item) => ({
    key: item.status,
    label: item.status,
    count: item.count,
    color: statusColor(item.status),
    percent: item.count === 0 ? 0 : Math.max((item.count / total) * 100, 8)
  }))
})

onMounted(loadDashboard)

async function loadDashboard() {
  publicLoadMessage.value = ''
  workspaceLoadMessage.value = ''
  try {
    reports.value = await getAwrReports()
  } catch (error) {
    publicLoadMessage.value = error instanceof Error ? error.message : '대시보드 AWR 현황을 불러오지 못했습니다.'
  }

  if (!canUseWorkspace.value) return

  try {
    const [tuningHistory, savedConnections] = await Promise.all([
      getSqlTuningHistory(),
      getTargetDbConnections()
    ])
    history.value = tuningHistory
    connections.value = savedConnections
    selectedConnectionId.value = savedConnections[0]?.id ?? null
  } catch (error) {
    workspaceLoadMessage.value = error instanceof Error ? error.message : 'SQL 튜닝 작업 현황을 불러오지 못했습니다.'
  }
}

function openDirectDb() {
  const query: Record<string, string> = {}
  if (selectedConnectionId.value) query.connectionId = String(selectedConnectionId.value)
  if (directSqlId.value.trim()) query.sqlId = directSqlId.value.trim()
  router.push({ name: 'sql-tuning', query })
}

function openTuningItem(item: SqlTuningResponse) {
  const query: Record<string, string> = {}
  if (item.sqlId) query.sqlId = item.sqlId
  router.push({ name: 'sql-tuning', query })
}

function workspaceMetric(value: number) {
  return canUseWorkspace.value ? formatNumber(value) : '-'
}

function statusRank(status: string) {
  const ranks: Record<string, number> = {
    INDEXED: 0,
    PARSED: 1,
    EXTRACTING: 2,
    QUEUED: 3,
    FAILED: 4
  }
  return ranks[status] ?? 9
}

function statusColor(status: string) {
  const colors: Record<string, string> = {
    INDEXED: '#217a52',
    PARSED: '#176ea8',
    EXTRACTING: '#7c3aed',
    QUEUED: '#b7791f',
    FAILED: '#be123c'
  }
  return colors[status] || '#64748b'
}

function statusTint(status: string) {
  const colors: Record<string, string> = {
    INDEXED: '#e7f6ee',
    PARSED: '#e9f4fb',
    EXTRACTING: '#f1edff',
    QUEUED: '#fff5db',
    FAILED: '#fff1f2'
  }
  return colors[status] || '#eef2f6'
}

function lastSevenDays() {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date(today)
    date.setDate(today.getDate() - (6 - index))
    return {
      key: dateKey(date),
      label: new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit' }).format(date)
    }
  })
}

function dateKey(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('ko-KR').format(value)
}

function formatDate(value?: string | null) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}
</script>

<style src="./awr.css"></style>

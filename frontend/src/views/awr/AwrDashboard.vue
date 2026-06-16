<template>
  <div class="awr-page dashboard-page dashboard-compact-page">
    <div class="dashboard-compact-header">
      <div>
        <span class="dashboard-ko-eyebrow">AWR 기반 SQL 분석</span>
        <h1>SQL Advisor</h1>
        <p>성능 상태와 우선 점검 SQL만 압축해서 보여줍니다.</p>
      </div>

      <div class="awr-actions dashboard-compact-actions">
        <button class="awr-btn primary" type="button" @click="router.push({ name: 'awr-upload' })">
          AWR 업로드
        </button>
        <button class="awr-btn" type="button" @click="router.push({ name: 'sql-tuning' })">
          SQL 튜닝
        </button>
      </div>
    </div>

    <div v-if="publicLoadMessage" class="awr-empty compact">
      {{ publicLoadMessage }}
    </div>

    <section class="dashboard-focus-grid">
      <article class="awr-panel dashboard-status-card" :class="performanceClass">
        <div class="dashboard-card-title">
          <span>성능 상태</span>
          <strong>{{ performanceLabel }}</strong>
        </div>

        <div class="dashboard-score-wrap">
          <div class="dashboard-score-ring" :style="{ '--score': performanceScore }">
            <div>
              <strong>{{ performanceScore }}</strong>
              <span>/ 100</span>
            </div>
          </div>
        </div>

        <p class="dashboard-status-message">
          {{ performanceMessage }}
        </p>
      </article>

      <article class="awr-panel dashboard-bottleneck-card">
        <div class="dashboard-card-title">
          <span>주요 병목</span>
          <button class="dashboard-text-button" type="button" @click="openFirstReport">
            상세 보기
          </button>
        </div>

        <div v-if="topWaitEvents.length === 0" class="awr-empty compact">
          분석 완료된 대기 이벤트가 아직 없습니다.
        </div>

        <ul v-else class="dashboard-bottleneck-list">
          <li v-for="event in topWaitEvents" :key="`${event.waitClass}-${event.eventName}`">
            <div class="dashboard-bottleneck-row">
              <div>
                <strong>{{ event.eventName }}</strong>
                <span>{{ waitClassLabel(event.waitClass) }}</span>
              </div>
              <em>{{ formatPercent(event.dbTimePercent) }}</em>
            </div>

            <div class="dashboard-bottleneck-bar">
              <span :style="{ width: `${event.barPercent}%` }"></span>
            </div>
          </li>
        </ul>
      </article>
    </section>

    <section class="awr-panel dashboard-sql-card">
      <div class="dashboard-card-title">
        <span>우선 점검 SQL</span>
        <button class="dashboard-text-button" type="button" @click="router.push({ name: 'awr-reports' })">
          리포트 목록
        </button>
      </div>

      <div v-if="topRiskSql.length === 0" class="awr-empty compact">
        분석 완료된 SQL 지표가 아직 없습니다.
      </div>

      <div v-else class="dashboard-sql-table">
        <button
          v-for="item in topRiskSql"
          :key="`${item.reportId}-${item.sqlId}-${item.rankNo}`"
          type="button"
          class="dashboard-sql-row"
          @click="openReport(item.reportId)"
        >
          <span class="dashboard-sql-id">
            <strong>{{ item.sqlId }}</strong>
            <em>{{ item.reportName }}</em>
          </span>

          <span class="dashboard-risk-pill" :class="riskClass(item.riskScore)">
            {{ riskLabel(item.riskScore) }}
          </span>

          <span class="dashboard-sql-reason">
            {{ riskReason(item) }}
          </span>

          <span class="dashboard-sql-metric">
            CPU {{ formatSeconds(item.cpuTimeSec) }} · Buffer {{ formatCompactNumber(item.bufferGets) }}
          </span>
        </button>
      </div>
    </section>

    <section class="dashboard-summary-grid">
      <button class="dashboard-summary-card" type="button" @click="router.push({ name: 'awr-reports' })">
        <span>분석 완료</span>
        <strong>{{ readyReportCount }}</strong>
        <em>전체 {{ reportCount }}건 중 {{ readyPercent }}</em>
      </button>

      <button class="dashboard-summary-card danger" type="button" @click="router.push({ name: 'awr-reports' })">
        <span>실패 리포트</span>
        <strong>{{ failedReportCount }}</strong>
        <em>재처리 확인 대상</em>
      </button>

      <button class="dashboard-summary-card warn" type="button" @click="router.push({ name: 'sql-tuning' })">
        <span>튜닝 권고</span>
        <strong>{{ workspaceMetric(indexCandidateCount + missingInputCount) }}</strong>
        <em>인덱스 후보 · 추가 입력</em>
      </button>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAwrReport, getAwrReports } from '@/api/awr'
import { getSqlTuningHistory } from '@/api/sqlTuning'
import { useAuthStore } from '@/stores/auth'
import type {
  ReportDetailResponse,
  ReportSummaryResponse,
  SqlMetricResponse,
  SqlTuningResponse,
  WaitEventResponse
} from '@/types/awr'

interface RiskSqlItem extends SqlMetricResponse {
  reportId: number
  reportName: string
  riskScore: number
}

interface WaitEventItem extends WaitEventResponse {
  dbTimePercent: number
  barPercent: number
}

const router = useRouter()
const authStore = useAuthStore()

const reports = ref<ReportSummaryResponse[]>([])
const reportDetails = ref<ReportDetailResponse[]>([])
const history = ref<SqlTuningResponse[]>([])

const publicLoadMessage = ref('')
const workspaceLoadMessage = ref('')

const canUseWorkspace = computed(() => !authStore.authEnabled || authStore.isAuthenticated)

const reportCount = computed(() => reports.value.length)

const readyReportCount = computed(() =>
  reports.value.filter((report) => report.status === 'INDEXED').length
)

const failedReportCount = computed(() =>
  reports.value.filter((report) => report.status === 'FAILED').length
)

const processingReportCount = computed(() =>
  reports.value.filter((report) =>
    ['QUEUED', 'EXTRACTING', 'INDEXING', 'NEEDS_TEXT_EXTRACTION'].includes(report.status)
  ).length
)

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

const topRiskSql = computed<RiskSqlItem[]>(() => {
  return reportDetails.value
    .flatMap((detail) =>
      detail.topSql.map((sql) => ({
        ...sql,
        reportId: detail.id,
        reportName: detail.filename,
        riskScore: calculateRiskScore(sql)
      }))
    )
    .sort((left, right) => right.riskScore - left.riskScore)
    .slice(0, 3)
})

const topWaitEvents = computed<WaitEventItem[]>(() => {
  const grouped = new Map<
    string,
    {
      eventName: string
      waitClass: string
      dbTimePercent: number
      totalWaitTimeSec: number
    }
  >()

  reportDetails.value.forEach((detail) => {
    detail.topWaitEvents.forEach((event) => {
      const key = `${event.waitClass}:${event.eventName}`

      const current = grouped.get(key) || {
        eventName: event.eventName,
        waitClass: event.waitClass,
        dbTimePercent: 0,
        totalWaitTimeSec: 0
      }

      current.dbTimePercent += event.dbTimePercent || 0
      current.totalWaitTimeSec += event.totalWaitTimeSec || 0

      grouped.set(key, current)
    })
  })

  const rows = Array.from(grouped.values())
    .sort((left, right) => right.dbTimePercent - left.dbTimePercent || right.totalWaitTimeSec - left.totalWaitTimeSec)
    .slice(0, 3)

  const maxPercent = Math.max(...rows.map((row) => row.dbTimePercent), 1)

  return rows.map((row) => ({
    waitClass: row.waitClass,
    eventName: row.eventName,
    totalWaitTimeSec: row.totalWaitTimeSec,
    avgWaitMs: null,
    dbTimePercent: row.dbTimePercent,
    barPercent: Math.max((row.dbTimePercent / maxPercent) * 100, 8)
  }))
})

const performanceScore = computed(() => {
  let score = 100

  score -= failedReportCount.value * 10
  score -= processingReportCount.value * 3
  score -= topRiskSql.value.filter((item) => item.riskScore >= 75).length * 12
  score -= topRiskSql.value.filter((item) => item.riskScore >= 45 && item.riskScore < 75).length * 5
  score -= topWaitEvents.value.filter((event) => event.dbTimePercent >= 30).length * 10
  score -= Math.min(missingInputCount.value * 2, 10)

  return Math.max(Math.min(score, 100), 0)
})

const performanceLabel = computed(() => {
  if (performanceScore.value >= 85) return '안정'
  if (performanceScore.value >= 65) return '주의'
  return '점검 필요'
})

const performanceClass = computed(() => {
  if (performanceScore.value >= 85) return 'good'
  if (performanceScore.value >= 65) return 'caution'
  return 'bad'
})

const performanceMessage = computed(() => {
  if (reportCount.value === 0) {
    return 'AWR 리포트를 업로드하면 성능 상태가 표시됩니다.'
  }

  if (failedReportCount.value > 0) {
    return `실패 리포트 ${failedReportCount.value}건을 먼저 확인하는 게 좋습니다.`
  }

  if (topRiskSql.value.some((item) => item.riskScore >= 75)) {
    return '부하가 큰 SQL을 우선 점검해야 합니다.'
  }

  if (topWaitEvents.value.some((event) => event.dbTimePercent >= 30)) {
    return 'DB Time 비중이 큰 대기 이벤트가 있습니다.'
  }

  return '현재 대시보드 기준 큰 이상 징후는 없습니다.'
})

onMounted(loadDashboard)

async function loadDashboard() {
  publicLoadMessage.value = ''
  workspaceLoadMessage.value = ''

  try {
    reports.value = await getAwrReports()
    await loadInsightDetails()
  } catch (error) {
    publicLoadMessage.value = error instanceof Error ? error.message : '대시보드 AWR 현황을 불러오지 못했습니다.'
  }

  if (!canUseWorkspace.value) return

  try {
    history.value = await getSqlTuningHistory()
  } catch (error) {
    workspaceLoadMessage.value = error instanceof Error ? error.message : 'SQL 튜닝 작업 현황을 불러오지 못했습니다.'
  }
}

async function loadInsightDetails() {
  const targets = reports.value
    .filter((report) => report.status === 'INDEXED' && (report.topSqlCount > 0 || report.waitEventCount > 0))
    .slice(0, 5)

  const results = await Promise.allSettled(targets.map((report) => getAwrReport(report.id)))

  reportDetails.value = results
    .filter((result): result is PromiseFulfilledResult<ReportDetailResponse> => result.status === 'fulfilled')
    .map((result) => result.value)
}

function openReport(reportId: number) {
  router.push({
    name: 'awr-report-detail',
    params: {
      id: reportId
    }
  })
}

function openFirstReport() {
  const reportId =
    reportDetails.value[0]?.id ??
    reports.value.find((report) => report.status === 'INDEXED')?.id

  if (reportId) {
    openReport(reportId)
    return
  }

  router.push({ name: 'awr-reports' })
}

function workspaceMetric(value: number) {
  if (workspaceLoadMessage.value) return '-'
  return canUseWorkspace.value ? formatNumber(value) : '-'
}

function calculateRiskScore(sql: SqlMetricResponse) {
  const base = sql.score ?? 0
  const elapsed = Math.min((sql.elapsedTimeSec || 0) / 10, 30)
  const cpu = Math.min((sql.cpuTimeSec || 0) / 10, 25)
  const buffer = Math.min(Math.log10((sql.bufferGets || 0) + 1) * 5, 25)
  const disk = Math.min(Math.log10((sql.diskReads || 0) + 1) * 4, 15)
  const rankBonus = Math.max(10 - (sql.rankNo || 10), 0)

  return Math.round(Math.min(base + elapsed + cpu + buffer + disk + rankBonus, 100))
}

function riskLabel(score: number) {
  if (score >= 75) return '높음'
  if (score >= 45) return '주의'
  return '낮음'
}

function riskClass(score: number) {
  if (score >= 75) return 'danger'
  if (score >= 45) return 'warn'
  return 'ok'
}

function riskReason(sql: SqlMetricResponse) {
  const elapsed = sql.elapsedTimeSec || 0
  const cpu = sql.cpuTimeSec || 0
  const buffer = sql.bufferGets || 0
  const disk = sql.diskReads || 0

  if (cpu >= elapsed * 0.55 && cpu > 0) return 'CPU 사용량 과다'
  if (buffer >= 1_000_000) return 'Buffer Gets 과다'
  if (disk >= 100_000) return 'Disk Reads 과다'
  if ((sql.executions || 0) >= 1_000) return '실행 횟수 과다'
  if (elapsed >= 300) return '수행 시간 과다'

  return '상위 부하 SQL'
}

function waitClassLabel(waitClass?: string | null) {
  const labels: Record<string, string> = {
    CPU: 'CPU 사용',
    'User I/O': '사용자 I/O',
    'System I/O': '시스템 I/O',
    Commit: '커밋 대기',
    Concurrency: '동시성 대기',
    Cluster: 'RAC 클러스터',
    Application: '애플리케이션 대기',
    Network: '네트워크 대기'
  }

  if (!waitClass) return '-'
  return labels[waitClass] || waitClass
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('ko-KR').format(value)
}

function formatCompactNumber(value?: number | null) {
  if (!value) return '-'

  return new Intl.NumberFormat('ko-KR', {
    notation: 'compact',
    maximumFractionDigits: 1
  }).format(value)
}

function formatSeconds(value?: number | null) {
  if (!value) return '-'

  if (value >= 60) {
    return `${(value / 60).toFixed(1)}분`
  }

  return `${value.toFixed(1)}초`
}

function formatPercent(value?: number | null) {
  if (!value) return '-'
  return `${value.toFixed(1)}%`
}
</script>

<style src="./awr.css"></style>
<style scoped src="./AwrDashboard.css"></style>

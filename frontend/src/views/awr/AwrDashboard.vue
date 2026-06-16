<template>
  <div class="awr-page dashboard-page dashboard-v5-page">
    <section class="dashboard-top-strip">
      <article class="summary-card state" :class="performanceClass">
        <span class="summary-label">종합 상태</span>
        <strong>{{ performanceLabel }}</strong>
        <em>{{ performanceScore }}점</em>
      </article>

      <article class="summary-card risk">
        <span class="summary-label">우선 점검 SQL</span>
        <strong>{{ topRiskSql.length }}건</strong>
        <em>{{ dominantIssueLabel }}</em>
      </article>

      <article class="summary-card bottleneck">
        <span class="summary-label">주요 병목</span>
        <strong>{{ dominantBottleneckLabel }}</strong>
        <em>{{ dominantBottleneckPercent }}</em>
      </article>

      <article class="summary-card report">
        <span class="summary-label">분석 현황</span>
        <strong>{{ readyReportCount }}/{{ reportCount }}</strong>
        <em>실패 {{ failedReportCount }}건</em>
      </article>
    </section>

    <div v-if="publicLoadMessage" class="dashboard-alert">
      {{ publicLoadMessage }}
    </div>

    <section class="dashboard-v5-grid">
      <article class="dashboard-panel status-panel" :class="performanceClass">
        <div class="panel-header">
          <div>
            <span class="panel-kicker">종합 진단</span>
            <h2>성능 상태</h2>
          </div>
          <span class="panel-badge">{{ performanceLabel }}</span>
        </div>

        <div class="score-zone">
          <div class="score-ring" :style="{ '--score': `${performanceScore}%` }">
            <div class="score-inner">
              <strong>{{ performanceScore }}</strong>
              <span>/ 100</span>
            </div>
          </div>
        </div>

        <div class="status-summary-box">
          <strong>{{ performanceMessage }}</strong>
          <p>{{ dominantIssueLabel }} · {{ dominantBottleneckLabel }}</p>
        </div>

        <div class="status-metric-grid">
          <div>
            <span>완료율</span>
            <strong>{{ readyPercent }}</strong>
          </div>
          <div>
            <span>실패</span>
            <strong>{{ failedReportCount }}</strong>
          </div>
        </div>
      </article>

      <article class="dashboard-panel bottleneck-panel">
        <div class="panel-header">
          <div>
            <span class="panel-kicker">병목 분석</span>
            <h2>병목 분포</h2>
          </div>
        </div>

        <div v-if="topWaitEvents.length === 0" class="empty-panel">
          분석 완료된 병목 정보가 아직 없습니다.
        </div>

        <template v-else>
          <div class="distribution-hero">
            <div class="distribution-title">
              <span>DB Time 기준 상위 병목</span>
              <strong>{{ dominantBottleneckLabel }}</strong>
            </div>

            <div class="distribution-bar">
              <span
                v-for="(event, index) in topWaitEvents"
                :key="`${event.waitClass}-${event.eventName}`"
                :class="`segment segment-${index + 1}`"
                :style="{ width: `${event.segmentPercent}%` }"
              ></span>
            </div>
          </div>

          <div class="bottleneck-list">
            <article
              v-for="(event, index) in topWaitEvents"
              :key="`${event.waitClass}-${event.eventName}`"
              class="bottleneck-item"
            >
              <div class="bottleneck-rank" :class="`rank-${index + 1}`">{{ index + 1 }}</div>

              <div class="bottleneck-content">
                <div class="bottleneck-topline">
                  <strong>{{ normalizeWaitEventName(event.eventName) }}</strong>
                  <span>{{ formatPercent(event.dbTimePercent) }}</span>
                </div>

                <div class="bottleneck-subline">
                  <em>{{ waitClassLabel(event.waitClass) }}</em>
                  <small>{{ bottleneckComment(event) }}</small>
                </div>

                <div class="bottleneck-track">
                  <i
                    :class="`track-fill fill-${index + 1}`"
                    :style="{ width: `${event.barPercent}%` }"
                  ></i>
                </div>
              </div>
            </article>
          </div>
        </template>
      </article>

      <article class="dashboard-panel sql-panel">
        <div class="panel-header">
          <div>
            <span class="panel-kicker">집중 점검 대상</span>
            <h2>우선 점검 SQL</h2>
          </div>
        </div>

        <div v-if="topRiskSql.length === 0" class="empty-panel">
          분석 완료된 SQL 지표가 아직 없습니다.
        </div>

        <div v-else class="sql-stack">
          <button
            v-for="item in topRiskSql"
            :key="`${item.reportId}-${item.sqlId}-${item.rankNo}`"
            type="button"
            class="sql-card"
            @click="openReport(item.reportId)"
          >
            <span class="sql-topline">
              <strong>{{ item.sqlId }}</strong>
              <em class="risk-pill" :class="riskClass(item.riskScore)">
                {{ riskLabel(item.riskScore) }}
              </em>
            </span>

            <span class="sql-reason">{{ riskReason(item) }}</span>

            <span class="sql-metrics">
              <span>CPU {{ formatSeconds(item.cpuTimeSec) }}</span>
              <span>실행 {{ formatCompactNumber(item.executions) }}회</span>
              <span>Buffer {{ formatCompactNumber(item.bufferGets) }}</span>
            </span>
          </button>
        </div>

        <div class="next-action-box">
          <span>다음 조치</span>
          <strong>{{ actionGuides[0]?.title || '분석 결과 확인' }}</strong>
          <p>{{ actionGuides[0]?.description || 'AWR 리포트를 추가 등록해 비교 분석 기반을 늘려보세요.' }}</p>
        </div>
      </article>
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
  segmentPercent: number
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
  const totalPercent = Math.max(rows.reduce((sum, row) => sum + row.dbTimePercent, 0), 1)

  return rows.map((row) => ({
    waitClass: row.waitClass,
    eventName: row.eventName,
    totalWaitTimeSec: row.totalWaitTimeSec,
    avgWaitMs: null,
    dbTimePercent: row.dbTimePercent,
    barPercent: Math.max((row.dbTimePercent / maxPercent) * 100, 8),
    segmentPercent: Math.max((row.dbTimePercent / totalPercent) * 100, 8)
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
    return 'AWR 리포트를 등록하면 종합 상태를 확인할 수 있습니다.'
  }

  if (failedReportCount.value > 0) {
    return `실패 리포트 ${failedReportCount.value}건을 먼저 확인해야 합니다.`
  }

  if (topRiskSql.value.some((item) => item.riskScore >= 75)) {
    return '부하가 큰 SQL이 있어 우선 점검이 필요합니다.'
  }

  if (topWaitEvents.value.some((event) => event.dbTimePercent >= 30)) {
    return '특정 병목 비중이 커서 원인 점검이 필요합니다.'
  }

  return '현재 기준 큰 이상 징후는 없습니다.'
})

const dominantWaitEvent = computed(() => topWaitEvents.value[0] ?? null)

const dominantBottleneckLabel = computed(() => {
  if (!dominantWaitEvent.value) return '이상 없음'
  return normalizeWaitEventName(dominantWaitEvent.value.eventName)
})

const dominantBottleneckPercent = computed(() => {
  if (!dominantWaitEvent.value) return '-'
  return formatPercent(dominantWaitEvent.value.dbTimePercent)
})

const dominantIssueLabel = computed(() => {
  if (!topRiskSql.value.length) return '이상 징후 없음'
  return riskReason(topRiskSql.value[0])
})

const overviewHighlights = computed(() => {
  const items: { title: string; description: string; tone: string }[] = []

  if (failedReportCount.value > 0) {
    items.push({
      title: `실패 리포트 ${failedReportCount.value}건`,
      description: '재처리 여부와 원본 파일 상태를 우선 확인해야 합니다.',
      tone: 'danger'
    })
  } else {
    items.push({
      title: `분석 완료 ${readyReportCount.value}건`,
      description: `전체 ${reportCount.value}건 중 ${readyPercent.value}가 분석 완료 상태입니다.`,
      tone: 'neutral'
    })
  }

  if (dominantWaitEvent.value) {
    items.push({
      title: `${normalizeWaitEventName(dominantWaitEvent.value.eventName)} 비중 높음`,
      description: `${waitClassLabel(dominantWaitEvent.value.waitClass)} 구간이 ${formatPercent(dominantWaitEvent.value.dbTimePercent)} 수준입니다.`,
      tone: dominantWaitEvent.value.dbTimePercent >= 30 ? 'warn' : 'neutral'
    })
  }

  if (topRiskSql.value[0]) {
    items.push({
      title: '상위 SQL 점검 필요',
      description: `${topRiskSql.value[0].sqlId} - ${riskReason(topRiskSql.value[0])}`,
      tone: topRiskSql.value[0].riskScore >= 75 ? 'danger' : 'warn'
    })
  }

  return items.slice(0, 3)
})

const actionGuides = computed(() => {
  const items: { title: string; description: string; tone: string }[] = []

  if (failedReportCount.value > 0) {
    items.push({
      title: '실패 리포트 확인',
      description: '분석 실패 건이 있으면 원본 파일과 재처리 여부를 먼저 확인하세요.',
      tone: 'danger'
    })
  }

  if (topRiskSql.value.length > 0) {
    items.push({
      title: '상위 부하 SQL 우선 점검',
      description: `${topRiskSql.value[0].sqlId}부터 실행 횟수, CPU 시간, Buffer Gets를 확인하세요.`,
      tone: 'warn'
    })
  }

  if (dominantWaitEvent.value) {
    items.push({
      title: '병목 유형별 원인 확인',
      description: `${waitClassLabel(dominantWaitEvent.value.waitClass)} 구간이 높으므로 관련 자원 상태를 점검하세요.`,
      tone: 'neutral'
    })
  }

  if (items.length < 3) {
    items.push({
      title: '분석 결과 축적',
      description: 'AWR 리포트를 추가 등록해 비교 분석 기반을 늘리는 것이 좋습니다.',
      tone: 'neutral'
    })
  }

  return items.slice(0, 3)
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
  const execs = sql.executions || 0

  if (cpu >= elapsed * 0.55 && cpu > 0) return 'CPU 사용 비중 높음'
  if (buffer >= 1_000_000) return 'Buffer Gets 과다'
  if (disk >= 100_000) return 'Disk Reads 과다'
  if (execs >= 1_000) return '실행 횟수 과다'
  if (elapsed >= 300) return '수행 시간 과다'

  return '상위 부하 SQL'
}

function waitClassLabel(waitClass?: string | null) {
  const labels: Record<string, string> = {
    CPU: 'CPU',
    'User I/O': '사용자 I/O',
    'System I/O': '시스템 I/O',
    Commit: '커밋 대기',
    Concurrency: '동시성 대기',
    Cluster: 'RAC 클러스터',
    Application: '애플리케이션',
    Network: '네트워크'
  }

  if (!waitClass) return '-'
  return labels[waitClass] || waitClass
}

function normalizeWaitEventName(name?: string | null) {
  if (!name) return '-'

  const normalized = name
    .split(/\s+/)
    .filter(Boolean)
    .filter((token, index, arr) => index === 0 || token !== arr[index - 1])
    .join(' ')

  const map: Record<string, string> = {
    'DB CPU CPU': 'DB CPU',
    'CPU CPU': 'CPU'
  }

  return map[normalized] || normalized
}

function bottleneckComment(event: WaitEventItem) {
  if (event.dbTimePercent >= 40) return 'DB Time 비중이 매우 높습니다.'
  if (event.dbTimePercent >= 20) return '우선 확인이 필요한 병목입니다.'
  return '참고 수준으로 확인하면 됩니다.'
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

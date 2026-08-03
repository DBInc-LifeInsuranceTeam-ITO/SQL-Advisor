<template>
  <div class="awr-page">
    <section class="awr-report-header-card">
      <div class="awr-report-header-main">
        <div class="awr-report-title-area">
          <p class="awr-report-eyebrow">AWR 리포트 분석 결과</p>
          <h1 :title="reportFilename">{{ reportFilename }}</h1>
        </div>

        <div class="awr-report-header-actions">
          <span :class="['awr-report-status', isAnalysisComplete ? 'complete' : 'processing']">
            {{ reportStatusLabel }}
          </span>

          <button
            class="awr-btn"
            type="button"
            @click="router.push({ name: 'awr-reports' })"
          >
            목록
          </button>
        </div>
      </div>

      <div v-if="report" class="awr-report-meta">
        <div>
          <span>데이터베이스</span>
          <strong :title="displayDbName">{{ displayDbName }}</strong>
        </div>

        <div>
          <span>인스턴스</span>
          <strong :title="displayInstanceName">{{ displayInstanceName }}</strong>
        </div>

        <div>
          <span>분석 기간</span>
          <strong :title="analysisPeriod">{{ analysisPeriod }}</strong>
        </div>
      </div>
    </section>

    <div v-if="errorMessage" class="awr-empty">
      {{ errorMessage }}
    </div>

    <template v-if="report">
      <div class="awr-split awr-report-main">
        <section class="awr-panel awr-side-panel">
          <div class="awr-panel-header">
            <div>
              <h2 class="awr-panel-title">AI 분석 요약</h2>
              <p class="awr-muted awr-panel-description">
                AWR 전체 부하와 병목 의심 지점에 대한 분석 결과입니다.
              </p>
            </div>

            <span v-if="analysisToShow" class="awr-badge">
              {{ analysisToShow.model }}
            </span>
          </div>

          <template v-if="analysisToShow">
            <p class="awr-summary-text">
              {{ analysisToShow.summary }}
            </p>

            <div class="awr-stack awr-finding-list">
              <article
                v-for="finding in analysisToShow.topFindings"
                :key="finding.priority"
                class="awr-finding"
              >
                <div class="awr-finding-heading">
                  <span class="awr-priority-number">
                    {{ finding.priority }}
                  </span>

                  <div>
                    <h3>{{ finding.symptom }}</h3>
                    <p v-if="finding.sqlId" class="awr-muted">
                      SQL_ID {{ finding.sqlId }} · 분석 신뢰도 {{ finding.confidence }}
                    </p>
                    <p v-else class="awr-muted">
                      분석 신뢰도 {{ finding.confidence }}
                    </p>
                  </div>
                </div>

                <div class="awr-finding-grid compact">
                  <div>
                    <strong>판단 근거</strong>
                    <ul>
                      <li
                        v-for="item in finding.evidence"
                        :key="item"
                      >
                        {{ item }}
                      </li>
                    </ul>
                  </div>

                  <div>
                    <strong>진단 내용</strong>
                    <ul>
                      <li
                        v-for="item in finding.likelyCauses"
                        :key="item"
                      >
                        {{ item }}
                      </li>
                    </ul>
                  </div>

                  <div>
                    <strong>추가 확인사항</strong>
                    <ul>
                      <li
                        v-for="item in finding.validationSteps"
                        :key="item"
                      >
                        {{ item }}
                      </li>
                    </ul>
                  </div>
                </div>

                <p
                  v-if="finding.risk"
                  class="awr-muted awr-finding-risk"
                >
                  판단 한계: {{ finding.risk }}
                </p>
              </article>
            </div>

            <div
              v-if="analysisToShow.missingInputs.length"
              class="awr-finding awr-missing-inputs"
            >
              <strong>추가로 필요한 정보</strong>
              <ul>
                <li
                  v-for="item in analysisToShow.missingInputs"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
            </div>
          </template>

          <div
            v-else-if="isAnalyzing"
            class="awr-empty compact"
          >
            리포트 분석 결과를 생성하고 있습니다...
          </div>

          <div
            v-else
            class="awr-empty compact"
          >
            리포트 분석 결과를 생성하지 못했습니다.
          </div>
        </section>

        <section class="awr-panel awr-wait-panel">
          <div class="awr-panel-header">
            <div>
              <h2 class="awr-panel-title">주요 대기 이벤트</h2>
              <p class="awr-muted awr-panel-description">
                DB 처리 지연에 영향을 준 주요 대기 항목입니다.
              </p>
            </div>

            <span class="awr-badge">
              {{ report.topWaitEvents.length }}
            </span>
          </div>

          <div
            v-if="report.topWaitEvents.length === 0"
            class="awr-empty compact"
          >
            분석할 대기 이벤트 정보가 없습니다.
          </div>

          <ul v-else class="awr-wait-list">
            <li
              v-for="event in report.topWaitEvents"
              :key="event.eventName"
            >
              <strong>{{ event.eventName }}</strong>
              <div class="awr-muted">
                {{ event.waitClass || '분류 없음' }}
                · DB Time {{ formatNumber(event.dbTimePercent) }}%
                · 총 대기시간 {{ formatNumber(event.totalWaitTimeSec) }}초
              </div>
            </li>
          </ul>
        </section>
      </div>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">부하 상위 SQL</h2>
            <p class="awr-muted awr-panel-description">
              수행시간과 자원 사용량이 높은 SQL입니다.
            </p>
          </div>

          <span class="awr-badge">
            {{ report.topSql.length }}
          </span>
        </div>

        <div
          v-if="report.topSql.length === 0"
          class="awr-empty"
        >
          추출된 상위 SQL 정보가 없습니다.
        </div>

        <div v-else class="awr-table-wrap">
          <table class="awr-table">
            <thead>
              <tr>
                <th>SQL_ID</th>
                <th>기준 항목</th>
                <th>수행시간(초)</th>
                <th>CPU 시간(초)</th>
                <th>Buffer Gets</th>
                <th>Disk Reads</th>
                <th>실행 횟수</th>
              </tr>
            </thead>

            <tbody>
              <tr
                v-for="metric in report.topSql"
                :key="`${metric.sectionName}-${metric.sqlId}-${metric.rankNo}`"
              >
                <td>
                  <button
                    class="awr-link"
                    type="button"
                    @click="askSql(metric.sqlId)"
                  >
                    {{ metric.sqlId }}
                  </button>
                </td>
                <td>{{ metric.sectionName }}</td>
                <td>{{ formatNumber(metric.elapsedTimeSec) }}</td>
                <td>{{ formatNumber(metric.cpuTimeSec) }}</td>
                <td>{{ formatNumber(metric.bufferGets) }}</td>
                <td>{{ formatNumber(metric.diskReads) }}</td>
                <td>{{ formatNumber(metric.executions) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="awr-panel awr-evidence-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">분석 근거 상세</h2>
          <span class="awr-badge">{{ report.sections.length }}</span>
        </div>

        <div class="awr-stack">
          <details
            v-for="section in report.sections"
            :key="`${section.sectionOrder}-${section.sectionName}`"
          >
            <summary>
              {{ section.sectionOrder }}. {{ section.sectionName }}
            </summary>
            <pre class="awr-code">{{ section.rawText }}</pre>
          </details>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { analyzeAwrReport, getAwrReport } from '@/api/awr'
import type { AnalysisResponse, ReportDetailResponse } from '@/types/awr'

const route = useRoute()
const router = useRouter()

const reportId = computed(() => Number(route.params.id))
const report = ref<ReportDetailResponse | null>(null)
const analysis = ref<AnalysisResponse | null>(null)
const isAnalyzing = ref(false)
const errorMessage = ref('')

const analysisToShow = computed(
  () => analysis.value || report.value?.latestAnalysis || null
)

const reportFilename = computed(() => {
  return report.value?.filename || 'AWR 분석 결과'
})

const isAnalysisComplete = computed(() => {
  const status = report.value?.status?.trim().toUpperCase()
  return status === 'INDEXED' || status === 'COMPLETED' || status === 'DONE'
})

const reportStatusLabel = computed(() => {
  if (isAnalyzing.value) {
    return '분석 중'
  }

  return isAnalysisComplete.value ? '분석 완료' : '처리 중'
})

const displayDbName = computed(() => {
  const dbName = report.value?.dbName?.trim() || ''

  if (isValidDbName(dbName)) {
    return dbName
  }

  const filename = report.value?.filename || ''
  const ifrsMatch = filename.match(/\b(IFRS[A-Za-z0-9_$#-]{1,30})\b/i)

  if (ifrsMatch?.[1]) {
    return ifrsMatch[1].toUpperCase()
  }

  const parenthesizedMatch = filename.match(/\(([A-Za-z][A-Za-z0-9_$#-]{2,30})\)/)

  if (parenthesizedMatch?.[1]) {
    return parenthesizedMatch[1]
  }

  return '정보 없음'
})

const displayInstanceName = computed(() => {
  const instanceName = report.value?.instanceName?.trim() || ''

  if (!instanceName || instanceName.toLowerCase() === 'unknown') {
    return '정보 없음'
  }

  return instanceName
})

const analysisPeriod = computed(() => {
  const begin = normalizeSnapTime(report.value?.snapBegin)
  const end = normalizeSnapTime(report.value?.snapEnd)

  if (!begin && !end) {
    return '정보 없음'
  }

  return `${begin || '-'} ~ ${end || '-'}`
})

onMounted(load)

async function load() {
  errorMessage.value = ''

  try {
    const detail = await getAwrReport(reportId.value)

    report.value = detail

    if (detail.latestAnalysis) {
      analysis.value = detail.latestAnalysis
      return
    }

    await runAnalyze()
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : 'AWR 리포트를 불러오지 못했습니다.'
  }
}

async function runAnalyze() {
  if (isAnalyzing.value) {
    return
  }

  isAnalyzing.value = true
  errorMessage.value = ''

  try {
    const result = await analyzeAwrReport(reportId.value)

    analysis.value = result

    if (report.value) {
      report.value.latestAnalysis = result
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '리포트 분석에 실패했습니다.'
  } finally {
    isAnalyzing.value = false
  }
}

function askSql(sqlId: string) {
  router.push({
    name: 'awr-chat',
    query: {
      reportId: reportId.value,
      question: `SQL_ID ${sqlId}의 부하 특성을 설명해줘`
    }
  })
}

function isValidDbName(value: string) {
  if (!value || value.toLowerCase() === 'unknown') {
    return false
  }

  if (value.length > 32) {
    return false
  }

  if (/awr|report|\.html?$/i.test(value)) {
    return false
  }

  if ((value.match(/_/g) || []).length > 4) {
    return false
  }

  return true
}

function normalizeSnapTime(value?: string | null) {
  const normalized = value?.trim() || ''

  if (!normalized) {
    return ''
  }

  if (/^(time|snap time|end snap time)$/i.test(normalized)) {
    return ''
  }

  return normalized
}

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) {
    return '-'
  }

  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: 2
  }).format(value)
}
</script>

<style src="./awr.css"></style>

<style scoped>
.awr-report-header-card {
  margin-bottom: 1rem;
  padding: 0.9rem 1rem;
  border: 1px solid #dce4ec;
  border-radius: 0.85rem;
  background: #fff;
  box-shadow: 0 0.2rem 0.65rem rgba(25, 45, 70, 0.05);
}

.awr-report-header-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.awr-report-title-area {
  min-width: 0;
  flex: 1;
}

.awr-report-eyebrow {
  margin: 0 0 0.35rem;
  color: #0a8f4d;
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0.06em;
}

.awr-report-title-area h1 {
  margin: 0;
  max-width: 100%;
  overflow: hidden;
  color: #101b2d;
  font-size: clamp(0.95rem, 1.15vw, 1.18rem);
  font-weight: 800;
  line-height: 1.35;
  letter-spacing: -0.015em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.awr-report-header-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 0.55rem;
}

.awr-report-status {
  display: inline-flex;
  align-items: center;
  min-height: 1.85rem;
  padding: 0.25rem 0.65rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 800;
  white-space: nowrap;
}

.awr-report-status.complete {
  color: #087743;
  background: #e7f7ee;
}

.awr-report-status.processing {
  color: #996000;
  background: #fff4d8;
}

.awr-report-meta {
  display: grid;
  grid-template-columns: minmax(9rem, 0.8fr) minmax(9rem, 0.8fr) minmax(14rem, 1.4fr);
  gap: 0.75rem;
  margin-top: 0.65rem;
  padding-top: 0.65rem;
  border-top: 1px solid #e7ebf0;
}

.awr-report-meta > div {
  min-width: 0;
}

.awr-report-meta span {
  display: block;
  margin-bottom: 0.15rem;
  color: #7a8797;
  font-size: 0.72rem;
  font-weight: 700;
}

.awr-report-meta strong {
  display: block;
  overflow: hidden;
  color: #26364a;
  font-size: 0.86rem;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.awr-report-main {
  align-items: start;
}

.awr-panel-description {
  margin: 0.25rem 0 0;
}

.awr-summary-text {
  margin: 0;
  line-height: 1.75;
}

.awr-finding-list {
  margin-top: 1rem;
}

.awr-finding-heading {
  display: flex;
  align-items: flex-start;
  gap: 0.7rem;
  margin-bottom: 0.8rem;
}

.awr-finding-heading h3 {
  margin: 0;
}

.awr-finding-heading p {
  margin: 0.2rem 0 0;
}

.awr-priority-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.8rem;
  height: 1.8rem;
  flex: 0 0 auto;
  border-radius: 50%;
  background: #0a8f4d;
  color: #fff;
  font-size: 0.78rem;
  font-weight: 800;
}

.awr-finding-risk {
  margin-top: 0.75rem;
}

.awr-missing-inputs {
  margin-top: 1rem;
}

.awr-wait-list {
  display: grid;
  gap: 0.65rem;
  padding: 0;
  margin: 0;
  list-style: none;
}

.awr-wait-list li {
  padding: 0.85rem;
  border: 1px solid #e0e6ed;
  border-radius: 0.65rem;
  background: #fff;
}

.awr-wait-list li strong {
  display: block;
  margin-bottom: 0.25rem;
}

.awr-evidence-panel {
  margin-top: 1rem;
}

@media (max-width: 900px) {
  .awr-report-header-main {
    flex-direction: column;
  }

  .awr-report-header-actions {
    width: 100%;
    justify-content: space-between;
  }

  .awr-report-meta {
    grid-template-columns: 1fr;
  }
}
</style>

<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>{{ report?.filename || 'AWR 분석 결과' }}</h1>
        <p v-if="report">{{ report.dbName }} / {{ report.instanceName }} · {{ report.snapBegin || '-' }} ~ {{ report.snapEnd || '-' }}</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-reports' })">목록</button>
        <button class="awr-btn" type="button" :disabled="!report" @click="router.push({ name: 'awr-chat', query: { reportId } })">Chat</button>
        <button class="awr-btn primary" type="button" :disabled="!report || isAnalyzing" @click="runAnalyze">
                  {{ isAnalyzing ? '분석 중' : '리포트 리뷰' }}
        </button>
      </div>
    </div>

    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <template v-if="report">
      <section class="awr-grid">
        <div class="awr-kpi">
          <div class="awr-kpi-label">Status</div>
          <div class="awr-kpi-value" style="font-size: 1.25rem;">{{ report.status }}</div>
          <div class="awr-kpi-sub">{{ status?.currentStep || '상태 확인 중' }}</div>
        </div>
        <div class="awr-kpi">
          <div class="awr-kpi-label">DB Time</div>
          <div class="awr-kpi-value">{{ report.dbTime || '-' }}</div>
          <div class="awr-kpi-sub">AWR header</div>
        </div>
        <div class="awr-kpi">
          <div class="awr-kpi-label">Top SQL</div>
          <div class="awr-kpi-value">{{ report.topSql.length }}</div>
          <div class="awr-kpi-sub">상위 metric row</div>
        </div>
        <div class="awr-kpi">
          <div class="awr-kpi-label">Wait Events</div>
          <div class="awr-kpi-value">{{ report.topWaitEvents.length }}</div>
          <div class="awr-kpi-sub">대기 근거</div>
        </div>
      </section>

      <section v-if="status" class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">처리 상태</h2>
          <span :class="['awr-badge', report.status === 'INDEXED' ? 'ok' : 'warn']">{{ status.progress }}%</span>
        </div>
        <div class="awr-progress"><span :style="{ width: `${status.progress}%` }"></span></div>
        <p class="awr-muted" style="margin-top: 0.75rem;">{{ status.completedSteps.join(' → ') }}</p>
        <ul v-if="status.warnings.length" class="awr-list">
          <li v-for="warning in status.warnings" :key="warning">{{ warning }}</li>
        </ul>
      </section>

      <div class="awr-split">
        <section class="awr-panel">
          <div class="awr-panel-header">
            <h2 class="awr-panel-title">Top SQL</h2>
          </div>
          <div v-if="report.topSql.length === 0" class="awr-empty">SQL ordered by 섹션이 아직 구조화되지 않았습니다.</div>
          <div v-else class="awr-table-wrap">
            <table class="awr-table">
              <thead>
                <tr>
                  <th>SQL_ID</th>
                  <th>Section</th>
                  <th>Elapsed</th>
                  <th>CPU</th>
                  <th>Gets</th>
                  <th>Reads</th>
                  <th>Execs</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="metric in report.topSql"
                  :key="`${metric.sectionName}-${metric.sqlId}-${metric.rankNo}`"
                >
                  <td>
                    <button class="awr-link" type="button" @click="askSql(metric.sqlId)">{{ metric.sqlId }}</button>
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

        <section class="awr-panel awr-side-panel">
          <div class="awr-panel-header">
            <div>
              <h2 class="awr-panel-title">리포트 요약</h2>
              <p class="awr-muted" style="margin: 0.25rem 0 0;">AWR 전체 부하와 병목 의심 지점에 대한 일반 진단</p>
            </div>
            <span v-if="analysisToShow" class="awr-badge">{{ analysisToShow.model }}</span>
          </div>

          <template v-if="analysisToShow">
            <p>{{ analysisToShow.summary }}</p>

            <div class="awr-stack" style="margin-top: 1rem;">
              <article v-for="finding in analysisToShow.topFindings" :key="finding.priority" class="awr-finding">
                <h3>#{{ finding.priority }} {{ finding.symptom }}</h3>
                <p v-if="finding.sqlId" class="awr-muted">SQL_ID {{ finding.sqlId }} · confidence {{ finding.confidence }}</p>
                <p v-else class="awr-muted">confidence {{ finding.confidence }}</p>

                <div class="awr-finding-grid compact">
                  <div>
                    <strong>근거</strong>
                    <ul>
                      <li v-for="item in finding.evidence" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                  <div>
                    <strong>진단</strong>
                    <ul>
                      <li v-for="item in finding.likelyCauses" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                  <div>
                    <strong>추가 확인</strong>
                    <ul>
                      <li v-for="item in finding.validationSteps" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                </div>

                <p v-if="finding.risk" class="awr-muted" style="margin-top: 0.75rem;">판단 한계: {{ finding.risk }}</p>
              </article>
            </div>

            <div v-if="analysisToShow.missingInputs.length" class="awr-finding" style="margin-top: 1rem;">
              <strong>추가로 필요한 정보</strong>
              <ul>
                <li v-for="item in analysisToShow.missingInputs" :key="item">{{ item }}</li>
              </ul>
            </div>
          </template>
          <div v-else-if="isAnalyzing" class="awr-empty compact">리포트 요약을 생성하고 있습니다...</div>
          <div v-else class="awr-empty compact">리포트 요약을 생성하지 못했습니다. 상단의 리포트 리뷰 버튼을 눌러 다시 시도해주세요.</div>
        </section>
      </div>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">Top Wait Events</h2>
        </div>
        <div v-if="report.topWaitEvents.length === 0" class="awr-empty">Foreground wait 섹션이 아직 구조화되지 않았습니다.</div>
        <ul v-else class="awr-list">
          <li v-for="event in report.topWaitEvents" :key="event.eventName">
            <strong>{{ event.eventName }}</strong>
            <div class="awr-muted">{{ event.waitClass }} · DB Time {{ formatNumber(event.dbTimePercent) }}% · Total {{ formatNumber(event.totalWaitTimeSec) }}</div>
          </li>
        </ul>
      </section>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">근거 섹션</h2>
          <span class="awr-badge">{{ report.sections.length }}</span>
        </div>
        <div class="awr-stack">
          <details v-for="section in report.sections" :key="`${section.sectionOrder}-${section.sectionName}`">
            <summary>{{ section.sectionOrder }}. {{ section.sectionName }}</summary>
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
import { analyzeAwrReport, getAwrReport, getAwrStatus } from '@/api/awr'
import type { AnalysisResponse, ReportDetailResponse, StatusResponse } from '@/types/awr'

const route = useRoute()
const router = useRouter()
const reportId = computed(() => Number(route.params.id))
const report = ref<ReportDetailResponse | null>(null)
const status = ref<StatusResponse | null>(null)
const analysis = ref<AnalysisResponse | null>(null)
const isAnalyzing = ref(false)
const errorMessage = ref('')

const analysisToShow = computed(() => analysis.value || report.value?.latestAnalysis || null)

onMounted(load)

async function load() {
  errorMessage.value = ''

  try {
    const [detail, currentStatus] = await Promise.all([
      getAwrReport(reportId.value),
      getAwrStatus(reportId.value)
    ])

    report.value = detail
    status.value = currentStatus

    // 저장된 리포트 리뷰가 있으면 바로 표시
    if (detail.latestAnalysis) {
      analysis.value = detail.latestAnalysis
      return
    }

    // 저장된 리뷰가 없으면 상태값과 관계없이 자동 생성
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
        : '리포트 리뷰 실행에 실패했습니다.'
  } finally {
    isAnalyzing.value = false
  }
}

function askSql(sqlId: string) {
  router.push({ name: 'awr-chat', query: { reportId: reportId.value, question: `SQL_ID ${sqlId}의 부하 특성을 설명해줘` } })
}

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) return '-'
  return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)
}
</script>

<style src="./awr.css"></style>

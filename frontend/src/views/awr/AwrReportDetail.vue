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
          {{ isAnalyzing ? '분석 중' : '병목 분석' }}
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
                  <th>Tuning</th>
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
                  :class="priorityRowClass(metric.sqlId)"
                >
                  <td>
                    <div class="awr-sql-id-cell">
                      <button class="awr-link" type="button" @click="askSql(metric.sqlId)">{{ metric.sqlId }}</button>
                      <span v-if="tuningPriorityFor(metric.sqlId)" :class="priorityBadgeClass(metric.sqlId)">Priority #{{ tuningPriorityFor(metric.sqlId) }}</span>
                    </div>
                  </td>
                  <td>
                    <button class="awr-btn compact" type="button" :disabled="isTuningSql(metric.sqlId)" @click="runSqlTuning(metric)">
                      {{ isTuningSql(metric.sqlId) ? 'Tuning...' : 'Tune' }}
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

        <section class="awr-panel awr-side-panel">
          <div class="awr-panel-header">
            <div>
              <h2 class="awr-panel-title">{{ sqlTuning ? `SQL Tuning - ${sqlTuning.sqlId}` : '튜닝 우선순위' }}</h2>
              <p v-if="sqlTuning" class="awr-muted" style="margin: 0.25rem 0 0;">confidence {{ sqlTuning.confidence }}</p>
            </div>
            <div class="awr-actions">
              <button v-if="sqlTuning" class="awr-btn compact" type="button" @click="clearSqlTuning">분석 보기</button>
              <span v-if="sqlTuning" class="awr-badge">{{ sqlTuning.model }}</span>
              <span v-else-if="analysisToShow" class="awr-badge">{{ analysisToShow.model }}</span>
            </div>
          </div>
          <template v-if="sqlTuning">
            <p>{{ sqlTuning.summary }}</p>

            <div class="awr-finding-grid compact" style="margin-top: 1rem;">
              <div>
                <strong>Symptoms</strong>
                <ul>
                  <li v-for="item in sqlTuning.symptoms" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div>
                <strong>Rewrite Checks</strong>
                <ul>
                  <li v-for="item in sqlTuning.rewriteRecommendations" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div>
                <strong>Validation</strong>
                <ul>
                  <li v-for="item in sqlTuning.validationSteps" :key="item">{{ item }}</li>
                </ul>
              </div>
              <div>
                <strong>Missing Inputs</strong>
                <ul>
                  <li v-for="item in sqlTuning.missingInputs" :key="item">{{ item }}</li>
                </ul>
              </div>
            </div>

            <div class="awr-stack" style="margin-top: 1rem;">
              <article v-for="item in sqlTuning.indexRecommendations" :key="`${item.tableName}-${item.columns.join('-')}`" class="awr-finding">
                <h3>{{ item.tableName || 'Index candidate' }}</h3>
                <p class="awr-muted">{{ item.reason }}</p>
                <p><strong>Columns:</strong> {{ formatColumns(item.columns) }}</p>
                <pre v-if="item.ddlCandidate" class="awr-code">{{ item.ddlCandidate }}</pre>
                <p><strong>Expected benefit:</strong> {{ item.expectedBenefit }}</p>
                <p class="awr-muted">{{ item.risk }}</p>
              </article>
              <div v-if="sqlTuning.indexRecommendations.length === 0" class="awr-empty compact">
                No concrete index DDL candidate was generated. Add execution plan, schema DDL, existing indexes, and bind samples before applying index changes.
              </div>
            </div>
          </template>
          <template v-else-if="analysisToShow">
            <p>{{ analysisToShow.summary }}</p>
            <div class="awr-stack" style="margin-top: 1rem;">
              <article v-for="finding in analysisToShow.topFindings" :key="finding.priority" class="awr-finding">
                <h3>#{{ finding.priority }} SQL_ID {{ finding.sqlId }}</h3>
                <p class="awr-muted">{{ finding.symptom }} · confidence {{ finding.confidence }}</p>
                <div class="awr-finding-grid compact">
                  <div>
                    <strong>근거</strong>
                    <ul>
                      <li v-for="item in finding.evidence" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                  <div>
                    <strong>가설</strong>
                    <ul>
                      <li v-for="item in finding.likelyCauses" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                  <div>
                    <strong>권장 조치</strong>
                    <ul>
                      <li v-for="item in finding.recommendedActions" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                  <div>
                    <strong>검증</strong>
                    <ul>
                      <li v-for="item in finding.validationSteps" :key="item">{{ item }}</li>
                    </ul>
                  </div>
                </div>
                <p class="awr-muted" style="margin-top: 0.75rem;">{{ finding.risk }}</p>
              </article>
            </div>
          </template>
          <div v-else class="awr-empty compact">아직 분석 결과가 없습니다.</div>

          <div v-if="sqlTuningHistory.length" class="awr-side-history">
            <div class="awr-panel-header compact">
              <h3 class="awr-panel-title">Tuning History</h3>
              <span class="awr-badge">{{ sqlTuningHistory.length }}</span>
            </div>
            <ul class="awr-history-list compact">
              <li v-for="item in sqlTuningHistory" :key="item.tuningId">
                <button
                  :class="['awr-history-item', sqlTuning?.tuningId === item.tuningId ? 'active' : '']"
                  type="button"
                  @click="selectSqlTuning(item)"
                >
                  <span class="awr-history-question">SQL_ID {{ item.sqlId }} · {{ item.summary }}</span>
                  <span class="awr-history-meta">{{ item.model }} · confidence {{ item.confidence }} · {{ formatDate(item.createdAt) }}</span>
                </button>
              </li>
            </ul>
          </div>
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
import { analyzeAwrReport, getAwrReport, getAwrStatus, getAwrSqlTuningHistory, tuneAwrSql } from '@/api/awr'
import type { AnalysisResponse, ReportDetailResponse, SqlMetricResponse, SqlTuningResponse, StatusResponse } from '@/types/awr'

const route = useRoute()
const router = useRouter()
const reportId = computed(() => Number(route.params.id))
const report = ref<ReportDetailResponse | null>(null)
const status = ref<StatusResponse | null>(null)
const analysis = ref<AnalysisResponse | null>(null)
const sqlTuning = ref<SqlTuningResponse | null>(null)
const sqlTuningHistory = ref<SqlTuningResponse[]>([])
const tuningSqlId = ref<string | null>(null)
const isAnalyzing = ref(false)
const errorMessage = ref('')

const analysisToShow = computed(() => analysis.value || report.value?.latestAnalysis || null)
const tuningPriorityBySqlId = computed(() => {
  const priorities = new Map<string, number>()
  for (const finding of analysisToShow.value?.topFindings || []) {
    if (finding.sqlId) {
      const key = finding.sqlId.toLowerCase()
      const current = priorities.get(key)
      if (!current || finding.priority < current) {
        priorities.set(key, finding.priority)
      }
    }
  }
  return priorities
})

onMounted(load)

async function load() {
  errorMessage.value = ''
  try {
    const [detail, currentStatus, tuningHistory] = await Promise.all([
      getAwrReport(reportId.value),
      getAwrStatus(reportId.value),
      getAwrSqlTuningHistory(reportId.value)
    ])
    report.value = detail
    status.value = currentStatus
    sqlTuningHistory.value = tuningHistory
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'AWR 리포트를 불러오지 못했습니다.'
  }
}

async function runAnalyze() {
  isAnalyzing.value = true
  try {
    analysis.value = await analyzeAwrReport(reportId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '분석 실행에 실패했습니다.'
  } finally {
    isAnalyzing.value = false
  }
}

async function runSqlTuning(metric: SqlMetricResponse) {
  tuningSqlId.value = metric.sqlId
  try {
    sqlTuning.value = await tuneAwrSql(reportId.value, metric.sqlId)
    sqlTuningHistory.value = await getAwrSqlTuningHistory(reportId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'SQL tuning failed.'
  } finally {
    tuningSqlId.value = null
  }
}

function isTuningSql(sqlId: string) {
  return tuningSqlId.value === sqlId
}

function tuningPriorityFor(sqlId: string) {
  return tuningPriorityBySqlId.value.get(sqlId.toLowerCase()) || null
}

function priorityRowClass(sqlId: string) {
  const priority = tuningPriorityFor(sqlId)
  return priority ? ['awr-priority-row', priorityTone(priority)] : []
}

function priorityBadgeClass(sqlId: string) {
  const priority = tuningPriorityFor(sqlId)
  return ['awr-priority-badge', priority ? priorityTone(priority) : 'priority-other']
}

function priorityTone(priority: number) {
  if (priority === 1) return 'priority-1'
  if (priority === 2) return 'priority-2'
  return 'priority-other'
}

function selectSqlTuning(item: SqlTuningResponse) {
  sqlTuning.value = item
}

function clearSqlTuning() {
  sqlTuning.value = null
}

function askSql(sqlId: string) {
  router.push({ name: 'awr-chat', query: { reportId: reportId.value, question: `SQL_ID ${sqlId}의 문제 원인은?` } })
}

function formatColumns(columns: string[]) {
  return columns.length ? columns.join(', ') : '-'
}

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) return '-'
  return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)
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

<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>SQLAdvisor</h1>
        <p>Direct DB 튜닝과 AWR 분석 흐름을 한 화면에서 확인합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn primary" type="button" @click="openSqlTuning">SQL 튜닝</button>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-upload' })">AWR 업로드</button>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-chat' })">Chat</button>
      </div>
    </div>

    <div v-if="dashboardError" class="awr-empty compact">{{ dashboardError }}</div>

    <section class="awr-grid">
      <div class="awr-kpi">
        <div class="awr-kpi-label">Tuning Runs</div>
        <div class="awr-kpi-value">{{ workspaceMetric(tuningCount) }}</div>
        <div class="awr-kpi-sub">최근 SQL 튜닝 이력</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Saved DB</div>
        <div class="awr-kpi-value">{{ workspaceMetric(connectionCount) }}</div>
        <div class="awr-kpi-sub">Direct DB 연결</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">AWR Ready</div>
        <div class="awr-kpi-value">{{ readyCount }}</div>
        <div class="awr-kpi-sub">INDEXED 상태</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">AWR SQL Rows</div>
        <div class="awr-kpi-value">{{ formatNumber(totalSql) }}</div>
        <div class="awr-kpi-sub">SQL ordered section</div>
      </div>
    </section>

    <div class="dashboard-workbench-grid">
      <section class="awr-panel dashboard-focus-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">Direct DB 튜닝</h2>
            <p class="dashboard-panel-text">SQL_ID 기반 튜닝 작업을 시작합니다.</p>
          </div>
          <span class="awr-badge ok">Primary</span>
        </div>

        <template v-if="canUseWorkspace">
          <div class="dashboard-stat-row">
            <div>
              <span>저장 연결</span>
              <strong>{{ connectionCount }}</strong>
            </div>
            <div>
              <span>Index 후보</span>
              <strong>{{ indexCandidateCount }}</strong>
            </div>
            <div>
              <span>Missing Inputs</span>
              <strong>{{ missingInputCount }}</strong>
            </div>
          </div>

          <div v-if="privateLoadMessage" class="awr-empty compact">{{ privateLoadMessage }}</div>
          <div v-else-if="connections.length === 0" class="awr-empty compact">
            저장된 Direct DB 연결이 없습니다.
          </div>
          <ul v-else class="dashboard-mini-list">
            <li v-for="connection in visibleConnections" :key="connection.id" class="dashboard-mini-row">
              <div>
                <strong>{{ connection.name }}</strong>
                <span>{{ connection.username }} · {{ connection.jdbcUrl }}</span>
              </div>
              <span v-if="connection.monitoringEnabled" class="awr-badge small">Monitoring</span>
            </li>
          </ul>

          <div class="awr-actions dashboard-actions">
            <button class="awr-btn primary" type="button" @click="openSqlTuning">Direct DB 튜닝 시작</button>
            <button class="awr-btn" type="button" @click="openSqlTuning">연결 관리</button>
          </div>
        </template>
        <div v-else class="awr-empty compact">로그인 후 Direct DB 튜닝 이력을 확인할 수 있습니다.</div>
      </section>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">최근 튜닝 결과</h2>
            <p class="dashboard-panel-text">최근 실행된 SQL 튜닝 결과입니다.</p>
          </div>
          <button class="awr-btn compact" type="button" :disabled="!canUseWorkspace" @click="openSqlTuning">전체 보기</button>
        </div>

        <div v-if="!canUseWorkspace" class="awr-empty compact">로그인 후 튜닝 결과를 확인할 수 있습니다.</div>
        <div v-else-if="privateLoadMessage" class="awr-empty compact">{{ privateLoadMessage }}</div>
        <div v-else-if="recentTuning.length === 0" class="awr-empty compact">아직 실행된 SQL 튜닝 결과가 없습니다.</div>
        <ul v-else class="dashboard-tuning-list">
          <li v-for="item in recentTuning" :key="item.tuningId">
            <button class="dashboard-tuning-item" type="button" @click="openSqlTuning">
              <span class="dashboard-tuning-main">
                <strong>{{ item.sqlId }}</strong>
                <span>{{ item.confidence }}</span>
              </span>
              <span class="dashboard-tuning-summary">{{ item.summary }}</span>
              <span class="dashboard-tuning-meta">
                Index {{ item.indexRecommendations.length }} · Missing {{ item.missingInputs.length }} · {{ formatDate(item.createdAt) }}
              </span>
            </button>
          </li>
        </ul>
      </section>
    </div>

    <section class="awr-panel">
      <div class="awr-panel-header">
        <div>
          <h2 class="awr-panel-title">최근 AWR 리포트</h2>
          <p class="dashboard-panel-text">업로드된 AWR 분석 현황입니다.</p>
        </div>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-reports' })">전체 보기</button>
      </div>

      <div v-if="reports.length === 0" class="awr-empty">AWR HTML 또는 TXT 파일을 업로드하면 분석 현황이 표시됩니다.</div>
      <ul v-else class="awr-list">
        <li v-for="report in recentReports" :key="report.id">
          <button class="awr-link" type="button" @click="router.push({ name: 'awr-report-detail', params: { id: report.id } })">
            {{ report.filename }}
          </button>
          <div class="dashboard-report-meta">
            <span>{{ report.dbName || '-' }} / {{ report.instanceName || '-' }}</span>
            <span>SQL {{ report.topSqlCount }} · Wait {{ report.waitEventCount }} · {{ report.status }}</span>
          </div>
        </li>
      </ul>
    </section>
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
const dashboardError = ref('')
const privateLoadMessage = ref('')

const canUseWorkspace = computed(() => !authStore.authEnabled || authStore.isAuthenticated)
const totalSql = computed(() => reports.value.reduce((sum, report) => sum + report.topSqlCount, 0))
const readyCount = computed(() => reports.value.filter((report) => report.status === 'INDEXED').length)
const tuningCount = computed(() => history.value.length)
const connectionCount = computed(() => connections.value.length)
const recentReports = computed(() => reports.value.slice(0, 5))
const recentTuning = computed(() => history.value.slice(0, 4))
const visibleConnections = computed(() => connections.value.slice(0, 3))
const missingInputCount = computed(() =>
  history.value.reduce((sum, item) => sum + item.missingInputs.length, 0)
)
const indexCandidateCount = computed(() =>
  history.value.reduce((sum, item) => sum + item.indexRecommendations.length, 0)
)

onMounted(loadDashboard)

async function loadDashboard() {
  dashboardError.value = ''
  privateLoadMessage.value = ''
  try {
    reports.value = await getAwrReports()
  } catch (error) {
    dashboardError.value = error instanceof Error ? error.message : '대시보드 AWR 현황을 불러오지 못했습니다.'
  }

  if (!canUseWorkspace.value) return

  try {
    const [tuningHistory, savedConnections] = await Promise.all([
      getSqlTuningHistory(),
      getTargetDbConnections()
    ])
    history.value = tuningHistory
    connections.value = savedConnections
  } catch (error) {
    privateLoadMessage.value = error instanceof Error ? error.message : 'SQL 튜닝 작업 현황을 불러오지 못했습니다.'
  }
}

function openSqlTuning() {
  router.push({ name: 'sql-tuning' })
}

function workspaceMetric(value: number) {
  return canUseWorkspace.value ? formatNumber(value) : '-'
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

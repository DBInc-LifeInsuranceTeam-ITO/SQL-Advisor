<template>
  <div class="awr-page awr-report-list-page">
    <div class="awr-upload-hero awr-report-list-hero">
      <div>
        <p class="awr-upload-eyebrow">AWR Report Analysis</p>
        <h1 class="awr-main-title">AWR 분석 결과</h1>
        <p>
          업로드된 AWR 리포트의 분석 상태, 분석 구간, SQL/Wait Event 추출 현황을
          한 화면에서 확인할 수 있습니다.
        </p>
      </div>
    </div>

    <section class="awr-report-list-kpi-grid">
      <div class="awr-kpi awr-report-list-kpi accent-green">
        <div class="awr-kpi-label">등록 리포트</div>
        <div class="awr-kpi-value">{{ reports.length }}</div>
        <div class="awr-kpi-sub">전체 등록 건수</div>
      </div>

      <div class="awr-kpi awr-report-list-kpi accent-blue">
        <div class="awr-kpi-label">분석 완료</div>
        <div class="awr-kpi-value">{{ indexedCount }}</div>
        <div class="awr-kpi-sub">결과 조회 가능</div>
      </div>

      <div class="awr-kpi awr-report-list-kpi accent-amber">
        <div class="awr-kpi-label">SQL 지표</div>
        <div class="awr-kpi-value">{{ totalSql }}</div>
        <div class="awr-kpi-sub">추출 SQL 수</div>
      </div>

      <div class="awr-kpi awr-report-list-kpi accent-slate">
        <div class="awr-kpi-label">대기 이벤트</div>
        <div class="awr-kpi-value">{{ totalWaits }}</div>
        <div class="awr-kpi-sub">추출 Wait Event 수</div>
      </div>
    </section>

    <section class="awr-panel awr-upload-card awr-report-list-card">
      <div class="awr-upload-section-title awr-report-list-title-row">
        <div>
          <h2>리포트 목록</h2>
          <p>행을 선택하면 상세 분석 결과 화면으로 이동합니다.</p>
        </div>

        <div class="awr-actions">
          <span class="awr-format-badge awr-report-list-total">
            TOTAL {{ reports.length }}
          </span>
        </div>
      </div>

      <div v-if="errorMessage" class="awr-empty awr-report-list-error">
        {{ errorMessage }}
      </div>

      <div v-else-if="isLoading" class="awr-empty awr-report-list-empty">
        리포트 목록을 불러오는 중입니다.
      </div>

      <div v-else-if="reports.length === 0" class="awr-empty awr-report-list-empty">
        등록된 AWR 리포트가 없습니다.
      </div>

      <div v-else class="awr-table-wrap awr-report-list-table-wrap">
        <table class="awr-table awr-report-list-table">
          <thead>
            <tr>
              <th>파일명</th>
              <th>DB / 인스턴스</th>
              <th>분석 구간</th>
              <th>공유 여부</th>
              <th>상태</th>
              <th>추출 결과</th>
              <th>등록일시</th>
            </tr>
          </thead>

          <tbody>
            <tr
              v-for="report in reports"
              :key="report.id"
              class="awr-report-list-row clickable"
              tabindex="0"
              @click="goToReportDetail(report.id)"
              @keydown.enter="goToReportDetail(report.id)"
            >
              <td>
                <span class="awr-link awr-report-list-file">
                  {{ report.filename }}
                </span>
              </td>

              <td>
                <div class="awr-report-list-db">
                  <strong>{{ normalizeDbName(report.dbName) }}</strong>
                  <span>{{ normalizeInstanceName(report.instanceName) }}</span>
                </div>
              </td>

              <td>
                <div class="awr-report-list-snapshot">
                  <template v-if="report.snapBegin || report.snapEnd">
                    <span>{{ report.snapBegin || '-' }}</span>
                    <em>~</em>
                    <span>{{ report.snapEnd || '-' }}</span>
                  </template>
                  <span v-else>-</span>
                </div>
              </td>

              <td>
                <span
                  :class="[
                    'awr-report-list-visibility',
                    report.visibility === 'PRIVATE' ? 'private' : 'shared'
                  ]"
                >
                  {{ report.visibility === 'PRIVATE' ? '비공유' : '공유' }}
                </span>
              </td>

              <td>
                <span :class="['awr-status-chip', statusClass(report.status)]">
                  {{ statusLabel(report.status) }}
                </span>
              </td>

              <td>
                <div class="awr-report-list-extract">
                  <span>SQL {{ report.topSqlCount }}건</span>
                  <span>Wait {{ report.waitEventCount }}건</span>
                </div>
              </td>

              <td>
                <span class="awr-report-list-date">
                  {{ formatDateTime(report.uploadedAt) }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAwrReports } from '@/api/awr'
import type { ReportSummaryResponse } from '@/types/awr'

const router = useRouter()

const reports = ref<ReportSummaryResponse[]>([])
const isLoading = ref(false)
const errorMessage = ref('')

const indexedCount = computed(() => {
  return reports.value.filter((report) => {
    const status = normalizeStatus(report.status)

    return status === 'INDEXED' || status === 'COMPLETED' || status === 'DONE'
  }).length
})

const totalSql = computed(() => {
  return reports.value.reduce((sum, report) => sum + report.topSqlCount, 0)
})

const totalWaits = computed(() => {
  return reports.value.reduce((sum, report) => sum + report.waitEventCount, 0)
})

onMounted(loadReports)

async function loadReports() {
  isLoading.value = true
  errorMessage.value = ''

  try {
    reports.value = await getAwrReports()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '리포트 목록을 불러오지 못했습니다.'
  } finally {
    isLoading.value = false
  }
}

function goToReportDetail(reportId: number) {
  router.push({
    name: 'awr-report-detail',
    params: { id: reportId }
  })
}

function normalizeStatus(status?: string | null) {
  return status?.trim().toUpperCase() || ''
}

function statusLabel(status?: string | null) {
  const normalized = normalizeStatus(status)

  if (normalized === 'QUEUED') return '분석 대기'
  if (normalized === 'PROCESSING' || normalized === 'PARSING' || normalized === 'INDEXING') return '분석 중'
  if (normalized === 'INDEXED' || normalized === 'COMPLETED' || normalized === 'DONE') return '분석 완료'
  if (normalized === 'FAILED' || normalized === 'ERROR') return '분석 실패'

  return status || '상태 확인'
}

function statusClass(status?: string | null) {
  const normalized = normalizeStatus(status)

  if (normalized === 'INDEXED' || normalized === 'COMPLETED' || normalized === 'DONE') return 'done'
  if (normalized === 'FAILED' || normalized === 'ERROR') return 'failed'
  if (normalized === 'PROCESSING' || normalized === 'PARSING' || normalized === 'INDEXING') return 'processing'

  return 'waiting'
}

function normalizeDbName(value?: string | null) {
  if (!value || value.trim() === '') return '-'
  if (value.trim().toLowerCase() === 'unknown') return 'DB 미확인'

  return value
}

function normalizeInstanceName(value?: string | null) {
  if (!value || value.trim() === '') return '-'
  if (value.trim().toLowerCase() === 'unknown') return 'Instance 미확인'

  return value
}

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short'
  }).format(new Date(value))
}
</script>

<style src="./awr.css"></style>

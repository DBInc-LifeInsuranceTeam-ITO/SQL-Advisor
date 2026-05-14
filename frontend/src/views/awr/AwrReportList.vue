<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>AWR 리포트</h1>
        <p>업로드된 AWR의 snapshot, 파싱 상태, 추출된 Top SQL과 Wait Event 개수를 확인합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn primary" type="button" @click="router.push({ name: 'awr-upload' })">AWR 업로드</button>
      </div>
    </div>

    <section class="awr-grid">
      <div class="awr-kpi">
        <div class="awr-kpi-label">Reports</div>
        <div class="awr-kpi-value">{{ reports.length }}</div>
        <div class="awr-kpi-sub">현재 세션</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Indexed</div>
        <div class="awr-kpi-value">{{ indexedCount }}</div>
        <div class="awr-kpi-sub">분석 가능</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">SQL Metrics</div>
        <div class="awr-kpi-value">{{ totalSql }}</div>
        <div class="awr-kpi-sub">구조화 row</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Wait Events</div>
        <div class="awr-kpi-value">{{ totalWaits }}</div>
        <div class="awr-kpi-sub">추출 이벤트</div>
      </div>
    </section>

    <section class="awr-panel">
      <div class="awr-panel-header">
        <h2 class="awr-panel-title">목록</h2>
        <button class="awr-btn" type="button" :disabled="isLoading" @click="loadReports">새로고침</button>
      </div>

      <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>
      <div v-else-if="!isLoading && reports.length === 0" class="awr-empty">등록된 AWR 리포트가 없습니다.</div>
      <div v-else class="awr-table-wrap">
        <table class="awr-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>파일명</th>
              <th>DB / Instance</th>
              <th>Snapshot</th>
              <th>Status</th>
              <th>SQL</th>
              <th>Wait</th>
              <th>업로드</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="report in reports" :key="report.id">
              <td>{{ report.id }}</td>
              <td>
                <button class="awr-link" type="button" @click="router.push({ name: 'awr-report-detail', params: { id: report.id } })">
                  {{ report.filename }}
                </button>
              </td>
              <td>{{ report.dbName }} / {{ report.instanceName }}</td>
              <td>{{ report.snapBegin || '-' }}<br />{{ report.snapEnd || '-' }}</td>
              <td><span :class="['awr-badge', report.status === 'INDEXED' ? 'ok' : 'warn']">{{ report.status }}</span></td>
              <td>{{ report.topSqlCount }}</td>
              <td>{{ report.waitEventCount }}</td>
              <td>{{ formatDateTime(report.uploadedAt) }}</td>
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

const indexedCount = computed(() => reports.value.filter((report) => report.status === 'INDEXED').length)
const totalSql = computed(() => reports.value.reduce((sum, report) => sum + report.topSqlCount, 0))
const totalWaits = computed(() => reports.value.reduce((sum, report) => sum + report.waitEventCount, 0))

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

function formatDateTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    dateStyle: 'short',
    timeStyle: 'short'
  }).format(new Date(value))
}
</script>

<style src="./awr.css"></style>

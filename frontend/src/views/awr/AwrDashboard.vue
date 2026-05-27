<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>SQLAdvisor</h1>
        <p>AWR 기반 SQL_ID, DB Time, Wait profile을 구조화하고 분석 우선순위를 정리합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn primary" type="button" @click="router.push({ name: 'awr-upload' })">AWR 업로드</button>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-chat' })">Chat</button>
      </div>
    </div>

    <section class="awr-grid">
      <div class="awr-kpi">
        <div class="awr-kpi-label">Reports</div>
        <div class="awr-kpi-value">{{ reports.length }}</div>
        <div class="awr-kpi-sub">업로드된 AWR</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Top SQL Rows</div>
        <div class="awr-kpi-value">{{ totalSql }}</div>
        <div class="awr-kpi-sub">SQL ordered section</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Wait Events</div>
        <div class="awr-kpi-value">{{ totalWaits }}</div>
        <div class="awr-kpi-sub">foreground 중심</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Ready</div>
        <div class="awr-kpi-value">{{ readyCount }}</div>
        <div class="awr-kpi-sub">INDEXED 상태</div>
      </div>
    </section>

    <section class="awr-panel">
      <div class="awr-panel-header">
        <h2 class="awr-panel-title">최근 리포트</h2>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-reports' })">전체 보기</button>
      </div>

      <div v-if="reports.length === 0" class="awr-empty">AWR HTML 또는 TXT 파일을 업로드하면 분석 현황이 표시됩니다.</div>
      <ul v-else class="awr-list">
        <li v-for="report in reports.slice(0, 5)" :key="report.id">
          <button class="awr-link" type="button" @click="router.push({ name: 'awr-report-detail', params: { id: report.id } })">
            {{ report.filename }}
          </button>
          <div class="awr-muted">{{ report.dbName }} / {{ report.instanceName }} · SQL {{ report.topSqlCount }} · Wait {{ report.waitEventCount }}</div>
        </li>
      </ul>
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

const totalSql = computed(() => reports.value.reduce((sum, report) => sum + report.topSqlCount, 0))
const totalWaits = computed(() => reports.value.reduce((sum, report) => sum + report.waitEventCount, 0))
const readyCount = computed(() => reports.value.filter((report) => report.status === 'INDEXED').length)

onMounted(async () => {
  try {
    reports.value = await getAwrReports()
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  }
})
</script>

<style src="./awr.css"></style>

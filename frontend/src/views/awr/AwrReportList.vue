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
      </div>

      <div class="awr-report-search-row">
        <div class="awr-report-search-box">
          <span class="awr-report-search-label">검색</span>
          <input
            v-model="searchKeyword"
            class="awr-report-search-input"
            type="text"
            placeholder="파일명, DB명, 인스턴스명으로 검색"
          />
          <button
            v-if="searchKeyword"
            class="awr-report-search-clear"
            type="button"
            @click="clearSearch"
          >
            초기화
          </button>
        </div>

        <div class="awr-report-search-count">
          {{ filteredReports.length }}건 표시
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

      <div v-else-if="filteredReports.length === 0" class="awr-empty awr-report-list-empty">
        검색 조건에 해당하는 리포트가 없습니다.
      </div>

      <template v-else>
        <div class="awr-table-wrap awr-report-list-table-wrap">
          <table class="awr-table awr-report-list-table">
            <colgroup>
              <col class="awr-col-file" />
              <col class="awr-col-db" />
              <col class="awr-col-period" />
              <col class="awr-col-uploader" />
              <col class="awr-col-status" />
              <col class="awr-col-extract" />
              <col class="awr-col-date" />
              <col class="awr-col-action" />
            </colgroup>

            <thead>
              <tr>
                <th>파일명</th>
                <th>DB / 인스턴스</th>
                <th>분석 구간</th>
                <th>등록자</th>
                <th>상태</th>
                <th>추출 결과</th>
                <th>등록일시</th>
                <th>관리</th>
              </tr>
            </thead>

            <tbody>
              <tr
                v-for="report in pagedReports"
                :key="report.id"
                class="awr-report-list-row clickable"
                tabindex="0"
                @click="goToReportDetail(report.id)"
                @keydown.enter="goToReportDetail(report.id)"
              >
                <td>
                  <span class="awr-link awr-report-list-file" :title="report.filename">
                    {{ report.filename }}
                  </span>
                </td>

                <td>
                  <div class="awr-report-list-db">
                    <strong :title="normalizeDbName(report.dbName)">
                      {{ normalizeDbName(report.dbName) }}
                    </strong>
                    <span :title="normalizeInstanceName(report.instanceName)">
                      {{ normalizeInstanceName(report.instanceName) }}
                    </span>
                  </div>
                </td>

                <td>
                  <div class="awr-report-list-snapshot">
                    <template v-if="report.snapBegin || report.snapEnd">
                      <span :title="formatSnapshotText(report.snapBegin)">
                        {{ formatSnapshotText(report.snapBegin) || '-' }}
                      </span>
                      <em>~</em>
                      <span :title="formatSnapshotText(report.snapEnd)">
                        {{ formatSnapshotText(report.snapEnd) || '-' }}
                      </span>
                    </template>
                    <span v-else>-</span>
                  </div>
                </td>

                <td>
                  <span class="awr-report-uploader" :title="report.uploadedByName || '미확인'">
                    {{ formatUploaderName(report.uploadedByName) }}
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

                <td>
                  <button
                    class="awr-report-delete-button"
                    type="button"
                    :disabled="deletingReportId === report.id"
                    @click.stop="deleteReport(report)"
                  >
                    {{ deletingReportId === report.id ? '삭제 중' : '삭제' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="totalPages > 1" class="awr-report-pagination">
          <button
            class="awr-page-nav-button"
            type="button"
            :disabled="currentPage === 1"
            @click="goToPage(currentPage - 1)"
          >
            ‹
          </button>

          <button
            v-for="page in visiblePages"
            :key="page.key"
            class="awr-page-number-button"
            :class="{ active: page.page === currentPage, ellipsis: page.type === 'ellipsis' }"
            type="button"
            :disabled="page.type === 'ellipsis'"
            @click="page.type === 'page' && goToPage(page.page)"
          >
            {{ page.label }}
          </button>

          <button
            class="awr-page-nav-button"
            type="button"
            :disabled="currentPage === totalPages"
            @click="goToPage(currentPage + 1)"
          >
            ›
          </button>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { deleteAwrReport, getAwrReports } from '@/api/awr'
import type { ReportSummaryResponse } from '@/types/awr'

type PageItem = {
  key: string
  type: 'page' | 'ellipsis'
  page: number
  label: string
}

const router = useRouter()

const reports = ref<ReportSummaryResponse[]>([])
const isLoading = ref(false)
const errorMessage = ref('')
const searchKeyword = ref('')
const deletingReportId = ref<number | null>(null)

const pageSize = 10
const currentPage = ref(1)

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

const filteredReports = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()

  if (!keyword) return reports.value

  return reports.value.filter((report) => {
    const filename = report.filename?.toLowerCase() || ''
    const dbName = report.dbName?.toLowerCase() || ''
    const instanceName = report.instanceName?.toLowerCase() || ''

    return filename.includes(keyword) || dbName.includes(keyword) || instanceName.includes(keyword)
  })
})

const totalPages = computed(() => {
  return Math.max(1, Math.ceil(filteredReports.value.length / pageSize))
})

const pagedReports = computed(() => {
  const start = (currentPage.value - 1) * pageSize
  const end = start + pageSize

  return filteredReports.value.slice(start, end)
})

const visiblePages = computed<PageItem[]>(() => {
  const total = totalPages.value
  const current = currentPage.value
  const pages: PageItem[] = []
  const maxVisiblePages = 5

  if (total <= maxVisiblePages + 1) {
    for (let page = 1; page <= total; page += 1) {
      pages.push(createPageItem(page))
    }

    return pages
  }

  let start = Math.max(1, current - 2)
  const end = Math.min(total, start + maxVisiblePages - 1)

  if (end - start + 1 < maxVisiblePages) {
    start = Math.max(1, end - maxVisiblePages + 1)
  }

  for (let page = start; page <= end; page += 1) {
    pages.push(createPageItem(page))
  }

  if (end < total) {
    pages.push({
      key: 'ellipsis-right',
      type: 'ellipsis',
      page: -1,
      label: '...'
    })
    pages.push(createPageItem(total))
  }

  return pages
})

watch(searchKeyword, () => {
  currentPage.value = 1
})

watch(totalPages, (nextTotalPages) => {
  if (currentPage.value > nextTotalPages) {
    currentPage.value = nextTotalPages
  }
})

onMounted(loadReports)

async function loadReports() {
  isLoading.value = true
  errorMessage.value = ''

  try {
    reports.value = await getAwrReports()
    currentPage.value = 1
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '리포트 목록을 불러오지 못했습니다.'
  } finally {
    isLoading.value = false
  }
}

async function deleteReport(report: ReportSummaryResponse) {
  const confirmed = window.confirm('삭제하시겠습니까?')

  if (!confirmed) {
    return
  }

  deletingReportId.value = report.id
  errorMessage.value = ''

  try {
    const result = await deleteAwrReport(report.id)

    reports.value = reports.value.filter((item) => item.id !== report.id)

    if (result.warnings.length > 0) {
      window.alert(`리포트는 삭제됐지만 일부 파일 삭제 경고가 있습니다.\n\n${result.warnings.join('\n')}`)
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '리포트 삭제에 실패했습니다.'
  } finally {
    deletingReportId.value = null
  }
}

function createPageItem(page: number): PageItem {
  return {
    key: `page-${page}`,
    type: 'page',
    page,
    label: String(page)
  }
}

function goToPage(page: number) {
  if (page < 1 || page > totalPages.value) return

  currentPage.value = page
}

function goToReportDetail(reportId: number) {
  router.push({
    name: 'awr-report-detail',
    params: { id: reportId }
  })
}

function clearSearch() {
  searchKeyword.value = ''
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

function formatSnapshotText(value?: string | null) {
  if (!value || value.trim() === '') return ''

  return value.replace(/\s+/g, ' ').trim()
}
function formatUploaderName(value?: string | null) {
  if (!value || value.trim() === '') return '미확인'

  return value.split('/')[0].trim()
}
function formatDateTime(value: string) {
  const date = new Date(value)

  return new Intl.DateTimeFormat('ko-KR', {
    year: '2-digit',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}
</script>

<style src="./awr.css"></style>

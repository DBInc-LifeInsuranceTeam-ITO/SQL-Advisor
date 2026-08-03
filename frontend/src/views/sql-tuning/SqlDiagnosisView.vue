<template>
  <div class="awr-page diagnosis-page">
    <div class="awr-upload-hero diagnosis-hero">
      <div>
        <p class="awr-upload-eyebrow">SQL 성능 분석</p>
        <h1 class="awr-main-title">SQL 튜닝</h1>
        <p>부하 SQL을 자동 진단하거나 SQL과 실행계획을 직접 입력해 상세 튜닝을 수행합니다.</p>
      </div>
    </div>

    <nav class="tuning-tabs" aria-label="SQL 튜닝 방식">
      <RouterLink class="tuning-tab active" to="/sql-tuning">자동 진단</RouterLink>
      <RouterLink class="tuning-tab" to="/sql-tuning/manual">직접 SQL 분석</RouterLink>
    </nav>

    <div v-if="errorMessage" class="message-box error-box">
      <strong>요청을 처리하지 못했습니다.</strong>
      <span>{{ errorMessage }}</span>
    </div>

    <section class="awr-panel target-panel">
      <div class="awr-panel-header">
        <div>
          <h2 class="awr-panel-title">진단 대상 SQL</h2>
          <p class="panel-description">DB 연결을 선택하면 공유 메모리에서 업무 SQL을 조회합니다.</p>
        </div>
        <button class="awr-btn compact primary" type="button" :disabled="!connectionId || loadingTopSql" @click="loadTopSql">
          {{ loadingTopSql ? '조회 중...' : '부하 SQL 조회' }}
        </button>
      </div>

      <div class="diagnosis-controls">
        <label class="awr-field">
          DB 연결
          <select v-model.number="connectionId" class="awr-input" @change="handleConnectionChange">
            <option :value="null">DB 연결을 선택하세요</option>
            <option v-for="connection in connections" :key="connection.id" :value="connection.id">
              {{ connection.name }} · {{ connection.username }}
            </option>
          </select>
        </label>
        <label class="awr-field">
          조회 건수
          <select v-model.number="limit" class="awr-input" :disabled="loadingTopSql || !connectionId" @change="loadTopSql">
            <option :value="20">20건</option>
            <option :value="50">50건</option>
            <option :value="100">100건</option>
          </select>
        </label>
      </div>

      <div v-if="!connections.length && !errorMessage" class="empty-state">
        <strong>등록된 DB 연결이 없습니다.</strong>
        <span>시스템 관리에서 대상 DB를 먼저 등록한 뒤 다시 진입하세요.</span>
      </div>

      <div v-else-if="!connectionId" class="empty-state">
        <strong>진단할 DB 연결을 선택하세요.</strong>
        <span>선택한 DB의 Top SQL과 실행계획, 테이블 통계 및 인덱스를 조회합니다.</span>
      </div>

      <template v-else>
        <div class="sql-toolbar">
          <label class="search-field">
            <span class="sr-only">SQL 검색</span>
            <input v-model.trim="searchKeyword" class="awr-input" type="search" placeholder="SQL_ID, Schema, SQL 문장 검색" />
          </label>
          <label class="sort-field">
            <span>정렬</span>
            <select v-model="sortKey" class="awr-input">
              <option value="elapsed">평균 수행시간</option>
              <option value="buffer">Buffer Gets</option>
              <option value="disk">Disk Reads</option>
              <option value="rows">처리 행 수</option>
            </select>
          </label>
          <span v-if="topSqlLoaded" class="result-count">{{ filteredTopSqlRows.length }}건</span>
        </div>

        <div v-if="topSqlWarnings.length" class="message-box warning-box">
          <strong>조회 경고</strong>
          <ul><li v-for="warning in topSqlWarnings" :key="warning">{{ warning }}</li></ul>
        </div>

        <div v-if="loadingTopSql" class="empty-state loading-state">
          <strong>부하 SQL을 조회하고 있습니다.</strong>
          <span>Oracle 공유 메모리에서 업무 SQL과 성능 지표를 수집하는 중입니다.</span>
        </div>

        <div v-else-if="filteredTopSqlRows.length" class="awr-table-wrap top-sql-table-wrap">
          <table class="awr-table compact top-sql-table">
            <thead>
              <tr>
                <th>SQL_ID</th><th>Schema</th><th>평균 수행시간</th><th>Buffer Gets</th><th>Disk Reads</th><th>Rows</th><th>SQL</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in filteredTopSqlRows" :key="`${row.sqlId}-${row.childNumber}`" :class="{ selected: selectedSqlId === row.sqlId }" @dblclick="selectSql(row)">
                <td><button class="sql-id-button" type="button" :disabled="loadingDiagnosis" @click="selectSql(row)">{{ row.sqlId }}</button></td>
                <td>{{ row.parsingSchemaName || '-' }}</td>
                <td>{{ number(row.averageElapsedTimeSec) }}초</td>
                <td>{{ number(row.bufferGets) }}</td>
                <td>{{ number(row.diskReads) }}</td>
                <td>{{ number(row.rowsProcessed) }}</td>
                <td><code class="sql-preview" :title="row.sqlText || ''">{{ row.sqlText || '-' }}</code></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="topSqlLoaded" class="empty-state">
          <strong>{{ searchKeyword ? '검색 조건에 맞는 SQL이 없습니다.' : '진단할 업무 SQL이 없습니다.' }}</strong>
          <span>{{ searchKeyword ? '검색어를 변경하거나 초기화하세요.' : '대상 DB에서 업무 SQL을 실행한 뒤 다시 조회하세요.' }}</span>
        </div>
        <div v-else class="empty-state">
          <strong>부하 SQL 조회를 시작하세요.</strong>
          <span>조회된 SQL_ID를 선택하면 자동 진단 결과가 아래에 표시됩니다.</span>
        </div>
      </template>
    </section>

    <section v-if="loadingDiagnosis || diagnosis" class="awr-panel diagnosis-result">
      <div v-if="loadingDiagnosis" class="empty-state loading-state">
        <strong>선택한 SQL을 진단하고 있습니다.</strong>
        <span>성능 지표, 실행계획, 테이블 통계와 인덱스를 결합하는 중입니다.</span>
      </div>
      <template v-else-if="diagnosis">
        <div class="diagnosis-header">
          <div>
            <p class="awr-upload-eyebrow">진단 결과 · {{ diagnosis.sqlId }}</p>
            <h2>{{ diagnosis.summary }}</h2>
            <p class="result-meta">Schema {{ diagnosis.metric.parsingSchemaName || '-' }} · Child {{ diagnosis.metric.childNumber ?? '-' }} · Plan {{ diagnosis.metric.planHashValue ?? '-' }}</p>
          </div>
          <div :class="['diagnosis-score', severityClass(diagnosis.severity)]">
            <strong>{{ diagnosis.score }}</strong><span>{{ diagnosis.severity }}</span>
          </div>
        </div>

        <div class="metric-grid">
          <div><span>총 수행시간</span><strong>{{ number(diagnosis.metric.totalElapsedTimeSec) }}초</strong></div>
          <div><span>평균 수행시간</span><strong>{{ number(diagnosis.metric.averageElapsedTimeSec) }}초</strong></div>
          <div><span>CPU 시간</span><strong>{{ number(diagnosis.metric.totalCpuTimeSec) }}초</strong></div>
          <div><span>실행 횟수</span><strong>{{ number(diagnosis.metric.executions) }}</strong></div>
          <div><span>Buffer Gets</span><strong>{{ number(diagnosis.metric.bufferGets) }}</strong></div>
          <div><span>Disk Reads</span><strong>{{ number(diagnosis.metric.diskReads) }}</strong></div>
          <div><span>처리 행 수</span><strong>{{ number(diagnosis.metric.rowsProcessed) }}</strong></div>
          <div><span>Plan Hash</span><strong>{{ number(diagnosis.metric.planHashValue) }}</strong></div>
        </div>

        <section class="result-section first-section">
          <div class="section-title-row">
            <div><h3>진단 항목</h3><p>{{ diagnosis.findings.length }}개의 성능 이슈와 권고사항입니다.</p></div>
          </div>
          <div class="finding-list">
            <article v-for="finding in diagnosis.findings" :key="finding.code" class="finding-card">
              <div class="finding-title">
                <span :class="['severity-badge', severityClass(finding.severity)]">{{ finding.severity }}</span>
                <strong>{{ finding.title }}</strong><code>{{ finding.code }}</code>
              </div>
              <p>{{ finding.description }}</p>
              <div class="recommendation-box">
                <strong>권고사항</strong>
                <ul><li v-for="recommendation in finding.recommendations" :key="recommendation">{{ recommendation }}</li></ul>
              </div>
              <details>
                <summary>판정 근거 보기</summary>
                <pre>{{ pretty(finding.evidence) }}</pre>
              </details>
            </article>
          </div>
        </section>

        <details class="result-disclosure" open>
          <summary>구조화 실행계획 <span>{{ diagnosis.executionPlan.nodes.length }}개 노드</span></summary>
          <div class="result-section disclosure-body">
            <div class="awr-table-wrap">
              <table class="awr-table compact plan-table">
                <thead><tr><th>ID</th><th>Operation</th><th>Object</th><th>Cost</th><th>예상 Rows</th><th>실제 Rows</th><th>Predicate</th></tr></thead>
                <tbody>
                  <tr v-for="node in diagnosis.executionPlan.nodes" :key="`${node.id}-${node.operation}-${node.objectName}`" :class="{ 'full-scan-row': node.operation === 'TABLE ACCESS' && node.options === 'FULL' }">
                    <td>{{ node.id ?? '-' }}</td>
                    <td><span class="operation-cell" :style="{ paddingLeft: `${(node.depth || 0) * 16}px` }">{{ operation(node.operation, node.options) }}</span></td>
                    <td>{{ objectName(node.objectOwner, node.objectName) }}</td>
                    <td>{{ number(node.cost) }}</td>
                    <td>{{ number(node.cardinality) }}</td>
                    <td>{{ number(node.actualRows ?? node.lastActualRows) }}</td>
                    <td><code class="predicate-cell">{{ node.accessPredicate || node.filterPredicate || '-' }}</code></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </details>

        <details class="result-disclosure" open>
          <summary>테이블 및 인덱스 <span>{{ diagnosis.tableMetadata.tables.length }}개 테이블</span></summary>
          <div class="result-section disclosure-body">
            <article v-for="table in diagnosis.tableMetadata.tables" :key="`${table.owner}.${table.tableName}`" class="table-card">
              <div class="table-summary">
                <strong>{{ table.owner }}.{{ table.tableName }}</strong>
                <span>Rows {{ number(table.numRows) }}</span><span>Blocks {{ number(table.blocks) }}</span><span>평균 Row {{ number(table.avgRowLength) }} bytes</span><span>통계 {{ date(table.lastAnalyzed) }}</span>
              </div>
              <div class="awr-table-wrap">
                <table class="awr-table compact index-table">
                  <thead><tr><th>인덱스</th><th>컬럼</th><th>유형</th><th>상태</th><th>가시성</th><th>고유성</th><th>현재 Plan</th></tr></thead>
                  <tbody>
                    <tr v-for="index in table.indexes" :key="index.indexName" :class="{ 'used-index-row': index.usedInCurrentPlan }">
                      <td>{{ index.indexName }}</td><td>{{ index.columns.join(', ') || '-' }}</td><td>{{ index.indexType || '-' }}</td><td>{{ index.status || '-' }}</td><td>{{ index.visibility || '-' }}</td><td>{{ index.uniqueness || '-' }}</td><td><span :class="['usage-badge', index.usedInCurrentPlan ? 'used' : 'unused']">{{ index.usedInCurrentPlan ? '사용' : '미사용' }}</span></td>
                    </tr>
                    <tr v-if="!table.indexes.length"><td colspan="7">조회된 인덱스가 없습니다.</td></tr>
                  </tbody>
                </table>
              </div>
            </article>
          </div>
        </details>

        <details class="result-disclosure">
          <summary>SQL 원문 및 수집 경고</summary>
          <div class="result-section disclosure-body">
            <pre class="sql-text">{{ diagnosis.metric.sqlText }}</pre>
            <div v-if="diagnosis.warnings.length" class="message-box warning-box result-warning">
              <strong>진단 참고사항</strong>
              <ul><li v-for="warning in diagnosis.warnings" :key="warning">{{ warning }}</li></ul>
            </div>
          </div>
        </details>

        <div class="manual-analysis-cta">
          <div><strong>AI 기반 상세 튜닝이 더 필요합니까?</strong><span>SQL, 실행계획, DDL을 직접 입력해 재작성안과 인덱스 후보를 분석할 수 있습니다.</span></div>
          <RouterLink class="awr-btn primary" to="/sql-tuning/manual">직접 SQL 분석으로 이동</RouterLink>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { getDetailedTopSql, getDiagnosisConnections, getSqlDiagnosis } from '@/api/sqlDiagnosis'
import type { TargetDbConnectionResponse } from '@/types/awr'
import type { DirectSqlMetricResponse, SqlDiagnosisResponse } from '@/types/sqlDiagnosis'

type SortKey = 'elapsed' | 'buffer' | 'disk' | 'rows'

const connections = ref<TargetDbConnectionResponse[]>([])
const connectionId = ref<number | null>(null)
const limit = ref<20 | 50 | 100>(20)
const topSqlRows = ref<DirectSqlMetricResponse[]>([])
const topSqlWarnings = ref<string[]>([])
const topSqlLoaded = ref(false)
const selectedSqlId = ref('')
const diagnosis = ref<SqlDiagnosisResponse | null>(null)
const loadingTopSql = ref(false)
const loadingDiagnosis = ref(false)
const errorMessage = ref('')
const searchKeyword = ref('')
const sortKey = ref<SortKey>('elapsed')

const filteredTopSqlRows = computed(() => {
  const keyword = searchKeyword.value.toLowerCase()
  const rows = topSqlRows.value.filter((row) => {
    if (!keyword) return true
    return [row.sqlId, row.parsingSchemaName, row.module, row.sqlText]
      .some((value) => value?.toLowerCase().includes(keyword))
  })
  return [...rows].sort((left, right) => metricValue(right, sortKey.value) - metricValue(left, sortKey.value))
})

onMounted(async () => {
  try {
    connections.value = await getDiagnosisConnections()
    if (connections.value.length === 1) {
      connectionId.value = connections.value[0].id
      await loadTopSql()
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'DB 연결 목록을 불러오지 못했습니다.'
  }
})

async function handleConnectionChange() {
  selectedSqlId.value = ''
  diagnosis.value = null
  topSqlRows.value = []
  topSqlWarnings.value = []
  topSqlLoaded.value = false
  searchKeyword.value = ''
  if (connectionId.value) await loadTopSql()
}

async function loadTopSql() {
  if (!connectionId.value || loadingTopSql.value) return
  loadingTopSql.value = true
  errorMessage.value = ''
  selectedSqlId.value = ''
  diagnosis.value = null
  try {
    const result = await getDetailedTopSql(connectionId.value, limit.value)
    topSqlRows.value = result.rows
    topSqlWarnings.value = result.warnings
    topSqlLoaded.value = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '부하 SQL을 조회하지 못했습니다.'
  } finally {
    loadingTopSql.value = false
  }
}

async function selectSql(row: DirectSqlMetricResponse) {
  if (!connectionId.value || loadingDiagnosis.value) return
  selectedSqlId.value = row.sqlId
  diagnosis.value = null
  loadingDiagnosis.value = true
  errorMessage.value = ''
  try {
    diagnosis.value = await getSqlDiagnosis(connectionId.value, row.sqlId, row.childNumber)
    requestAnimationFrame(() => document.querySelector('.diagnosis-result')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'SQL 진단을 수행하지 못했습니다.'
  } finally {
    loadingDiagnosis.value = false
  }
}

function metricValue(row: DirectSqlMetricResponse, key: SortKey) {
  if (key === 'buffer') return row.bufferGets ?? 0
  if (key === 'disk') return row.diskReads ?? 0
  if (key === 'rows') return row.rowsProcessed ?? 0
  return row.averageElapsedTimeSec ?? 0
}
function number(value?: number | null) {
  return value == null ? '-' : new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 3 }).format(value)
}
function date(value?: string | null) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
}
function severityClass(value?: string | null) { return `severity-${(value || 'LOW').toLowerCase()}` }
function operation(operationValue?: string | null, options?: string | null) { return [operationValue, options].filter(Boolean).join(' ') || '-' }
function objectName(owner?: string | null, name?: string | null) { return [owner, name].filter(Boolean).join('.') || '-' }
function pretty(value: Record<string, unknown>) { return JSON.stringify(value, null, 2) }
</script>

<style src="../awr/awr.css"></style>
<style scoped>
.diagnosis-page{display:grid;gap:18px}.tuning-tabs{display:flex;gap:8px;padding:6px;border:1px solid #dce5e1;border-radius:14px;background:#fff;width:max-content}.tuning-tab{padding:10px 18px;border-radius:10px;color:#49605a;font-weight:800;text-decoration:none}.tuning-tab.active{background:#078f4e;color:#fff}.panel-description{margin:5px 0 0;color:#64748b;font-size:13px}.diagnosis-controls{display:grid;grid-template-columns:minmax(260px,1fr) 180px;gap:14px;margin-bottom:14px}.sql-toolbar{display:grid;grid-template-columns:minmax(280px,1fr) 210px auto;align-items:end;gap:12px;margin:16px 0 12px}.sort-field{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:8px;color:#52635e;font-size:13px;font-weight:700}.result-count{padding:10px 0;color:#64748b;font-size:13px;font-weight:700}.message-box{display:grid;gap:5px;padding:13px 15px;border-radius:12px}.message-box span,.message-box li{font-size:13px}.message-box ul{margin:2px 0 0;padding-left:18px}.error-box{border:1px solid #fecaca;background:#fff1f2;color:#9f1239}.warning-box{border:1px solid #fde68a;background:#fffbeb;color:#854d0e}.empty-state{display:grid;place-items:center;gap:6px;min-height:130px;padding:24px;border:1px dashed #cbd8d3;border-radius:12px;background:#f9fbfa;text-align:center;color:#64748b}.empty-state strong{color:#263b35;font-size:15px}.empty-state span{font-size:13px}.loading-state{background:linear-gradient(90deg,#f8faf9,#eef7f2,#f8faf9);background-size:200% 100%;animation:loading 1.4s linear infinite}.top-sql-table tbody tr{cursor:pointer}.top-sql-table tbody tr.selected{background:#ecfdf3}.sql-id-button{border:0;background:none;color:#2563eb;font:inherit;font-weight:800;cursor:pointer}.sql-id-button:disabled{cursor:wait}.sql-preview{display:block;max-width:520px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.diagnosis-result{overflow:hidden;scroll-margin-top:20px}.diagnosis-header{display:flex;align-items:center;justify-content:space-between;gap:20px}.diagnosis-header h2{margin:4px 0 0;font-size:21px}.result-meta{margin:7px 0 0;color:#64748b;font-size:12px}.diagnosis-score{min-width:96px;padding:14px;border-radius:16px;text-align:center}.diagnosis-score strong{display:block;font-size:32px;line-height:1}.diagnosis-score span{font-size:12px;font-weight:900}.severity-high{background:#fee2e2;color:#b91c1c}.severity-medium{background:#fef3c7;color:#92400e}.severity-low{background:#dcfce7;color:#166534}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:20px 0}.metric-grid>div{padding:15px;border:1px solid #edf1ef;border-radius:11px;background:#f7f9fc}.metric-grid span{display:block;color:#64748b;font-size:12px}.metric-grid strong{display:block;margin-top:5px;font-size:17px}.result-section{margin-top:22px}.first-section{margin-top:10px}.section-title-row{display:flex;justify-content:space-between;align-items:end;margin-bottom:10px}.section-title-row h3{margin:0}.section-title-row p{margin:4px 0 0;color:#64748b;font-size:12px}.finding-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.finding-card{padding:17px;border:1px solid #e5eaf0;border-radius:13px;background:#fff}.finding-title{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.finding-title code{color:#64748b;font-size:11px}.severity-badge{padding:3px 8px;border-radius:999px;font-size:11px;font-weight:900}.finding-card>p{margin:11px 0;color:#374151}.recommendation-box{padding:11px 13px;border-radius:9px;background:#f5f8f7}.recommendation-box strong{font-size:12px;color:#315248}.recommendation-box ul{margin:7px 0 0;padding-left:18px}.finding-card details{margin-top:11px}.finding-card details summary,.result-disclosure>summary{cursor:pointer;font-weight:800}.finding-card details pre{max-height:220px;overflow:auto;padding:10px;border-radius:8px;background:#111827;color:#f8fafc}.result-disclosure{margin-top:18px;border:1px solid #dfe8e4;border-radius:12px;background:#fff;overflow:hidden}.result-disclosure>summary{display:flex;justify-content:space-between;padding:15px 17px;background:#f7faf8;list-style:none}.result-disclosure>summary::-webkit-details-marker{display:none}.result-disclosure>summary span{color:#64748b;font-size:12px}.disclosure-body{margin:0;padding:14px 16px 16px}.operation-cell{display:inline-block;font-weight:700}.predicate-cell{white-space:pre-wrap}.full-scan-row{background:#fff7ed}.table-card+.table-card{margin-top:16px}.table-summary{display:flex;gap:14px;flex-wrap:wrap;padding:12px;background:#f7f9fc;border-radius:10px 10px 0 0}.used-index-row{background:#f0fdf4}.usage-badge{display:inline-block;padding:3px 8px;border-radius:999px;font-size:11px;font-weight:800}.usage-badge.used{background:#dcfce7;color:#166534}.usage-badge.unused{background:#f1f5f9;color:#64748b}.sql-text{overflow:auto;padding:14px;border-radius:10px;background:#111827;color:#f8fafc;white-space:pre-wrap}.result-warning{margin-top:12px}.manual-analysis-cta{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:20px;padding:17px;border-radius:12px;background:#eef8f2}.manual-analysis-cta div{display:grid;gap:4px}.manual-analysis-cta span{color:#64748b;font-size:13px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@keyframes loading{to{background-position:-200% 0}}@media(max-width:1100px){.finding-list{grid-template-columns:1fr}}@media(max-width:900px){.diagnosis-controls,.sql-toolbar{grid-template-columns:1fr}.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.diagnosis-header,.manual-analysis-cta{align-items:flex-start;flex-direction:column}.tuning-tabs{width:100%}.tuning-tab{flex:1;text-align:center}}
</style>

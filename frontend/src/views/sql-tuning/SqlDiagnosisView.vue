<template>
  <div class="awr-page diagnosis-page">
    <div class="awr-upload-hero diagnosis-hero">
      <div>
        <p class="awr-upload-eyebrow">SQL 성능 분석</p>
        <h1 class="awr-main-title">SQL 자동 진단</h1>
        <p>부하 SQL을 선택하면 성능 지표, 실행계획, 테이블 통계와 인덱스를 근거로 문제를 자동 진단합니다.</p>
      </div>
    </div>

    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <section class="awr-panel">
      <div class="awr-panel-header">
        <h2 class="awr-panel-title">진단 대상 SQL</h2>
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
          <select v-model.number="limit" class="awr-input" :disabled="loadingTopSql" @change="loadTopSql">
            <option :value="20">20건</option>
            <option :value="50">50건</option>
            <option :value="100">100건</option>
          </select>
        </label>
      </div>

      <div v-if="topSqlWarnings.length" class="awr-empty compact">
        <ul><li v-for="warning in topSqlWarnings" :key="warning">{{ warning }}</li></ul>
      </div>

      <div v-if="topSqlRows.length" class="awr-table-wrap">
        <table class="awr-table compact">
          <thead>
            <tr>
              <th>SQL_ID</th><th>Schema</th><th>평균 수행시간</th><th>Buffer Gets</th><th>Disk Reads</th><th>Rows</th><th>SQL</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in topSqlRows" :key="`${row.sqlId}-${row.childNumber}`" :class="selectedSqlId === row.sqlId ? 'selected' : ''">
              <td><button class="sql-id-button" type="button" :disabled="loadingDiagnosis" @click="selectSql(row)">{{ row.sqlId }}</button></td>
              <td>{{ row.parsingSchemaName || '-' }}</td>
              <td>{{ number(row.averageElapsedTimeSec) }}초</td>
              <td>{{ number(row.bufferGets) }}</td>
              <td>{{ number(row.diskReads) }}</td>
              <td>{{ number(row.rowsProcessed) }}</td>
              <td><code class="sql-preview">{{ row.sqlText || '-' }}</code></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else-if="topSqlLoaded && !loadingTopSql" class="awr-empty compact">진단할 업무 SQL이 없습니다.</div>
    </section>

    <section v-if="loadingDiagnosis || diagnosis" class="awr-panel diagnosis-result">
      <div v-if="loadingDiagnosis" class="awr-empty">선택한 SQL을 진단하는 중입니다.</div>
      <template v-else-if="diagnosis">
        <div class="diagnosis-header">
          <div>
            <p class="awr-upload-eyebrow">진단 결과 · {{ diagnosis.sqlId }}</p>
            <h2>{{ diagnosis.summary }}</h2>
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

        <div class="finding-list">
          <article v-for="finding in diagnosis.findings" :key="finding.code" class="finding-card">
            <div class="finding-title">
              <span :class="['severity-badge', severityClass(finding.severity)]">{{ finding.severity }}</span>
              <strong>{{ finding.title }}</strong><code>{{ finding.code }}</code>
            </div>
            <p>{{ finding.description }}</p>
            <details>
              <summary>근거 보기</summary>
              <pre>{{ pretty(finding.evidence) }}</pre>
            </details>
            <ul><li v-for="recommendation in finding.recommendations" :key="recommendation">{{ recommendation }}</li></ul>
          </article>
        </div>

        <div class="result-section">
          <h3>구조화 실행계획</h3>
          <div class="awr-table-wrap">
            <table class="awr-table compact">
              <thead><tr><th>ID</th><th>Operation</th><th>Object</th><th>Cost</th><th>예상 Rows</th><th>실제 Rows</th><th>Predicate</th></tr></thead>
              <tbody>
                <tr v-for="node in diagnosis.executionPlan.nodes" :key="`${node.id}-${node.operation}-${node.objectName}`">
                  <td>{{ node.id ?? '-' }}</td>
                  <td><span :style="{ paddingLeft: `${(node.depth || 0) * 14}px` }">{{ operation(node.operation, node.options) }}</span></td>
                  <td>{{ objectName(node.objectOwner, node.objectName) }}</td>
                  <td>{{ number(node.cost) }}</td>
                  <td>{{ number(node.cardinality) }}</td>
                  <td>{{ number(node.actualRows) }}</td>
                  <td>{{ node.accessPredicate || node.filterPredicate || '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="result-section">
          <h3>테이블 및 인덱스</h3>
          <article v-for="table in diagnosis.tableMetadata.tables" :key="`${table.owner}.${table.tableName}`" class="table-card">
            <div class="table-summary">
              <strong>{{ table.owner }}.{{ table.tableName }}</strong>
              <span>Rows {{ number(table.numRows) }}</span><span>Blocks {{ number(table.blocks) }}</span><span>통계 {{ date(table.lastAnalyzed) }}</span>
            </div>
            <div class="awr-table-wrap">
              <table class="awr-table compact">
                <thead><tr><th>인덱스</th><th>컬럼</th><th>유형</th><th>상태</th><th>가시성</th><th>고유성</th><th>현재 Plan</th></tr></thead>
                <tbody>
                  <tr v-for="index in table.indexes" :key="index.indexName">
                    <td>{{ index.indexName }}</td><td>{{ index.columns.join(', ') || '-' }}</td><td>{{ index.indexType || '-' }}</td><td>{{ index.status || '-' }}</td><td>{{ index.visibility || '-' }}</td><td>{{ index.uniqueness || '-' }}</td><td>{{ index.usedInCurrentPlan ? '사용' : '미사용' }}</td>
                  </tr>
                  <tr v-if="!table.indexes.length"><td colspan="7">조회된 인덱스가 없습니다.</td></tr>
                </tbody>
              </table>
            </div>
          </article>
        </div>

        <div class="result-section">
          <h3>SQL 원문</h3>
          <pre class="sql-text">{{ diagnosis.metric.sqlText }}</pre>
        </div>

        <div v-if="diagnosis.warnings.length" class="awr-empty compact">
          <ul><li v-for="warning in diagnosis.warnings" :key="warning">{{ warning }}</li></ul>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getDetailedTopSql, getDiagnosisConnections, getSqlDiagnosis } from '@/api/sqlDiagnosis'
import type { TargetDbConnectionResponse } from '@/types/awr'
import type { DirectSqlMetricResponse, SqlDiagnosisResponse } from '@/types/sqlDiagnosis'

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

onMounted(async () => {
  try {
    connections.value = await getDiagnosisConnections()
    if (connections.value.length) {
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
  topSqlLoaded.value = false
  await loadTopSql()
}

async function loadTopSql() {
  if (!connectionId.value || loadingTopSql.value) return
  loadingTopSql.value = true
  errorMessage.value = ''
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
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'SQL 진단을 수행하지 못했습니다.'
  } finally {
    loadingDiagnosis.value = false
  }
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
.diagnosis-page{display:grid;gap:20px}.diagnosis-controls{display:grid;grid-template-columns:minmax(260px,1fr) 180px;gap:14px;margin-bottom:16px}.sql-id-button{border:0;background:none;color:#2563eb;font:inherit;font-weight:700;cursor:pointer}.sql-preview{display:block;max-width:520px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.diagnosis-result{overflow:hidden}.diagnosis-header{display:flex;align-items:center;justify-content:space-between;gap:20px}.diagnosis-header h2{margin:4px 0 0;font-size:20px}.diagnosis-score{min-width:92px;padding:12px;border-radius:14px;text-align:center}.diagnosis-score strong{display:block;font-size:30px;line-height:1}.diagnosis-score span{font-size:12px;font-weight:800}.severity-high{background:#fee2e2;color:#b91c1c}.severity-medium{background:#fef3c7;color:#92400e}.severity-low{background:#dcfce7;color:#166534}.metric-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:20px 0}.metric-grid>div{padding:14px;border-radius:10px;background:#f7f9fc}.metric-grid span{display:block;color:#64748b;font-size:12px}.metric-grid strong{display:block;margin-top:4px;font-size:16px}.finding-list{display:grid;gap:12px}.finding-card{padding:16px;border:1px solid #e5eaf0;border-radius:12px}.finding-title{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.finding-title code{color:#64748b;font-size:11px}.severity-badge{padding:3px 8px;border-radius:999px;font-size:11px;font-weight:800}.finding-card p{margin:10px 0}.finding-card ul{margin:10px 0 0;padding-left:20px}.finding-card details pre{max-height:220px;overflow:auto;padding:10px;border-radius:8px;background:#111827;color:#f8fafc}.result-section{margin-top:24px}.result-section h3{margin:0 0 10px}.table-card{margin-top:12px}.table-summary{display:flex;gap:14px;flex-wrap:wrap;padding:11px 12px;background:#f7f9fc;border-radius:10px 10px 0 0}.sql-text{overflow:auto;padding:14px;border-radius:10px;background:#111827;color:#f8fafc;white-space:pre-wrap}@media(max-width:900px){.diagnosis-controls{grid-template-columns:1fr}.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.diagnosis-header{align-items:flex-start}}
</style>

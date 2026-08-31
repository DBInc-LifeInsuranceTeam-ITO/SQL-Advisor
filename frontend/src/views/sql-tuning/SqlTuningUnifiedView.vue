<template>
  <div class="awr-page sql-workbench">
    <div class="awr-upload-hero">
      <div>
        <p class="awr-upload-eyebrow">SQL 성능 분석</p>
        <h1 class="awr-main-title">SQL 튜닝</h1>
        <p>DB에서 부하 SQL을 자동 진단하거나 SQL과 실행계획을 직접 입력해 상세 분석합니다.</p>
      </div>
    </div>

    <div class="mode-switch">
      <button :class="{ active: mode === 'AUTO' }" type="button" @click="mode = 'AUTO'">DB 자동 진단</button>
      <button :class="{ active: mode === 'MANUAL' }" type="button" @click="mode = 'MANUAL'">SQL 직접 분석</button>
    </div>

    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <div class="awr-split workbench-grid">
      <section class="awr-panel input-panel">
        <template v-if="mode === 'AUTO'">
          <div class="awr-panel-header">
            <h2 class="awr-panel-title">분석 대상 DB</h2>
            <div class="awr-actions compact">
              <button class="awr-btn compact" type="button" @click="loadConnections">새로고침</button>
              <button class="awr-btn compact" type="button" @click="showConnectionForm = !showConnectionForm">
                {{ showConnectionForm ? '등록 닫기' : 'DB 연결 등록' }}
              </button>
            </div>
          </div>

          <div v-if="showConnectionForm" class="connection-form">
            <label class="awr-field">연결 이름<input v-model="connectionForm.name" class="awr-input" placeholder="예) 운영 DB" /></label>
            <label class="awr-field">JDBC URL<input v-model="connectionForm.jdbcUrl" class="awr-input" placeholder="jdbc:oracle:thin:@//host:1521/service" /></label>
            <label class="awr-field">사용자 계정<input v-model="connectionForm.username" class="awr-input" /></label>
            <label class="awr-field">비밀번호<input v-model="connectionForm.password" class="awr-input" type="password" /></label>
            <div class="awr-actions">
              <button class="awr-btn compact" type="button" :disabled="!canTestConnection || busyConnection" @click="testNewConnection">연결 확인</button>
              <button class="awr-btn compact primary" type="button" :disabled="!canSaveConnection || busyConnection" @click="saveConnection">연결 저장</button>
            </div>
          </div>

          <label class="awr-field">
            등록된 DB 연결
            <select v-model.number="connectionId" class="awr-input" @change="resetAutoResult">
              <option :value="null">DB 연결을 선택하세요</option>
              <option v-for="connection in connections" :key="connection.id" :value="connection.id">
                {{ connection.name }} · {{ connection.username }}
              </option>
            </select>
          </label>

          <div v-if="selectedConnection" class="selected-connection">
            <div class="connection-main">
              <div class="connection-identity">
                <strong>{{ selectedConnection.name }}</strong>
                <span>{{ selectedConnection.username }}</span>
              </div>
              <code>{{ selectedConnection.jdbcUrl }}</code>
            </div>
            <div class="connection-actions">
              <button class="awr-btn compact" type="button" :disabled="busyConnection" @click="testSavedConnection">연결 테스트</button>
              <button class="awr-btn compact danger" type="button" :disabled="busyConnection" @click="removeConnection">삭제</button>
            </div>
          </div>

          <div class="top-controls">
            <label class="awr-field compact">조회 건수
              <select v-model.number="limit" class="awr-input compact">
                <option :value="20">20건</option><option :value="50">50건</option><option :value="100">100건</option>
              </select>
            </label>
            <label class="awr-field compact">정렬
              <select v-model="sortBy" class="awr-input compact">
                <option value="TOTAL_ELAPSED_TIME">총 수행시간</option>
                <option value="BUFFER_GETS">Buffer Gets</option>
                <option value="DISK_READS">Disk Reads</option>
                <option value="EXECUTIONS">실행 횟수</option>
              </select>
            </label>
            <button class="awr-btn compact primary query-button" type="button" :disabled="!connectionId || loadingTopSql" @click="loadTopSql">
              {{ loadingTopSql ? '조회 중...' : '조회' }}
            </button>
          </div>

          <input v-model="searchText" class="awr-input" placeholder="SQL_ID, Schema, SQL 문장 검색" />

          <div v-if="filteredTopSql.length" class="awr-table-wrap top-sql-table">
            <table class="awr-table compact">
              <thead><tr><th>SQL_ID</th><th>Schema</th><th>평균시간</th><th>Buffer</th><th>Disk</th><th>Rows</th></tr></thead>
              <tbody>
                <tr v-for="row in filteredTopSql" :key="`${row.sqlId}-${row.instanceId}-${row.childNumber}`" :class="selectedSqlRow === row ? 'selected' : ''" @click="diagnose(row)">
                  <td><button class="sql-link" type="button">{{ row.sqlId }}</button></td>
                  <td>{{ row.parsingSchemaName || '-' }}</td><td>{{ number(row.averageElapsedTimeSec) }}초</td>
                  <td>{{ number(row.bufferGets) }}</td><td>{{ number(row.diskReads) }}</td><td>{{ number(row.rowsProcessed) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="awr-empty compact">
            {{ topSqlLoaded ? '조건에 맞는 업무 SQL이 없습니다.' : 'DB 연결을 선택하고 조회를 실행하세요.' }}
          </div>
        </template>

        <template v-else>
          <div class="awr-panel-header"><h2 class="awr-panel-title">SQL 직접 입력</h2></div>
          <div class="awr-form">
            <label class="awr-field">SQL 원문<textarea v-model="manual.sqlText" class="awr-textarea main-sql" placeholder="분석할 SQL을 입력하세요."></textarea></label>
            <label class="awr-field">질문<input v-model="manual.question" class="awr-input" placeholder="예) Full Scan 원인과 개선안을 알려줘" /></label>
            <label class="awr-field">실행계획<textarea v-model="manual.executionPlan" class="awr-textarea" placeholder="DBMS_XPLAN 결과"></textarea></label>
            <label class="awr-field">테이블 DDL<textarea v-model="manual.schemaDdl" class="awr-textarea" placeholder="CREATE TABLE ..."></textarea></label>
            <label class="awr-field">기존 인덱스<textarea v-model="manual.existingIndexes" class="awr-textarea" placeholder="CREATE INDEX ..."></textarea></label>
            <label class="awr-field">바인드 샘플<textarea v-model="manual.bindSamples" class="awr-textarea" placeholder=":status='A'"></textarea></label>
            <button class="awr-btn primary" type="button" :disabled="!manual.sqlText.trim() || loadingManual" @click="runManualAnalysis">
              {{ loadingManual ? '분석 중...' : 'AI 상세 분석' }}
            </button>
          </div>
        </template>
      </section>

      <section class="awr-panel result-panel">
        <template v-if="mode === 'AUTO'">
          <div class="awr-panel-header">
            <h2 class="awr-panel-title">자동 진단 결과</h2>
            <button
              v-if="diagnosis && selectedSqlRow"
              class="awr-btn compact primary"
              type="button"
              :disabled="loadingSelectedTuning"
              @click="runSelectedTuning"
            >
              {{ loadingSelectedTuning ? 'AI 튜닝 중...' : '선택 SQL AI 튜닝' }}
            </button>
          </div>
          <div v-if="loadingDiagnosis" class="awr-empty">선택한 SQL을 진단하는 중입니다.</div>
          <div v-else-if="!diagnosis" class="awr-empty">왼쪽 부하 SQL 목록에서 SQL_ID를 선택하세요.</div>
          <template v-else>
            <div class="diagnosis-overview">
              <div class="diagnosis-copy">
                <p class="diagnosis-sql-id">{{ diagnosis.sqlId }}</p>
                <h3>{{ diagnosis.summary }}</h3>
              </div>
              <div :class="['score', severityClass(diagnosis.severity)]"><strong>{{ diagnosis.score }}</strong><span>{{ diagnosis.severity }}</span></div>
            </div>

            <pre class="sql-box">{{ diagnosis.metric.sqlText }}</pre>

            <div class="metric-grid">
              <div><span>평균 수행시간</span><strong>{{ number(diagnosis.metric.averageElapsedTimeSec) }}초</strong></div>
              <div><span>CPU</span><strong>{{ number(diagnosis.metric.totalCpuTimeSec) }}초</strong></div>
              <div><span>Buffer Gets</span><strong>{{ number(diagnosis.metric.bufferGets) }}</strong></div>
              <div><span>Disk Reads</span><strong>{{ number(diagnosis.metric.diskReads) }}</strong></div>
              <div><span>Rows</span><strong>{{ number(diagnosis.metric.rowsProcessed) }}</strong></div>
              <div><span>Plan Hash</span><strong>{{ number(diagnosis.metric.planHashValue) }}</strong></div>
            </div>

            <details class="risk-policy">
              <summary>위험도 산정 기준</summary>
              <p>AI 판단이 아니라 서버의 고정 규칙 점수를 합산하며, 최대 점수는 100점입니다.</p>
              <div class="risk-levels">
                <span><strong>HIGH</strong> 70점 이상</span>
                <span><strong>MEDIUM</strong> 40~69점</span>
                <span><strong>LOW</strong> 39점 이하</span>
              </div>
              <ul>
                <li>TABLE ACCESS FULL 발견: +40점</li>
                <li>Buffer Gets 10,000 이상: +20점</li>
                <li>Disk Reads 1,000 이상: +20점</li>
                <li>처리 행 1,000,000건 이상: +20점</li>
                <li>통계정보 미수집 또는 30일 초과: +15점</li>
                <li>Full Scan 대상에 사용 가능한 인덱스 없음: +15점</li>
                <li>예상 행과 처리 행 차이 10배 이상: +15점</li>
              </ul>
            </details>

            <div class="finding-list">
              <article v-for="finding in diagnosis.findings" :key="finding.code" class="finding-card">
                <div><span :class="['badge', severityClass(finding.severity)]">{{ finding.severity }}</span><strong>{{ finding.title }}</strong><code>{{ finding.code }}</code></div>
                <p>{{ finding.description }}</p>
                <ul><li v-for="item in finding.recommendations" :key="item">{{ item }}</li></ul>
                <details><summary>판정 근거</summary><pre>{{ pretty(finding.evidence) }}</pre></details>
              </article>
            </div>
            <details open class="result-block"><summary>실행계획</summary>
              <div class="awr-table-wrap"><table class="awr-table compact"><thead><tr><th>ID</th><th>Operation</th><th>Object</th><th>Cost</th><th>예상 Rows</th></tr></thead><tbody>
                <tr v-for="node in diagnosis.executionPlan.nodes" :key="`${node.id}-${node.operation}`" :class="node.operation === 'TABLE ACCESS' && node.options === 'FULL' ? 'danger-row' : ''">
                  <td>{{ node.id }}</td><td :style="{ paddingLeft: `${(node.depth || 0) * 14 + 10}px` }">{{ [node.operation, node.options].filter(Boolean).join(' ') }}</td>
                  <td>{{ [node.objectOwner, node.objectName].filter(Boolean).join('.') || '-' }}</td><td>{{ number(node.cost) }}</td><td>{{ number(node.cardinality) }}</td>
                </tr>
              </tbody></table></div>
            </details>
            <details open class="result-block"><summary>테이블 및 인덱스</summary>
              <article v-for="table in diagnosis.tableMetadata.tables" :key="`${table.owner}.${table.tableName}`" class="table-card">
                <h4>{{ table.owner }}.{{ table.tableName }} <small>Rows {{ number(table.numRows) }} · 통계 {{ date(table.lastAnalyzed) }}</small></h4>
                <div class="awr-table-wrap"><table class="awr-table compact"><thead><tr><th>인덱스</th><th>컬럼</th><th>상태</th><th>가시성</th><th>현재 Plan</th></tr></thead><tbody>
                  <tr v-for="index in table.indexes" :key="index.indexName"><td>{{ index.indexName }}</td><td>{{ index.columns.join(', ') }}</td><td>{{ index.status }}</td><td>{{ index.visibility }}</td><td>{{ index.usedInCurrentPlan ? '사용' : '미사용' }}</td></tr>
                  <tr v-if="!table.indexes.length"><td colspan="5">인덱스 없음</td></tr>
                </tbody></table></div>
              </article>
            </details>
            <div v-if="diagnosis.warnings.length" class="awr-empty compact"><ul><li v-for="warning in diagnosis.warnings" :key="warning">{{ warning }}</li></ul></div>
          </template>
        </template>

        <template v-else>
          <div class="awr-panel-header"><h2 class="awr-panel-title">AI 분석 결과</h2></div>
          <div v-if="loadingManual" class="awr-empty">SQL을 분석하는 중입니다.</div>
          <div v-else-if="!manualResult" class="awr-empty">왼쪽에 SQL과 참고 정보를 입력하고 AI 상세 분석을 실행하세요.</div>
          <template v-else>
            <h3>{{ manualResult.summary }}</h3>
            <div class="result-block"><h4>증상</h4><ul><li v-for="item in manualResult.symptoms" :key="item">{{ item }}</li></ul></div>
            <div v-if="manualResult.rewrittenSql" class="result-block">
              <div class="rewrite-title">
                <div>
                  <h4>실행 가능한 튜닝 SQL 후보</h4>
                  <p>운영 반영 전 원본 SQL과 결과 정합성 및 실행계획을 반드시 비교하세요.</p>
                </div>
                <button class="awr-btn compact" type="button" @click="copyRewrittenSql">
                  {{ copyStatus || 'SQL 복사' }}
                </button>
              </div>
              <pre class="sql-box rewritten-sql">{{ manualResult.rewrittenSql }}</pre>
              <div v-if="manualResult.rewriteRisks?.length" class="rewrite-risks">
                <strong>적용 전 주의사항</strong>
                <ul><li v-for="item in manualResult.rewriteRisks" :key="item">{{ item }}</li></ul>
              </div>
            </div>
            <div v-else class="result-block rewrite-unavailable">
              <h4>튜닝 SQL 후보</h4>
              <p>현재 수집된 근거만으로는 결과 의미를 유지하는 실행 가능한 SQL을 안전하게 생성하지 못했습니다.</p>
              <ul v-if="manualResult.rewriteRisks?.length"><li v-for="item in manualResult.rewriteRisks" :key="item">{{ item }}</li></ul>
            </div>
            <div class="result-block"><h4>SQL 재작성 권고</h4><ul><li v-for="item in manualResult.rewriteRecommendations" :key="item">{{ item }}</li></ul></div>
            <div class="result-block"><h4>인덱스 권고</h4>
              <article v-for="item in manualResult.indexRecommendations" :key="`${item.tableName}-${item.columns.join(',')}`" class="finding-card">
                <strong>{{ item.tableName || '대상 테이블' }} ({{ item.columns.join(', ') }})</strong><p>{{ item.reason }}</p><pre v-if="item.ddlCandidate">{{ item.ddlCandidate }}</pre>
              </article>
            </div>
            <div class="result-block"><h4>검증 절차</h4><ol><li v-for="item in manualResult.validationSteps" :key="item">{{ item }}</li></ol></div>
          </template>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createTargetDbConnection, deleteTargetDbConnection, getTargetDbConnections,
  testSavedTargetDbConnection, testTargetDbConnection, tuneDirectSql, tuneSql
} from '@/api/sqlTuning'
import { getDetailedTopSql, getSqlDiagnosis } from '@/api/sqlDiagnosis'
import type { SqlTuningResponse, TargetDbConnectionResponse } from '@/types/awr'
import type { DirectSqlMetricResponse, SqlDiagnosisResponse } from '@/types/sqlDiagnosis'

type Mode = 'AUTO' | 'MANUAL'
const mode = ref<Mode>('AUTO')
const connections = ref<TargetDbConnectionResponse[]>([])
const connectionId = ref<number | null>(null)
const showConnectionForm = ref(false)
const busyConnection = ref(false)
const loadingTopSql = ref(false)
const loadingDiagnosis = ref(false)
const loadingManual = ref(false)
const loadingSelectedTuning = ref(false)
const copyStatus = ref('')
const topSqlLoaded = ref(false)
const topSqlRows = ref<DirectSqlMetricResponse[]>([])
const diagnosis = ref<SqlDiagnosisResponse | null>(null)
const manualResult = ref<SqlTuningResponse | null>(null)
const selectedSqlRow = ref<DirectSqlMetricResponse | null>(null)
const searchText = ref('')
const errorMessage = ref('')
const limit = ref<20 | 50 | 100>(20)
const sortBy = ref('TOTAL_ELAPSED_TIME')
const connectionForm = reactive({ name: '', dbType: 'ORACLE', jdbcUrl: '', username: '', password: '', visibility: 'PRIVATE', monitoringEnabled: false, monitoringIntervalSec: 60 })
const manual = reactive({ sqlText: '', question: '', executionPlan: '', schemaDdl: '', existingIndexes: '', bindSamples: '' })

const selectedConnection = computed(() => connections.value.find(item => item.id === connectionId.value) || null)
const canTestConnection = computed(() => Boolean(connectionForm.jdbcUrl && connectionForm.username && connectionForm.password))
const canSaveConnection = computed(() => Boolean(connectionForm.name && canTestConnection.value))
const filteredTopSql = computed(() => {
  const q = searchText.value.trim().toLowerCase()
  if (!q) return topSqlRows.value
  return topSqlRows.value.filter(row => [row.sqlId, row.parsingSchemaName, row.sqlText].some(value => value?.toLowerCase().includes(q)))
})

onMounted(loadConnections)

async function loadConnections() {
  try {
    connections.value = await getTargetDbConnections()
    if (!connectionId.value && connections.value.length) connectionId.value = connections.value[0].id
  } catch (error) { setError(error, 'DB 연결 목록을 불러오지 못했습니다.') }
}
async function testNewConnection() {
  busyConnection.value = true; errorMessage.value = ''
  try { const result = await testTargetDbConnection(connectionForm); alert(result.message) }
  catch (error) { setError(error, 'DB 연결 확인에 실패했습니다.') }
  finally { busyConnection.value = false }
}
async function saveConnection() {
  busyConnection.value = true; errorMessage.value = ''
  try {
    const saved = await createTargetDbConnection(connectionForm)
    await loadConnections(); connectionId.value = saved.id; showConnectionForm.value = false
    Object.assign(connectionForm, { name: '', jdbcUrl: '', username: '', password: '' })
  } catch (error) { setError(error, 'DB 연결 저장에 실패했습니다.') }
  finally { busyConnection.value = false }
}
async function testSavedConnection() {
  if (!connectionId.value) return
  busyConnection.value = true
  try { const result = await testSavedTargetDbConnection(connectionId.value); alert(result.message) }
  catch (error) { setError(error, '저장된 DB 연결 확인에 실패했습니다.') }
  finally { busyConnection.value = false }
}
async function removeConnection() {
  if (!connectionId.value || !confirm('선택한 DB 연결을 삭제할까요?')) return
  busyConnection.value = true
  try { await deleteTargetDbConnection(connectionId.value); connectionId.value = null; await loadConnections(); resetAutoResult() }
  catch (error) { setError(error, 'DB 연결 삭제에 실패했습니다.') }
  finally { busyConnection.value = false }
}
function resetAutoResult() {
  topSqlRows.value = []
  topSqlLoaded.value = false
  diagnosis.value = null
  selectedSqlRow.value = null
}
async function loadTopSql() {
  if (!connectionId.value) return
  loadingTopSql.value = true; errorMessage.value = ''; diagnosis.value = null; selectedSqlRow.value = null
  try { const result = await getDetailedTopSql(connectionId.value, limit.value, sortBy.value); topSqlRows.value = result.rows; topSqlLoaded.value = true }
  catch (error) { setError(error, '부하 SQL 조회에 실패했습니다.') }
  finally { loadingTopSql.value = false }
}
async function diagnose(row: DirectSqlMetricResponse) {
  if (!connectionId.value) return
  selectedSqlRow.value = row; loadingDiagnosis.value = true; diagnosis.value = null; errorMessage.value = ''
  try { diagnosis.value = await getSqlDiagnosis(connectionId.value, row.sqlId, row.childNumber, row.instanceId) }
  catch (error) { setError(error, 'SQL 자동 진단에 실패했습니다.') }
  finally { loadingDiagnosis.value = false }
}
async function runSelectedTuning() {
  const row = selectedSqlRow.value
  if (!connectionId.value || !row || loadingSelectedTuning.value) return
  loadingSelectedTuning.value = true; manualResult.value = null; errorMessage.value = ''
  try {
    const result = await tuneDirectSql({
      connectionId: connectionId.value,
      sqlId: row.sqlId,
      instanceId: row.instanceId,
      childNumber: row.childNumber,
      sqlText: row.sqlText || undefined
    })
    const input = result.input
    Object.assign(manual, {
      sqlText: input?.sqlText || result.metric?.sqlText || row.sqlText || '',
      question: input?.question || '',
      executionPlan: input?.executionPlan || '',
      schemaDdl: input?.schemaDdl || '',
      existingIndexes: input?.existingIndexes || '',
      bindSamples: input?.bindSamples || ''
    })
    manualResult.value = result
    mode.value = 'MANUAL'
  } catch (error) { setError(error, '선택한 SQL의 AI 튜닝에 실패했습니다.') }
  finally { loadingSelectedTuning.value = false }
}
async function runManualAnalysis() {
  loadingManual.value = true; manualResult.value = null; errorMessage.value = ''
  try { manualResult.value = await tuneSql({ ...manual }) }
  catch (error) { setError(error, 'SQL 직접 분석에 실패했습니다.') }
  finally { loadingManual.value = false }
}
async function copyRewrittenSql() {
  if (!manualResult.value?.rewrittenSql) return
  try {
    await navigator.clipboard.writeText(manualResult.value.rewrittenSql)
    copyStatus.value = '복사됨'
  } catch {
    copyStatus.value = '복사 실패'
  }
  window.setTimeout(() => { copyStatus.value = '' }, 1500)
}
function setError(error: unknown, fallback: string) { errorMessage.value = error instanceof Error ? error.message : fallback }
function number(value?: number | null) { return value == null ? '-' : new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 3 }).format(value) }
function date(value?: string | null) { return value ? new Intl.DateTimeFormat('ko-KR').format(new Date(value)) : '-' }
function pretty(value: Record<string, unknown>) { return JSON.stringify(value, null, 2) }
function severityClass(value?: string | null) { return `severity-${(value || 'LOW').toLowerCase()}` }
</script>

<style src="../awr/awr.css"></style>
<style scoped>
.sql-workbench{display:grid;gap:18px}.mode-switch{display:inline-flex;width:max-content;padding:5px;border:1px solid #d8e0e8;border-radius:13px;background:#fff}.mode-switch button{border:0;border-radius:9px;background:transparent;padding:12px 20px;font:inherit;font-weight:800;cursor:pointer}.mode-switch button.active{background:#078f4c;color:#fff}.workbench-grid{align-items:start;grid-template-columns:minmax(460px,.9fr) minmax(560px,1.1fr)}.input-panel,.result-panel{min-width:0}.connection-form{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:16px;padding:14px;border:1px solid #dce5ec;border-radius:12px;background:#f8fafc}.connection-form .awr-actions{grid-column:1/-1}.selected-connection{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:18px;margin:14px 0 20px;padding:16px 18px;border:1px solid #cfe4d7;border-left:5px solid #0aa15b;border-radius:12px;background:#f7fbf8}.connection-main{min-width:0;display:grid;gap:8px}.connection-identity{display:flex;align-items:center;gap:10px}.connection-identity strong{font-size:17px}.connection-identity span{padding:4px 10px;border-radius:999px;background:#e5f7eb;color:#087744;font-size:12px;font-weight:800}.selected-connection code{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#45586b}.connection-actions{display:flex;align-items:center;gap:8px}.top-controls{display:grid;grid-template-columns:165px 205px 92px;align-items:end;gap:12px;margin:18px 0 14px;padding-top:18px;border-top:1px solid #e2e8f0}.query-button{width:92px;min-width:92px;height:40px;padding:0 16px}.top-sql-table{max-height:420px}.top-sql-table tbody tr{cursor:pointer}.sql-link{border:0;background:transparent;color:#2563eb;font:inherit;font-weight:800;cursor:pointer}.main-sql{min-height:220px}.diagnosis-overview{display:grid;grid-template-columns:minmax(0,1fr) 90px;align-items:start;gap:24px;margin-bottom:16px}.diagnosis-copy{min-width:0;padding-top:2px}.diagnosis-sql-id{margin:0;color:#078f4c;font-weight:800}.diagnosis-copy h3{margin:8px 0 0;line-height:1.5;font-size:18px}.score{width:90px;min-height:96px;padding:14px 10px;border-radius:14px;text-align:center;display:flex;flex-direction:column;align-items:center;justify-content:center}.score strong{display:block;font-size:30px;line-height:1}.score span{margin-top:10px;font-size:11px;font-weight:900}.severity-high{background:#fee2e2;color:#b91c1c}.severity-medium{background:#fef3c7;color:#92400e}.severity-low{background:#dcfce7;color:#166534}.sql-box{margin:0 0 16px;padding:14px;border-radius:10px;background:#111827;color:#f8fafc;white-space:pre-wrap;line-height:1.5}.rewritten-sql{max-height:420px;overflow:auto}.rewrite-title{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:10px}.rewrite-title h4{margin:0}.rewrite-title p{margin:5px 0 0;color:#64748b;font-size:12px}.rewrite-risks,.rewrite-unavailable{padding:12px 14px;border-radius:10px;background:#fff7ed;color:#9a3412}.rewrite-risks ul,.rewrite-unavailable ul{margin-bottom:0}.metric-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin:0 0 16px}.metric-grid>div{padding:14px;border-radius:10px;background:#f6f8fb}.metric-grid span{display:block;font-size:11px;color:#64748b}.metric-grid strong{display:block;margin-top:6px}.risk-policy{margin:0 0 16px;padding:13px 15px;border:1px solid #dce5ec;border-radius:11px;background:#fbfcfd}.risk-policy summary{cursor:pointer;font-weight:900}.risk-policy p{margin:12px 0 8px;color:#526274}.risk-policy ul{margin:10px 0 0;padding-left:20px}.risk-levels{display:flex;gap:8px;flex-wrap:wrap}.risk-levels span{padding:6px 9px;border-radius:8px;background:#eef3f7;font-size:12px}.finding-list{display:grid;gap:10px}.finding-card{padding:13px;border:1px solid #dce5ec;border-radius:11px}.finding-card>div:first-child{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.finding-card code{font-size:10px;color:#64748b}.badge{padding:3px 7px;border-radius:999px;font-size:10px;font-weight:900}.finding-card details pre{overflow:auto;max-height:180px;padding:10px;background:#111827;color:#fff;border-radius:8px}.result-block{margin-top:16px;padding-top:10px;border-top:1px solid #e2e8f0}.result-block>summary{font-size:16px;font-weight:900;cursor:pointer;margin-bottom:10px}.danger-row{background:#fff1f2}.table-card h4{display:flex;justify-content:space-between}.table-card small{font-weight:400;color:#64748b}@media(max-width:1200px){.workbench-grid{grid-template-columns:1fr}.metric-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:720px){.connection-form{grid-template-columns:1fr}.selected-connection{grid-template-columns:1fr}.connection-actions{justify-content:flex-start}.top-controls{grid-template-columns:1fr}.query-button{width:100%}.diagnosis-overview{grid-template-columns:1fr}.score{width:100%;min-height:74px}.metric-grid{grid-template-columns:1fr}.rewrite-title{flex-direction:column}}
</style>

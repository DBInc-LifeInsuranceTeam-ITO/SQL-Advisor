<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>SQL Tuning</h1>
        <p>Connect to Oracle directly, collect SQL context, then tune SQL and recommend index candidates.</p>
      </div>
    </div>

    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <div class="sql-tuning-mode-switch">
      <button :class="{ active: tuningMode === 'DIRECT' }" type="button" @click="tuningMode = 'DIRECT'">
        Direct DB
      </button>
      <button :class="{ active: tuningMode === 'MANUAL' }" type="button" @click="tuningMode = 'MANUAL'">
        Manual Input
      </button>
    </div>

    <div class="awr-split">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">{{ tuningMode === 'DIRECT' ? 'Direct DB Tuning' : 'Manual Input' }}</h2>
          <button
            v-if="tuningMode === 'DIRECT'"
            class="awr-btn primary"
            type="button"
            :disabled="!canDirectTune || isTuning"
            @click="runDirectTuning"
          >
            {{ isTuning ? 'Tuning...' : 'Tune Direct DB' }}
          </button>
          <button
            v-else
            class="awr-btn primary"
            type="button"
            :disabled="!canManualTune || isTuning"
            @click="runManualTuning"
          >
            {{ isTuning ? 'Tuning...' : 'Tune SQL' }}
          </button>
        </div>

        <div v-if="tuningMode === 'DIRECT'" class="awr-form">
          <div class="sql-tuning-direct-box">
            <div class="awr-panel-header compact">
              <div>
                <h3 class="sql-tuning-section-title">Target DB</h3>
                <p class="sql-tuning-help">Use a saved Oracle connection, or add one here.</p>
              </div>
              <button class="awr-btn compact" type="button" :disabled="isLoadingConnections" @click="loadConnections">
                Refresh
              </button>
            </div>

            <div class="awr-form-grid">
              <label class="awr-field">
                Saved Connection
                <select v-model="selectedConnectionId">
                  <option :value="null" disabled>Select connection</option>
                  <option v-for="connection in connections" :key="connection.id" :value="connection.id">
                    {{ connection.name }} / {{ connection.username }}
                  </option>
                </select>
              </label>

              <label class="awr-field">
                SQL_ID
                <input
                  v-model="directSqlId"
                  placeholder="ex. 8s2x1c9w7m0ab"
                  @keydown.enter.prevent="runDirectTuning"
                />
              </label>
            </div>

            <div class="sql-tuning-action-row">
              <button
                class="awr-btn"
                type="button"
                :disabled="!selectedConnectionId || isTestingConnection"
                @click="testSavedConnection"
              >
                {{ isTestingConnection ? 'Testing...' : 'Test Saved' }}
              </button>
              <button
                class="awr-btn"
                type="button"
                :disabled="!selectedConnectionId || isLoadingSqlList"
                @click="loadDirectSqlList"
              >
                {{ isLoadingSqlList ? 'Loading...' : 'Load SQL' }}
              </button>
              <button
                class="awr-btn"
                type="button"
                :disabled="!canDirectTune || isFetchingContext"
                @click="fetchDirectContext"
              >
                {{ isFetchingContext ? 'Fetching...' : 'Fetch Context' }}
              </button>
              <button
                class="awr-btn danger"
                type="button"
                :disabled="!selectedConnectionId || isDeletingConnection"
                @click="deleteSelectedConnection"
              >
                Delete Saved
              </button>
              <button class="awr-btn" type="button" @click="showConnectionForm = !showConnectionForm">
                {{ showConnectionForm ? 'Hide New Connection' : 'New Connection' }}
              </button>
            </div>

            <div v-if="connectionTestResult" :class="['sql-tuning-test-result', connectionTestResult.success ? 'ok' : 'warn']">
              <strong>{{ connectionTestResult.success ? 'Connection OK' : 'Connection Failed' }}</strong>
              <span>{{ connectionTestResult.message }}</span>
              <small v-if="connectionTestResult.capabilities.length">
                Capabilities: {{ connectionTestResult.capabilities.join(', ') }}
              </small>
              <small v-if="connectionTestResult.warnings.length">
                Warnings: {{ connectionTestResult.warnings.join(' / ') }}
              </small>
            </div>

            <div v-if="directSqlList.length" class="sql-tuning-sql-list awr-table-wrap">
              <table class="awr-table">
                <thead>
                  <tr>
                    <th>SQL_ID</th>
                    <th>Elapsed</th>
                    <th>Buffer Gets</th>
                    <th>Disk Reads</th>
                    <th>Executions</th>
                    <th>SQL Text</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="item in directSqlList"
                    :key="item.sqlId"
                    :class="{ 'sql-tuning-selected-row': item.sqlId === directSqlId }"
                  >
                    <td>
                      <button class="awr-link" type="button" @click="selectDirectSql(item)">
                        {{ item.sqlId }}
                      </button>
                    </td>
                    <td>{{ formatNumber(item.elapsedTimeSec) }}</td>
                    <td>{{ formatNumber(item.bufferGets) }}</td>
                    <td>{{ formatNumber(item.diskReads) }}</td>
                    <td>{{ formatNumber(item.executions) }}</td>
                    <td class="sql-tuning-sql-text">{{ item.sqlText || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div v-else-if="directSqlListMessage" class="awr-empty compact">{{ directSqlListMessage }}</div>

            <div v-if="showConnectionForm" class="sql-tuning-connection-form">
              <div class="awr-form-grid">
                <label class="awr-field">
                  Name
                  <input v-model="connectionForm.name" placeholder="ex. PROD readonly" />
                </label>
                <label class="awr-field">
                  JDBC URL
                  <input v-model="connectionForm.jdbcUrl" placeholder="jdbc:oracle:thin:@//host:1521/service" />
                </label>
                <label class="awr-field">
                  Username
                  <input v-model="connectionForm.username" placeholder="ex. SQLADVISOR_RO" />
                </label>
                <label class="awr-field">
                  Password
                  <input v-model="connectionForm.password" type="password" autocomplete="new-password" />
                </label>
                <label class="awr-field">
                  Visibility
                  <select v-model="connectionForm.visibility">
                    <option value="PRIVATE">Private</option>
                    <option value="SHARED">Shared</option>
                  </select>
                </label>
              </div>
              <div class="sql-tuning-action-row">
                <button class="awr-btn" type="button" :disabled="isTestingConnection" @click="testNewConnection">
                  {{ isTestingConnection ? 'Testing...' : 'Test New' }}
                </button>
                <button class="awr-btn success" type="button" :disabled="isSavingConnection" @click="saveConnection">
                  {{ isSavingConnection ? 'Saving...' : 'Save Connection' }}
                </button>
              </div>
            </div>
          </div>

          <div v-if="directContext" class="sql-tuning-context-box">
            <div class="awr-panel-header compact">
              <div>
                <h3 class="sql-tuning-section-title">Collected Context</h3>
                <p class="sql-tuning-help">
                  {{ directContext.connectionName }} / {{ directContext.metric.sqlId }} / {{ formatDate(directContext.collectedAt) }}
                </p>
              </div>
              <span class="awr-badge">DB context</span>
            </div>
            <label class="awr-field">
              SQL Text
              <textarea class="awr-textarea sql-tuning-main-input" :value="sqlText" readonly></textarea>
            </label>
            <div class="awr-form-grid">
              <label class="awr-field">
                Execution Plan
                <textarea class="awr-textarea" :value="executionPlan" readonly></textarea>
              </label>
              <label class="awr-field">
                Schema DDL and Table Stats
                <textarea class="awr-textarea" :value="schemaDdl" readonly></textarea>
              </label>
              <label class="awr-field">
                Existing Indexes
                <textarea class="awr-textarea" :value="existingIndexes" readonly></textarea>
              </label>
              <label class="awr-field">
                Bind Samples
                <textarea class="awr-textarea" :value="bindSamples" readonly></textarea>
              </label>
            </div>
            <div v-if="directContext.warnings.length" class="sql-tuning-warning-list">
              <strong>Warnings</strong>
              <ul>
                <li v-for="warning in directContext.warnings" :key="warning">{{ warning }}</li>
              </ul>
            </div>
          </div>

          <div v-else class="awr-empty compact">
            <span v-if="directSqlId">
              SQL_ID {{ directSqlId }} selected. Fetch Context previews the data used for tuning; Tune Direct DB can run it in one step.
            </span>
            <span v-else>
              Select a saved connection, then click Load SQL or enter SQL_ID.
            </span>
          </div>
        </div>

        <div v-else class="awr-form">
          <label class="awr-field">
            SQL Text
            <textarea
              v-model="sqlText"
              class="awr-textarea sql-tuning-main-input"
              placeholder="ex. SELECT * FROM orders o WHERE o.customer_id = :customer_id ORDER BY o.created_at DESC"
              @keydown.ctrl.enter.prevent="runManualTuning"
              @keydown.meta.enter.prevent="runManualTuning"
            ></textarea>
          </label>

          <div class="awr-form-grid">
            <label class="awr-field">
              Execution Plan
              <textarea v-model="executionPlan" class="awr-textarea" placeholder="ex. DBMS_XPLAN.DISPLAY_CURSOR result"></textarea>
            </label>
            <label class="awr-field">
              Schema DDL
              <textarea v-model="schemaDdl" class="awr-textarea" placeholder="ex. CREATE TABLE orders (...)"></textarea>
            </label>
            <label class="awr-field">
              Existing Indexes
              <textarea v-model="existingIndexes" class="awr-textarea" placeholder="ex. CREATE INDEX idx_orders_status ON orders(status);"></textarea>
            </label>
            <label class="awr-field">
              Bind Samples
              <textarea v-model="bindSamples" class="awr-textarea" placeholder="ex. :customer_id = 100284"></textarea>
            </label>
          </div>
        </div>
      </section>

      <section class="awr-panel awr-side-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">{{ selectedResult ? `SQL Tuning - ${selectedResult.sqlId}` : 'Tuning Result' }}</h2>
            <p v-if="selectedResult" class="awr-muted" style="margin: 0.25rem 0 0;">confidence {{ selectedResult.confidence }}</p>
          </div>
          <span v-if="selectedResult" class="awr-badge">{{ selectedResult.model }}</span>
        </div>

        <template v-if="selectedResult">
          <div class="sql-tuning-summary-strip">
            <div>
              <span>Confidence</span>
              <strong>{{ selectedResult.confidence }}</strong>
            </div>
            <div>
              <span>Index Candidates</span>
              <strong>{{ selectedResult.indexRecommendations.length }}</strong>
            </div>
            <div>
              <span>Missing Inputs</span>
              <strong>{{ selectedResult.missingInputs.length }}</strong>
            </div>
          </div>
          <p class="sql-tuning-summary-text">{{ selectedResult.summary }}</p>

          <div class="awr-finding-grid compact" style="margin-top: 1rem;">
            <div>
              <strong>Symptoms</strong>
              <ul>
                <li v-for="item in selectedResult.symptoms" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <strong>Rewrite Checks</strong>
              <ul>
                <li v-for="item in selectedResult.rewriteRecommendations" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <strong>Validation</strong>
              <ul>
                <li v-for="item in selectedResult.validationSteps" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <strong>Missing Inputs</strong>
              <ul>
                <li v-for="item in selectedResult.missingInputs" :key="item">{{ item }}</li>
              </ul>
            </div>
          </div>

          <div class="awr-stack" style="margin-top: 1rem;">
            <article v-for="item in selectedResult.indexRecommendations" :key="`${item.tableName}-${item.columns.join('-')}`" class="awr-finding">
              <h3>{{ item.tableName || 'Index candidate' }}</h3>
              <p class="awr-muted">{{ item.reason }}</p>
              <p><strong>Columns:</strong> {{ formatColumns(item.columns) }}</p>
              <div v-if="item.ddlCandidate" class="sql-tuning-ddl-header">
                <strong>DDL Candidate</strong>
                <button class="awr-btn compact" type="button" @click="copyDdl(item.ddlCandidate || '')">
                  {{ copiedDdl === item.ddlCandidate ? 'Copied' : 'Copy' }}
                </button>
              </div>
              <pre v-if="item.ddlCandidate" class="awr-code">{{ item.ddlCandidate }}</pre>
              <p><strong>Expected benefit:</strong> {{ item.expectedBenefit }}</p>
              <p class="awr-muted">{{ item.risk }}</p>
            </article>
            <div v-if="selectedResult.indexRecommendations.length === 0" class="awr-empty compact">
              No concrete index DDL candidate was generated.
            </div>
          </div>
        </template>
        <div v-else class="awr-empty compact">No tuning result selected.</div>

        <div v-if="history.length" class="awr-side-history">
          <div class="awr-panel-header compact">
            <h3 class="awr-panel-title">Tuning History</h3>
            <span class="awr-badge">{{ history.length }}</span>
          </div>
          <ul class="awr-history-list compact">
            <li v-for="item in history" :key="item.tuningId">
              <button
                :class="['awr-history-item', selectedResult?.tuningId === item.tuningId ? 'active' : '']"
                type="button"
                @click="selectResult(item)"
              >
                <span class="awr-history-question">{{ item.sqlId }} - {{ item.summary }}</span>
                <span class="awr-history-meta">{{ item.model }} - confidence {{ item.confidence }} - {{ formatDate(item.createdAt) }}</span>
              </button>
            </li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  collectDirectDbContext,
  createTargetDbConnection,
  deleteTargetDbConnection,
  getDirectTopSql,
  getSqlTuningHistory,
  getTargetDbConnections,
  testSavedTargetDbConnection,
  testTargetDbConnection,
  tuneDirectSql,
  tuneSql
} from '@/api/sqlTuning'
import type {
  DirectDbContextResponse,
  SqlMetricResponse,
  SqlTuningRequest,
  SqlTuningResponse,
  TargetDbConnectionResponse,
  TargetDbConnectionTestResponse
} from '@/types/awr'

type TuningMode = 'DIRECT' | 'MANUAL'

const tuningMode = ref<TuningMode>('DIRECT')
const sqlText = ref('')
const executionPlan = ref('')
const schemaDdl = ref('')
const existingIndexes = ref('')
const bindSamples = ref('')
const selectedResult = ref<SqlTuningResponse | null>(null)
const history = ref<SqlTuningResponse[]>([])
const isTuning = ref(false)
const errorMessage = ref('')
const copiedDdl = ref('')

const connections = ref<TargetDbConnectionResponse[]>([])
const selectedConnectionId = ref<number | null>(null)
const directSqlId = ref('')
const directSqlList = ref<SqlMetricResponse[]>([])
const directSqlListMessage = ref('')
const directContext = ref<DirectDbContextResponse | null>(null)
const connectionTestResult = ref<TargetDbConnectionTestResponse | null>(null)
const showConnectionForm = ref(false)
const isLoadingConnections = ref(false)
const isTestingConnection = ref(false)
const isSavingConnection = ref(false)
const isDeletingConnection = ref(false)
const isFetchingContext = ref(false)
const isLoadingSqlList = ref(false)

const connectionForm = reactive({
  name: '',
  dbType: 'ORACLE',
  jdbcUrl: 'jdbc:oracle:thin:@//localhost:1521/ORCLPDB1',
  username: 'SQLADVISOR_RO',
  password: '',
  visibility: 'PRIVATE'
})

const canManualTune = computed(() => sqlText.value.trim().length > 0)
const canDirectTune = computed(() => Boolean(selectedConnectionId.value && directSqlId.value.trim()))

onMounted(async () => {
  await Promise.all([loadConnections(), loadHistory()])
})

async function loadConnections() {
  isLoadingConnections.value = true
  try {
    connections.value = await getTargetDbConnections()
    if (!selectedConnectionId.value && connections.value.length) {
      selectedConnectionId.value = connections.value[0].id
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load DB connections.'
  } finally {
    isLoadingConnections.value = false
  }
}

async function loadHistory() {
  history.value = await getSqlTuningHistory()
}

async function testSavedConnection() {
  if (!selectedConnectionId.value || isTestingConnection.value) return
  await withConnectionTest(() => testSavedTargetDbConnection(selectedConnectionId.value as number))
}

async function testNewConnection() {
  if (isTestingConnection.value) return
  await withConnectionTest(() =>
    testTargetDbConnection({
      dbType: connectionForm.dbType,
      jdbcUrl: connectionForm.jdbcUrl,
      username: connectionForm.username,
      password: connectionForm.password
    })
  )
}

async function withConnectionTest(action: () => Promise<TargetDbConnectionTestResponse>) {
  errorMessage.value = ''
  isTestingConnection.value = true
  try {
    connectionTestResult.value = await action()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Connection test failed.'
  } finally {
    isTestingConnection.value = false
  }
}

async function saveConnection() {
  if (isSavingConnection.value) return
  errorMessage.value = ''
  isSavingConnection.value = true
  try {
    const saved = await createTargetDbConnection({
      name: connectionForm.name,
      dbType: connectionForm.dbType,
      jdbcUrl: connectionForm.jdbcUrl,
      username: connectionForm.username,
      password: connectionForm.password,
      visibility: connectionForm.visibility
    })
    await loadConnections()
    selectedConnectionId.value = saved.id
    showConnectionForm.value = false
    connectionForm.password = ''
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to save DB connection.'
  } finally {
    isSavingConnection.value = false
  }
}

async function deleteSelectedConnection() {
  if (!selectedConnectionId.value || isDeletingConnection.value) return
  const target = connections.value.find((item) => item.id === selectedConnectionId.value)
  if (!window.confirm(`Delete saved connection ${target?.name || selectedConnectionId.value}?`)) return
  errorMessage.value = ''
  isDeletingConnection.value = true
  try {
    await deleteTargetDbConnection(selectedConnectionId.value)
    selectedConnectionId.value = null
    directContext.value = null
    await loadConnections()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to delete DB connection.'
  } finally {
    isDeletingConnection.value = false
  }
}

async function loadDirectSqlList() {
  if (!selectedConnectionId.value || isLoadingSqlList.value) return
  errorMessage.value = ''
  directSqlListMessage.value = ''
  isLoadingSqlList.value = true
  try {
    directSqlList.value = await getDirectTopSql(selectedConnectionId.value)
    if (directSqlList.value.length) {
      selectDirectSql(directSqlList.value[0])
    } else {
      directSqlListMessage.value = 'No SQL rows were returned from the target DB.'
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Failed to load SQL from target DB.'
  } finally {
    isLoadingSqlList.value = false
  }
}

function selectDirectSql(item: SqlMetricResponse) {
  directSqlId.value = item.sqlId
  sqlText.value = item.sqlText || ''
  directContext.value = null
}

async function fetchDirectContext() {
  if (!canDirectTune.value || isFetchingContext.value) return
  errorMessage.value = ''
  isFetchingContext.value = true
  try {
    const context = await collectDirectDbContext({
      connectionId: selectedConnectionId.value as number,
      sqlId: directSqlId.value.trim()
    })
    directContext.value = context
    restoreInput(context.input, context.metric?.sqlText)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Direct DB context collection failed.'
  } finally {
    isFetchingContext.value = false
  }
}

async function runDirectTuning() {
  if (!canDirectTune.value || isTuning.value) return
  errorMessage.value = ''
  isTuning.value = true
  try {
    selectedResult.value = await tuneDirectSql({
      connectionId: selectedConnectionId.value as number,
      sqlId: directSqlId.value.trim()
    })
    restoreInput(selectedResult.value.input, selectedResult.value.metric?.sqlText)
    directContext.value = null
    history.value = await getSqlTuningHistory()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Direct DB tuning failed.'
  } finally {
    isTuning.value = false
  }
}

async function runManualTuning() {
  if (!canManualTune.value || isTuning.value) return
  errorMessage.value = ''
  isTuning.value = true
  try {
    selectedResult.value = await tuneSql({
      sqlText: sqlText.value,
      executionPlan: executionPlan.value,
      schemaDdl: schemaDdl.value,
      existingIndexes: existingIndexes.value,
      bindSamples: bindSamples.value
    })
    history.value = await getSqlTuningHistory()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'SQL tuning failed.'
  } finally {
    isTuning.value = false
  }
}

function selectResult(item: SqlTuningResponse) {
  selectedResult.value = item
  restoreInput(item.input, item.metric?.sqlText)
}

function restoreInput(input?: SqlTuningRequest | null, fallbackSqlText?: string | null) {
  sqlText.value = input?.sqlText || fallbackSqlText || ''
  executionPlan.value = input?.executionPlan || ''
  schemaDdl.value = input?.schemaDdl || ''
  existingIndexes.value = input?.existingIndexes || ''
  bindSamples.value = input?.bindSamples || ''
}

async function copyDdl(ddl: string) {
  if (!ddl) return
  try {
    await navigator.clipboard.writeText(ddl)
    copiedDdl.value = ddl
    window.setTimeout(() => {
      if (copiedDdl.value === ddl) copiedDdl.value = ''
    }, 1200)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'DDL copy failed.'
  }
}

function formatColumns(columns: string[]) {
  return columns.length ? columns.join(', ') : '-'
}

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) return '-'
  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: 2
  }).format(value)
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

<style src="../awr/awr.css"></style>

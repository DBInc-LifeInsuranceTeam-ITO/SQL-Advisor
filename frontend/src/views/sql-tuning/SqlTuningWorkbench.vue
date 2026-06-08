<template>
  <div class="awr-page sql-tuning-page">
    <div class="awr-page-header">
      <div>
        <h1>SQL 튜닝</h1>
        <p>SQL Text / 실행계획 / DDL / 인덱스 / Bind</p>
      </div>
    </div>

    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <div class="awr-split">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">Input</h2>
          <button class="awr-btn primary" type="button" :disabled="!canTune || isTuning" @click="runTuning">
            {{ isTuning ? 'Tuning...' : runButtonLabel }}
          </button>
        </div>

        <div class="sql-tuning-mode-switch">
          <button
            :class="['sql-tuning-mode-option', sourceMode === 'DIRECT' ? 'active' : '']"
            type="button"
            @click="setSourceMode('DIRECT')"
          >
            Direct DB
          </button>
          <button
            :class="['sql-tuning-mode-option', sourceMode === 'MANUAL' ? 'active' : '']"
            type="button"
            @click="setSourceMode('MANUAL')"
          >
            Manual Input
          </button>
        </div>

        <div class="awr-form">
          <div v-if="sourceMode === 'DIRECT'" class="sql-tuning-direct-box">
            <div class="awr-panel-header compact">
              <h3 class="awr-panel-title">Target DB</h3>
              <div class="awr-actions compact">
                <button class="awr-btn compact" type="button" :disabled="isLoadingConnections" @click="loadConnections()">
                  Refresh
                </button>
                <button class="awr-btn compact" type="button" @click="toggleConnectionForm">
                  {{ showConnectionForm ? 'Hide Form' : 'New Connection' }}
                </button>
              </div>
            </div>

            <div class="awr-form-grid">
              <label class="awr-field">
                Saved Connection
                <select v-model.number="selectedConnectionId" class="awr-input" @change="handleConnectionChange">
                  <option :value="null">Select connection</option>
                  <option v-for="connection in connections" :key="connection.id" :value="connection.id">
                    {{ connection.name }} · {{ connection.username }}
                  </option>
                </select>
              </label>
              <label v-if="!showConnectionForm" class="awr-field">
                SQL_ID
                <input
                  v-model="directSqlId"
                  class="awr-input sql-tuning-sql-id-input"
                  placeholder="예) 7p6k1x9s2m3ab"
                  @keydown.enter.prevent="fetchDirectContext"
                />
              </label>
            </div>

            <div v-if="selectedConnection" class="sql-tuning-selected-connection">
              <strong>{{ selectedConnection.name }}</strong>
              <span>{{ selectedConnection.username }} · {{ selectedConnection.jdbcUrl }}</span>
            </div>

            <div v-if="showConnectionForm" class="awr-form-grid">
              <label class="awr-field">
                Connection Name
                <input v-model="connectionForm.name" class="awr-input" placeholder="예) PROD readonly" />
              </label>
              <label class="awr-field">
                JDBC URL
                <input v-model="connectionForm.jdbcUrl" class="awr-input" placeholder="예) jdbc:oracle:thin:@//host:1521/service" />
              </label>
              <label class="awr-field">
                Username
                <input v-model="connectionForm.username" class="awr-input" placeholder="예) SQLADVISOR_RO" />
              </label>
              <label class="awr-field">
                Password
                <input v-model="connectionForm.password" class="awr-input" type="password" placeholder="Password" />
              </label>
            </div>

            <div class="awr-actions sql-tuning-action-bar">
              <div class="sql-tuning-action-left">
                <button v-if="showConnectionForm" class="awr-btn compact" type="button" :disabled="!canTestConnection || isTestingConnection" @click="testConnection">
                  {{ isTestingConnection ? 'Testing...' : 'Test Connection' }}
                </button>
                <button v-if="showConnectionForm" class="awr-btn compact" type="button" :disabled="!canSaveConnection || isSavingConnection" @click="saveConnection">
                  {{ isSavingConnection ? 'Saving...' : 'Save Connection' }}
                </button>
                <button class="awr-btn compact" type="button" :disabled="!selectedConnectionId || isTestingConnection" @click="testSelectedConnection">
                  {{ isTestingConnection ? 'Testing...' : 'Test Connection' }}
                </button>
                <button class="awr-btn compact" type="button" @click="directManualFallback = !directManualFallback">
                  {{ directManualFallback ? 'Hide SQL Text' : 'SQL Text fallback' }}
                </button>
              </div>
              <button class="awr-btn compact danger sql-tuning-delete-action" type="button" :disabled="!selectedConnectionId || isDeletingConnection" @click="deleteSelectedConnection">
                {{ isDeletingConnection ? 'Deleting...' : 'Delete Connection' }}
              </button>
            </div>

            <div class="sql-tuning-top-sql-controls">
              <div class="sql-tuning-control-row">
                <label class="awr-field compact">
                  SQL Count
                  <select v-model.number="topSqlLimit" class="awr-input compact" :disabled="isLoadingTopSql" @change="loadDirectTopSql">
                    <option :value="20">Top 20</option>
                    <option :value="50">Top 50</option>
                    <option :value="100">Top 100</option>
                  </select>
                </label>
              </div>
              <div class="sql-tuning-control-footer">
                <div class="sql-tuning-footer-left">
                  <button
                    :class="['sql-tuning-filter-toggle', excludeTunedTopSql ? 'active' : '']"
                    type="button"
                    :aria-pressed="excludeTunedTopSql"
                    @click="excludeTunedTopSql = !excludeTunedTopSql"
                  >
                    <span class="sql-tuning-toggle-dot"></span>
                    <span>Hide tuned SQL_ID</span>
                    <span v-if="excludeTunedTopSql && hiddenTopSqlCount" class="sql-tuning-toggle-count">
                      {{ hiddenTopSqlCount }}
                    </span>
                  </button>
                  <span v-if="topSqlLoaded" class="sql-tuning-load-status">{{ topSqlStatusMessage }}</span>
                </div>
                <button class="awr-btn compact primary" type="button" :disabled="!selectedConnectionId || isLoadingTopSql" @click="loadDirectTopSql">
                  {{ isLoadingTopSql ? 'Loading...' : 'Load SQL' }}
                </button>
              </div>
            </div>

            <div v-if="connectionMessage" class="awr-muted">{{ connectionMessage }}</div>
            <div v-if="connectionCapabilities.length" class="sql-tuning-capability-list">
              <span v-for="capability in connectionCapabilities" :key="capability">{{ capability }}</span>
            </div>
            <div v-if="connectionWarnings.length" class="awr-empty compact">
              <ul>
                <li v-for="warning in connectionWarnings" :key="warning">{{ warning }}</li>
              </ul>
            </div>
            <div v-if="directContext?.warnings.length" class="awr-empty compact">
              <ul>
                <li v-for="warning in directContext.warnings" :key="warning">{{ warning }}</li>
              </ul>
            </div>
            <div v-if="topSqlLoaded && !isLoadingTopSql && !displayedTopSql.length" class="awr-empty compact">
              {{ topSqlEmptyMessage }}
            </div>

            <div v-if="displayedTopSql.length" class="awr-table-wrap sql-tuning-top-sql">
              <table class="awr-table compact">
                <thead>
                  <tr>
                    <th>SQL_ID</th>
                    <th>Elapsed</th>
                    <th>Buffer Gets</th>
                    <th>Disk Reads</th>
                    <th>Executions</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="metric in displayedTopSql"
                    :key="metric.sqlId"
                    :class="[
                      directSqlId === metric.sqlId ? 'selected' : '',
                      historyBySqlId.has(metric.sqlId) ? 'has-history' : ''
                    ]"
                  >
                    <td>
                      <button
                        class="sql-tuning-sql-id-button"
                        type="button"
                        :disabled="isCollectingContext"
                        @click="useTopSql(metric)"
                      >
                        {{ metric.sqlId }}
                      </button>
                      <span v-if="tunedSqlIds.has(metric.sqlId)" class="awr-badge small">Tuned</span>
                    </td>
                    <td>{{ formatNumber(metric.elapsedTimeSec) }}</td>
                    <td>{{ formatNumber(metric.bufferGets) }}</td>
                    <td>{{ formatNumber(metric.diskReads) }}</td>
                    <td>{{ formatNumber(metric.executions) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <label v-if="showSqlTextInput" class="awr-field">
            {{ directSqlTextLabel }}
            <textarea
              v-model="sqlText"
              :class="['awr-textarea sql-tuning-main-input', sourceMode === 'DIRECT' ? 'sql-tuning-collected-sql' : '']"
              :readonly="sourceMode === 'DIRECT' && !directManualFallback"
              placeholder="예) SELECT *
FROM orders o
WHERE o.customer_id = :customer_id
  AND o.status = :status
ORDER BY o.created_at DESC"
              @keydown.ctrl.enter.prevent="runTuning"
              @keydown.meta.enter.prevent="runTuning"
            ></textarea>
          </label>

          <div v-if="showContextInputs" class="awr-form-grid">
            <label class="awr-field">
              Execution Plan
              <textarea
                v-model="executionPlan"
                class="awr-textarea"
                :readonly="sourceMode === 'DIRECT'"
                placeholder="예) DBMS_XPLAN.DISPLAY_CURSOR 결과
TABLE ACCESS FULL ORDERS
Predicate Information:
filter(&quot;O&quot;.&quot;CUSTOMER_ID&quot;=:CUSTOMER_ID)"
              ></textarea>
            </label>
            <label class="awr-field">
              Schema DDL
              <textarea
                v-model="schemaDdl"
                class="awr-textarea"
                :readonly="sourceMode === 'DIRECT'"
                placeholder="예) CREATE TABLE orders (
  order_id NUMBER,
  customer_id NUMBER,
  status VARCHAR2(20),
  created_at DATE
);

-- Table statistics
APP.ORDERS num_rows=50000000, blocks=900000, avg_row_len=120, sample_size=50000000, last_analyzed=2026-06-05 10:00:00, partitioned=NO, temporary=N

-- Table load statistics
APP.ORDERS inserts=1200000, updates=250000, deletes=10000, changed_rows=1460000, last_dml=2026-06-05 11:00:00, truncated=NO"
              ></textarea>
            </label>
            <label class="awr-field">
              Existing Indexes
              <textarea
                v-model="existingIndexes"
                class="awr-textarea"
                :readonly="sourceMode === 'DIRECT'"
                placeholder="예) CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);"
              ></textarea>
            </label>
            <label class="awr-field">
              Bind Samples
              <textarea
                v-model="bindSamples"
                class="awr-textarea"
                :readonly="sourceMode === 'DIRECT'"
                placeholder="예) :customer_id = 100284
:status = 'READY'"
              ></textarea>
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
                <span class="awr-history-question">{{ item.sqlId }} · {{ item.summary }}</span>
                <span class="awr-history-meta">{{ item.model }} · confidence {{ item.confidence }} · {{ formatDate(item.createdAt) }}</span>
              </button>
            </li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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
  DirectTopSqlOptions,
  SqlMetricResponse,
  SqlTuningRequest,
  SqlTuningResponse,
  TargetDbConnectionRequest,
  TargetDbConnectionResponse
} from '@/types/awr'

type SourceMode = 'MANUAL' | 'DIRECT'

const sourceMode = ref<SourceMode>('DIRECT')
const sqlText = ref('')
const executionPlan = ref('')
const schemaDdl = ref('')
const existingIndexes = ref('')
const bindSamples = ref('')
const directSqlId = ref('')
const directContext = ref<DirectDbContextResponse | null>(null)
const directTopSql = ref<SqlMetricResponse[]>([])
const directManualFallback = ref(false)
const excludeTunedTopSql = ref(false)
const topSqlLimit = ref<20 | 50 | 100>(20)
const connections = ref<TargetDbConnectionResponse[]>([])
const selectedConnectionId = ref<number | null>(null)
const showConnectionForm = ref(false)
const topSqlLoaded = ref(false)
const connectionForm = ref<TargetDbConnectionRequest>({
  name: '',
  dbType: 'ORACLE',
  jdbcUrl: '',
  username: '',
  password: '',
  visibility: 'PRIVATE',
  monitoringEnabled: false,
  monitoringIntervalSec: 600
})
const selectedResult = ref<SqlTuningResponse | null>(null)
const history = ref<SqlTuningResponse[]>([])
const isTuning = ref(false)
const isLoadingConnections = ref(false)
const isSavingConnection = ref(false)
const isTestingConnection = ref(false)
const isCollectingContext = ref(false)
const isLoadingTopSql = ref(false)
const isDeletingConnection = ref(false)
const errorMessage = ref('')
const connectionMessage = ref('')
const connectionCapabilities = ref<string[]>([])
const connectionWarnings = ref<string[]>([])
const copiedDdl = ref('')

const canTune = computed(() => {
  if (sourceMode.value === 'DIRECT') {
    return Boolean(selectedConnectionId.value
      && (directSqlId.value.trim() || (directManualFallback.value && sqlText.value.trim())))
  }
  return sqlText.value.trim().length > 0
})
const canFetchDirectContext = computed(() =>
  Boolean(selectedConnectionId.value
    && (directSqlId.value.trim() || (directManualFallback.value && sqlText.value.trim())))
)
const canSaveConnection = computed(() =>
  Boolean(connectionForm.value.name?.trim()
    && connectionForm.value.jdbcUrl?.trim()
    && connectionForm.value.username?.trim()
    && connectionForm.value.password?.trim())
)
const canTestConnection = computed(() =>
  Boolean(connectionForm.value.jdbcUrl?.trim()
    && connectionForm.value.username?.trim()
    && connectionForm.value.password?.trim())
)
const runButtonLabel = computed(() => sourceMode.value === 'DIRECT' ? 'Tune Direct DB' : 'Tune SQL')
const showSqlTextInput = computed(() =>
  sourceMode.value === 'MANUAL' || directManualFallback.value || Boolean(directContext.value)
)
const showContextInputs = computed(() => sourceMode.value === 'MANUAL' || Boolean(directContext.value))
const directSqlTextLabel = computed(() =>
  sourceMode.value === 'DIRECT'
    ? (directManualFallback.value ? 'SQL Text fallback' : 'Collected SQL Text')
    : 'SQL Text'
)
const selectedConnection = computed(() =>
  connections.value.find((connection) => connection.id === selectedConnectionId.value) || null
)
const tunedSqlIds = computed(() => new Set(
  history.value
    .map((item) => item.sqlId)
    .filter((sqlId): sqlId is string => Boolean(sqlId))
))
const historyBySqlId = computed(() => {
  const results = new Map<string, SqlTuningResponse>()
  history.value.forEach((item) => {
    if (item.sqlId && !results.has(item.sqlId)) {
      results.set(item.sqlId, item)
    }
  })
  return results
})
const displayedTopSql = computed(() =>
  excludeTunedTopSql.value
    ? directTopSql.value.filter((metric) => !tunedSqlIds.value.has(metric.sqlId))
    : directTopSql.value
)
const hiddenTopSqlCount = computed(() => Math.max(directTopSql.value.length - displayedTopSql.value.length, 0))
const topSqlStatusMessage = computed(() => {
  if (!topSqlLoaded.value) return ''
  if (!directTopSql.value.length) return '0 SQL_ID loaded'
  if (excludeTunedTopSql.value && hiddenTopSqlCount.value) {
    return `${displayedTopSql.value.length}/${directTopSql.value.length} SQL_ID shown`
  }
  return `${directTopSql.value.length} SQL_ID loaded`
})
const topSqlEmptyMessage = computed(() => {
  if (directTopSql.value.length && excludeTunedTopSql.value) {
    return 'All loaded SQL_IDs are hidden. Turn off Hide tuned SQL_ID to show them.'
  }
  return 'No SQL was found in the target database current SQL views.'
})

onMounted(() => {
  loadHistory()
  loadConnections()
})

async function loadHistory() {
  history.value = await getSqlTuningHistory()
}

async function loadConnections(loadTopSql = true) {
  isLoadingConnections.value = true
  connectionMessage.value = ''
  try {
    connections.value = await getTargetDbConnections()
    if (selectedConnectionId.value && !connections.value.some((connection) => connection.id === selectedConnectionId.value)) {
      selectedConnectionId.value = null
      directTopSql.value = []
      topSqlLoaded.value = false
    }
    if (!selectedConnectionId.value && connections.value.length) {
      selectedConnectionId.value = connections.value[0].id
    }
    if (!connections.value.length) {
      showConnectionForm.value = true
    }
    if (loadTopSql && sourceMode.value === 'DIRECT' && selectedConnectionId.value) {
      await loadDirectTopSql()
    }
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'Target DB connections failed to load.'
  } finally {
    isLoadingConnections.value = false
  }
}

function setSourceMode(mode: SourceMode) {
  sourceMode.value = mode
  if (mode === 'DIRECT' && selectedConnectionId.value && directTopSql.value.length === 0) {
    void loadDirectTopSql()
  } else if (mode === 'DIRECT' && !selectedConnectionId.value) {
    showConnectionForm.value = true
  }
}

async function handleConnectionChange() {
  directSqlId.value = ''
  directContext.value = null
  directTopSql.value = []
  topSqlLoaded.value = false
  clearDirectCollectedInput()
  if (selectedConnectionId.value) {
    await loadDirectTopSql()
  }
}

function clearDirectCollectedInput() {
  if (!directManualFallback.value) {
    sqlText.value = ''
  }
  executionPlan.value = ''
  schemaDdl.value = ''
  existingIndexes.value = ''
  bindSamples.value = ''
}

async function runTuning() {
  if (!canTune.value || isTuning.value) return
  if (sourceMode.value === 'DIRECT') {
    await runDirectTuning()
    return
  }
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

async function runDirectTuning() {
  errorMessage.value = ''
  isTuning.value = true
  try {
    selectedResult.value = await tuneDirectSql({
      connectionId: selectedConnectionId.value,
      sqlId: directSqlId.value,
      sqlText: directManualFallback.value ? sqlText.value : undefined
    })
    restoreInput(selectedResult.value.input, selectedResult.value.metric?.sqlText)
    history.value = await getSqlTuningHistory()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Direct DB tuning failed.'
  } finally {
    isTuning.value = false
  }
}

async function saveConnection() {
  if (!canSaveConnection.value || isSavingConnection.value) return
  isSavingConnection.value = true
  connectionMessage.value = ''
  connectionCapabilities.value = []
  connectionWarnings.value = []
  try {
    const testResult = await testTargetDbConnection(connectionForm.value)
    connectionCapabilities.value = testResult.capabilities || []
    connectionWarnings.value = testResult.warnings || []
    if (!testResult.success) {
      connectionMessage.value = testResult.message || 'Connection test failed.'
      return
    }
    const saved = await createTargetDbConnection(connectionForm.value)
    selectedConnectionId.value = saved.id
    resetConnectionForm()
    await loadConnections(false)
    await handleConnectionChange()
    showConnectionForm.value = false
    connectionMessage.value = 'Connection saved. Top SQL loaded.'
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'Connection save failed.'
  } finally {
    isSavingConnection.value = false
  }
}

async function testConnection() {
  if (!canTestConnection.value || isTestingConnection.value) return
  isTestingConnection.value = true
  connectionMessage.value = ''
  connectionCapabilities.value = []
  connectionWarnings.value = []
  try {
    const result = await testTargetDbConnection(connectionForm.value)
    connectionCapabilities.value = result.capabilities || []
    connectionWarnings.value = result.warnings || []
    connectionMessage.value = result.success
      ? `${result.databaseProductName || 'DB'} connection succeeded. Save connection to load Top SQL.`
      : result.message
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'Connection test failed.'
  } finally {
    isTestingConnection.value = false
  }
}

async function testSelectedConnection() {
  if (!selectedConnectionId.value || isTestingConnection.value) return
  isTestingConnection.value = true
  connectionMessage.value = ''
  connectionCapabilities.value = []
  connectionWarnings.value = []
  try {
    const result = await testSavedTargetDbConnection(selectedConnectionId.value)
    connectionCapabilities.value = result.capabilities || []
    connectionWarnings.value = result.warnings || []
    connectionMessage.value = result.success
      ? `${selectedConnection.value?.name || 'Saved connection'} test succeeded.`
      : result.message
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'Saved connection test failed.'
  } finally {
    isTestingConnection.value = false
  }
}

async function deleteSelectedConnection() {
  if (!selectedConnectionId.value || isDeletingConnection.value) return
  const connectionName = selectedConnection.value?.name || 'selected connection'
  if (!window.confirm(`Delete ${connectionName}?`)) return
  isDeletingConnection.value = true
  connectionMessage.value = ''
  connectionCapabilities.value = []
  connectionWarnings.value = []
  errorMessage.value = ''
  try {
    await deleteTargetDbConnection(selectedConnectionId.value)
    selectedConnectionId.value = null
    directSqlId.value = ''
    directContext.value = null
    directTopSql.value = []
    topSqlLoaded.value = false
    clearDirectCollectedInput()
    await loadConnections(false)
    connectionMessage.value = 'Connection deleted.'
    if (selectedConnectionId.value) {
      await loadDirectTopSql()
    }
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'Connection delete failed.'
  } finally {
    isDeletingConnection.value = false
  }
}

async function fetchDirectContext() {
  if (!canFetchDirectContext.value || isCollectingContext.value) return
  isCollectingContext.value = true
  errorMessage.value = ''
  try {
    directContext.value = await collectDirectDbContext({
      connectionId: selectedConnectionId.value,
      sqlId: directSqlId.value,
      sqlText: directManualFallback.value ? sqlText.value : undefined
    })
    directSqlId.value = directContext.value.metric?.sqlId || directSqlId.value
    restoreInput(directContext.value.input, directContext.value.metric?.sqlText)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Direct DB context fetch failed.'
  } finally {
    isCollectingContext.value = false
  }
}

async function loadDirectTopSql() {
  if (!selectedConnectionId.value || isLoadingTopSql.value) return
  isLoadingTopSql.value = true
  topSqlLoaded.value = false
  errorMessage.value = ''
  try {
    directTopSql.value = await getDirectTopSql(selectedConnectionId.value, topSqlOptions())
    topSqlLoaded.value = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Direct DB Top SQL failed.'
  } finally {
    isLoadingTopSql.value = false
  }
}

function topSqlOptions(): DirectTopSqlOptions {
  return {
    limit: topSqlLimit.value,
    sortBy: 'ELAPSED'
  }
}

async function useTopSql(metric: SqlMetricResponse) {
  directSqlId.value = metric.sqlId
  sqlText.value = metric.sqlText || ''
  selectedResult.value = null
  const existingHistory = historyBySqlId.value.get(metric.sqlId)
  if (existingHistory) {
    selectResult(existingHistory)
  }
  await fetchDirectContext()
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

function toggleConnectionForm() {
  showConnectionForm.value = !showConnectionForm.value
}

function resetConnectionForm() {
  connectionForm.value = {
    name: '',
    dbType: 'ORACLE',
    jdbcUrl: '',
    username: '',
    password: '',
    visibility: 'PRIVATE',
    monitoringEnabled: false,
    monitoringIntervalSec: 600
  }
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

function formatDate(value?: string | null) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

function formatNumber(value?: number | null) {
  return value == null ? '-' : new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)
}
</script>

<style src="../awr/awr.css"></style>

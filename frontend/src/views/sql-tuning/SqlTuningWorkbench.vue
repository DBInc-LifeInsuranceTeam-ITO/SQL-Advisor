<template>
  <div class="awr-page">
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
            {{ isTuning ? 'Tuning...' : 'Tune SQL' }}
          </button>
        </div>

        <div class="awr-form">
          <label class="awr-field">
            SQL Text
            <textarea
              v-model="sqlText"
              class="awr-textarea sql-tuning-main-input"
              placeholder="예) SELECT *
FROM orders o
WHERE o.customer_id = :customer_id
  AND o.status = :status
ORDER BY o.created_at DESC"
              @keydown.ctrl.enter.prevent="runTuning"
              @keydown.meta.enter.prevent="runTuning"
            ></textarea>
          </label>

          <div class="awr-form-grid">
            <label class="awr-field">
              Execution Plan
              <textarea
                v-model="executionPlan"
                class="awr-textarea"
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
                placeholder="예) CREATE TABLE orders (
  order_id NUMBER,
  customer_id NUMBER,
  status VARCHAR2(20),
  created_at DATE
);"
              ></textarea>
            </label>
            <label class="awr-field">
              Existing Indexes
              <textarea
                v-model="existingIndexes"
                class="awr-textarea"
                placeholder="예) CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);"
              ></textarea>
            </label>
            <label class="awr-field">
              Bind Samples
              <textarea
                v-model="bindSamples"
                class="awr-textarea"
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
import { getSqlTuningHistory, tuneSql } from '@/api/sqlTuning'
import type { SqlTuningRequest, SqlTuningResponse } from '@/types/awr'

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

const canTune = computed(() => sqlText.value.trim().length > 0)

onMounted(loadHistory)

async function loadHistory() {
  history.value = await getSqlTuningHistory()
}

async function runTuning() {
  if (!canTune.value || isTuning.value) return
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

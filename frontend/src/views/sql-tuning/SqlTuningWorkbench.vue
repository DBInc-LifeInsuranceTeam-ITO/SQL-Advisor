<template>
  <div class="awr-page sql-tuning-page">
    <div class="awr-upload-hero sql-tuning-hero">
      <div>
        <p class="awr-upload-eyebrow">SQL 성능 분석</p>
        <h1 class="awr-main-title">SQL 튜닝</h1>
        <p>
          SQL 실행 정보와 데이터베이스 오브젝트 정보를 분석하여
          성능 저하 원인과 튜닝 권고사항을 확인할 수 있습니다.
        </p>
      </div>
    </div>

    <div v-if="errorMessage" class="awr-empty">{{ errorMessage }}</div>

    <div class="awr-split">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">분석 대상 입력</h2>
          <button class="awr-btn primary" type="button" :disabled="!canTune || isTuning" @click="runTuning">
            {{ isTuning ? '분석 중...' : runButtonLabel }}
          </button>
        </div>

        <div class="sql-tuning-mode-switch">
          <button
            :class="['sql-tuning-mode-option', sourceMode === 'DIRECT' ? 'active' : '']"
            type="button"
            @click="setSourceMode('DIRECT')"
          >
            DB에서 SQL 조회
          </button>
          <button
            :class="['sql-tuning-mode-option', sourceMode === 'MANUAL' ? 'active' : '']"
            type="button"
            @click="setSourceMode('MANUAL')"
          >
            SQL 직접 입력
          </button>
        </div>

        <div class="awr-form">
          <div v-if="sourceMode === 'DIRECT'" class="sql-tuning-direct-box">
            <div class="awr-panel-header compact">
              <h3 class="awr-panel-title">분석 대상 DB</h3>
              <div class="awr-actions compact">
                <button class="awr-btn compact" type="button" :disabled="isLoadingConnections" @click="loadConnections()">
                  새로고침
                </button>
                <button class="awr-btn compact" type="button" @click="toggleConnectionForm">
                  {{ showConnectionForm ? '연결 등록 닫기' : 'DB 연결 등록' }}
                </button>
              </div>
            </div>

            <div class="awr-form-grid">
              <label class="awr-field">
                등록된 DB 연결
                <select v-model.number="selectedConnectionId" class="awr-input" @change="handleConnectionChange">
                  <option :value="null">DB 연결을 선택하세요</option>
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
                연결 이름
                <input v-model="connectionForm.name" class="awr-input" placeholder="예) 운영 DB 조회용" />
              </label>
              <label class="awr-field">
                JDBC URL
                <input v-model="connectionForm.jdbcUrl" class="awr-input" placeholder="예) jdbc:oracle:thin:@//host:1521/service" />
              </label>
              <label class="awr-field">
                사용자 계정
                <input v-model="connectionForm.username" class="awr-input" placeholder="예) SQLADVISOR_RO" />
              </label>
              <label class="awr-field">
                비밀번호
                <input v-model="connectionForm.password" class="awr-input" type="password" placeholder="DB 계정 비밀번호" />
              </label>
            </div>

            <div class="awr-actions sql-tuning-action-bar">
              <div class="sql-tuning-action-left">
                <button v-if="showConnectionForm" class="awr-btn compact" type="button" :disabled="!canTestConnection || isTestingConnection" @click="testConnection">
                  {{ isTestingConnection ? '연결 확인 중...' : '연결 확인' }}
                </button>
                <button v-if="showConnectionForm" class="awr-btn compact" type="button" :disabled="!canSaveConnection || isSavingConnection" @click="saveConnection">
                  {{ isSavingConnection ? '저장 중...' : '연결 저장' }}
                </button>
                <button class="awr-btn compact" type="button" :disabled="!selectedConnectionId || isTestingConnection" @click="testSelectedConnection">
                  {{ isTestingConnection ? '연결 확인 중...' : '선택한 DB 연결 확인' }}
                </button>
                <button class="awr-btn compact" type="button" @click="directManualFallback = !directManualFallback">
                  {{ directManualFallback ? 'SQL 직접 입력 닫기' : 'SQL 직접 입력으로 전환' }}
                </button>
              </div>
              <button class="awr-btn compact danger sql-tuning-delete-action" type="button" :disabled="!selectedConnectionId || isDeletingConnection" @click="deleteSelectedConnection">
                {{ isDeletingConnection ? '삭제 중...' : '연결 삭제' }}
              </button>
            </div>

            <div class="sql-tuning-top-sql-controls">
              <div class="sql-tuning-control-row">
                <label class="awr-field compact">
                  조회 건수
                  <select v-model.number="topSqlLimit" class="awr-input compact" :disabled="isLoadingTopSql" @change="loadDirectTopSql">
                    <option :value="20">상위 20건</option>
                    <option :value="50">상위 50건</option>
                    <option :value="100">상위 100건</option>
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
                    <span>분석 완료 SQL 숨기기</span>
                    <span v-if="excludeTunedTopSql && hiddenTopSqlCount" class="sql-tuning-toggle-count">
                      {{ hiddenTopSqlCount }}
                    </span>
                  </button>
                  <span v-if="topSqlLoaded" class="sql-tuning-load-status">{{ topSqlStatusMessage }}</span>
                </div>
                <button class="awr-btn compact primary" type="button" :disabled="!selectedConnectionId || isLoadingTopSql" @click="loadDirectTopSql">
                  {{ isLoadingTopSql ? '조회 중...' : '부하 SQL 조회' }}
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
                    <th
                      class="sql-tuning-sortable-header"
                      role="button"
                      tabindex="0"
                      :aria-sort="topSqlSortColumn === 'ELAPSED' ? ariaSortDirection : 'none'"
                      @click="setTopSqlSort('ELAPSED')"
                      @keydown.enter.prevent="setTopSqlSort('ELAPSED')"
                      @keydown.space.prevent="setTopSqlSort('ELAPSED')"
                    >
                      <span class="sql-tuning-sort-label">
                        총 수행시간(초)
                        <span v-if="topSqlSortColumn === 'ELAPSED'" class="sql-tuning-sort-indicator">{{ topSqlSortDirection === 'DESC' ? '↓' : '↑' }}</span>
                      </span>
                    </th>
                    <th
                      class="sql-tuning-sortable-header"
                      role="button"
                      tabindex="0"
                      :aria-sort="topSqlSortColumn === 'BUFFER_GETS' ? ariaSortDirection : 'none'"
                      @click="setTopSqlSort('BUFFER_GETS')"
                      @keydown.enter.prevent="setTopSqlSort('BUFFER_GETS')"
                      @keydown.space.prevent="setTopSqlSort('BUFFER_GETS')"
                    >
                      <span class="sql-tuning-sort-label">
                        버퍼 조회량
                        <span v-if="topSqlSortColumn === 'BUFFER_GETS'" class="sql-tuning-sort-indicator">{{ topSqlSortDirection === 'DESC' ? '↓' : '↑' }}</span>
                      </span>
                    </th>
                    <th
                      class="sql-tuning-sortable-header"
                      role="button"
                      tabindex="0"
                      :aria-sort="topSqlSortColumn === 'DISK_READS' ? ariaSortDirection : 'none'"
                      @click="setTopSqlSort('DISK_READS')"
                      @keydown.enter.prevent="setTopSqlSort('DISK_READS')"
                      @keydown.space.prevent="setTopSqlSort('DISK_READS')"
                    >
                      <span class="sql-tuning-sort-label">
                        디스크 읽기량
                        <span v-if="topSqlSortColumn === 'DISK_READS'" class="sql-tuning-sort-indicator">{{ topSqlSortDirection === 'DESC' ? '↓' : '↑' }}</span>
                      </span>
                    </th>
                    <th
                      class="sql-tuning-sortable-header"
                      role="button"
                      tabindex="0"
                      :aria-sort="topSqlSortColumn === 'EXECUTIONS' ? ariaSortDirection : 'none'"
                      @click="setTopSqlSort('EXECUTIONS')"
                      @keydown.enter.prevent="setTopSqlSort('EXECUTIONS')"
                      @keydown.space.prevent="setTopSqlSort('EXECUTIONS')"
                    >
                      <span class="sql-tuning-sort-label">
                        실행 횟수
                        <span v-if="topSqlSortColumn === 'EXECUTIONS'" class="sql-tuning-sort-indicator">{{ topSqlSortDirection === 'DESC' ? '↓' : '↑' }}</span>
                      </span>
                    </th>
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
                      <span v-if="tunedSqlIds.has(metric.sqlId)" class="awr-badge small">분석 완료</span>
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

          <label v-if="sourceMode === 'MANUAL' || directManualFallback" class="awr-field">
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
            <label class="awr-field sql-tuning-context-wide">
              관련 테이블의 기존 인덱스
              <div v-if="sourceMode === 'DIRECT' && relatedIndexRows.length" class="sql-tuning-index-table-wrap">
                <table class="sql-tuning-index-table">
                  <thead>
                    <tr>
                      <th>테이블</th>
                      <th>인덱스</th>
                      <th>컬럼</th>
                      <th>고유 여부</th>
                      <th>상태</th>
                      <th>사용 가능 여부</th>
                      <th>로깅</th>
                      <th>통계정보</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in relatedIndexRows" :key="`related-${row.table}-${row.index}`">
                      <td>{{ row.table }}</td>
                      <td>{{ row.index }}</td>
                      <td>{{ row.columns }}</td>
                      <td>{{ row.uniqueness }}</td>
                      <td>{{ row.status }}</td>
                      <td>{{ row.visibility }}</td>
                      <td>{{ row.logging }}</td>
                      <td>{{ row.stats }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <pre v-else-if="sourceMode === 'DIRECT' && legacyExistingIndexes" class="sql-tuning-context-viewer sql-tuning-index-viewer">{{ legacyExistingIndexes }}</pre>
              <div v-else-if="sourceMode === 'DIRECT'" class="awr-empty compact">관련 테이블의 기존 인덱스 정보를 수집하지 못했습니다.</div>
              <textarea
                v-else
                v-model="existingIndexes"
                class="awr-textarea sql-tuning-resizable-textarea"
                placeholder="예) CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);"
              ></textarea>
            </label>
            <label v-if="sourceMode === 'DIRECT'" class="awr-field sql-tuning-context-wide">
              실행계획에서 사용된 인덱스
              <div v-if="usedIndexRows.length" class="sql-tuning-index-table-wrap">
                <table class="sql-tuning-index-table">
                  <thead>
                    <tr>
                      <th>테이블</th>
                      <th>인덱스</th>
                      <th>접근 방식</th>
                      <th>고유 여부</th>
                      <th>상태</th>
                      <th>사용 가능 여부</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in usedIndexRows" :key="`used-${row.table}-${row.index}-${row.access}`">
                      <td>{{ row.table }}</td>
                      <td>{{ row.index }}</td>
                      <td>{{ row.access }}</td>
                      <td>{{ row.uniqueness }}</td>
                      <td>{{ row.status }}</td>
                      <td>{{ row.visibility }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-else class="awr-empty compact">수집된 실행계획에서 사용된 인덱스를 찾지 못했습니다.</div>
            </label>
            <label v-if="sourceMode === 'DIRECT' && !directManualFallback" class="awr-field sql-tuning-context-wide">
              수집된 SQL
              <pre v-if="sqlText" class="sql-tuning-context-viewer sql-tuning-sql-text-viewer">{{ sqlText }}</pre>
              <div v-else class="awr-empty compact">SQL 문장을 수집하지 못했습니다.</div>
            </label>
            <label class="awr-field sql-tuning-context-wide">
              바인드 변수 예시
              <pre v-if="sourceMode === 'DIRECT' && bindSamples" class="sql-tuning-context-viewer sql-tuning-bind-viewer">{{ bindSamples }}</pre>
              <div v-else-if="sourceMode === 'DIRECT'" class="awr-empty compact">바인드 변수 값을 수집하지 못했습니다.</div>
              <textarea
                v-else
                v-model="bindSamples"
                class="awr-textarea sql-tuning-resizable-textarea"
                placeholder="예) :customer_id = 100284
:status = 'READY'"
              ></textarea>
            </label>
            <label class="awr-field sql-tuning-context-wide">
              실행계획
              <div v-if="sourceMode === 'DIRECT'" class="sql-tuning-plan-viewer">
                <template v-if="executionPlanBlocks.length">
                  <template v-for="(block, blockIndex) in executionPlanBlocks" :key="blockIndex">
                    <pre v-if="block.type === 'text'" class="sql-tuning-plan-text-block">{{ block.lines.join('\n') }}</pre>
                    <div v-else class="sql-tuning-plan-table-wrap">
                      <table class="sql-tuning-plan-table">
                        <thead>
                          <tr>
                            <th v-for="header in block.headers" :key="header">{{ header }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          <tr v-for="(row, rowIndex) in block.rows" :key="rowIndex">
                            <td
                              v-for="(cell, cellIndex) in row"
                              :key="cellIndex"
                              :class="{ 'sql-tuning-plan-operation-cell': isExecutionPlanOperationColumn(block.headers, cellIndex) }"
                            >
                              {{ cell }}
                            </td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </template>
                </template>
                <div v-else class="awr-empty compact">실행계획을 수집하지 못했습니다.</div>
              </div>
              <textarea
                v-else
                v-model="executionPlan"
                class="awr-textarea sql-tuning-plan-textarea"
                placeholder="예) DBMS_XPLAN.DISPLAY_CURSOR 결과
TABLE ACCESS FULL ORDERS
Predicate Information:
filter(&quot;O&quot;.&quot;CUSTOMER_ID&quot;=:CUSTOMER_ID)"
              ></textarea>
            </label>
          </div>
        </div>
      </section>

      <section class="awr-panel awr-side-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">{{ selectedResult ? `SQL 분석 결과 - ${selectedResult.sqlId}` : 'SQL 분석 결과' }}</h2>
            <p v-if="selectedResult" class="awr-muted" style="margin: 0.25rem 0 0;">분석 신뢰도 {{ selectedResult.confidence }}</p>
          </div>
          <span v-if="selectedResult" class="awr-badge">{{ selectedResult.model }}</span>
        </div>

        <template v-if="selectedResult">
          <div class="sql-tuning-summary-strip">
            <div>
              <span>분석 신뢰도</span>
              <strong>{{ selectedResult.confidence }}</strong>
            </div>
            <div>
              <span>인덱스 검토안</span>
              <strong>{{ selectedIndexRecommendations.length }}</strong>
            </div>
            <div>
              <span>추가 필요 정보</span>
              <strong>{{ selectedResult.missingInputs.length }}</strong>
            </div>
          </div>
          <p class="sql-tuning-summary-text">{{ selectedResult.summary }}</p>

          <div class="awr-finding-grid compact" style="margin-top: 1rem;">
            <div>
              <strong>확인된 성능 문제</strong>
              <ul>
                <li v-for="item in selectedResult.symptoms" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <strong>SQL 개선 검토사항</strong>
              <ul>
                <li v-for="item in selectedResult.rewriteRecommendations" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <strong>적용 전 확인사항</strong>
              <ul>
                <li v-for="item in selectedResult.validationSteps" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div>
              <strong>추가 필요 정보</strong>
              <ul>
                <li v-for="item in selectedResult.missingInputs" :key="item">{{ item }}</li>
              </ul>
            </div>
          </div>

          <div class="awr-stack" style="margin-top: 1rem;">
            <article v-for="item in selectedIndexRecommendations" :key="`${item.tableName}-${item.columns.join('-')}`" class="awr-finding">
              <h3>{{ item.tableName || '인덱스 검토안' }}</h3>
              <p class="awr-muted">{{ item.reason }}</p>
              <p><strong>대상 컬럼:</strong> {{ formatColumns(item.columns) }}</p>
              <div v-if="item.ddlCandidate" class="sql-tuning-ddl-header">
                <strong>인덱스 생성문 예시</strong>
                <button class="awr-btn compact" type="button" @click="copyDdl(item.ddlCandidate || '')">
                  {{ copiedDdl === item.ddlCandidate ? '복사 완료' : '복사' }}
                </button>
              </div>
              <pre v-if="item.ddlCandidate" class="awr-code">{{ item.ddlCandidate }}</pre>
              <template v-if="item.buildSteps?.length">
                <div class="sql-tuning-ddl-header">
                  <strong>대용량 테이블 생성 옵션</strong>
                </div>
                <pre class="awr-code">{{ item.buildSteps.join('\n') }}</pre>
              </template>
              <ul v-if="item.postCreateSteps?.length" class="sql-tuning-compact-list">
                <li v-for="step in item.postCreateSteps" :key="step">{{ step }}</li>
              </ul>
              <p><strong>예상 효과:</strong> {{ item.expectedBenefit }}</p>
              <p class="awr-muted">{{ item.risk }}</p>
            </article>
            <div v-if="selectedIndexRecommendations.length === 0" class="awr-empty compact">
              현재 수집된 정보만으로는 구체적인 인덱스 생성안을 만들 수 없습니다.
            </div>
          </div>

          <div class="sql-tuning-question-box">
            <div class="awr-panel-header compact">
              <h3 class="awr-panel-title">분석 결과 추가 질문</h3>
              <span v-if="tuningQuestions.length" class="awr-badge">{{ tuningQuestions.length }}</span>
            </div>
            <form class="sql-tuning-question-form" @submit.prevent="askQuestion">
              <textarea
                v-model="tuningQuestion"
                class="awr-textarea"
                placeholder="인덱스 적용 위험, 실행계획 또는 검증 방법 등을 질문하세요."
              ></textarea>
              <button class="awr-btn compact primary" type="submit" :disabled="!canAskTuningQuestion || isAskingQuestion">
                {{ isAskingQuestion ? '답변 생성 중...' : '질문하기' }}
              </button>
            </form>
            <div v-if="isLoadingQuestions" class="awr-muted">이전 질문을 불러오는 중...</div>
            <div v-else-if="tuningQuestions.length" class="sql-tuning-question-list">
              <article v-for="item in tuningQuestions" :key="item.questionId" class="sql-tuning-question-item">
                <strong>{{ item.question }}</strong>
                <div class="sql-tuning-markdown" v-html="renderMarkdown(item.answer)"></div>
                <span>{{ item.model }} · 분석 신뢰도 {{ item.confidence }} · {{ formatDate(item.createdAt) }}</span>
              </article>
            </div>
          </div>
        </template>
        <div v-else class="awr-empty compact">왼쪽에서 분석할 SQL을 선택하거나 직접 입력한 뒤 분석 실행 버튼을 눌러주세요.</div>

        <div v-if="history.length" class="awr-side-history">
          <div class="awr-panel-header compact">
            <h3 class="awr-panel-title">SQL 분석 이력</h3>
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
                <span class="awr-history-meta">{{ item.model }} · 분석 신뢰도 {{ item.confidence }} · {{ formatDate(item.createdAt) }}</span>
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
import { useRoute } from 'vue-router'
import {
  askSqlTuningQuestion,
  collectDirectDbContext,
  createTargetDbConnection,
  deleteTargetDbConnection,
  getDirectTopSql,
  getSqlTuningHistory,
  getSqlTuningQuestions,
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
  SqlTuningQuestionResponse,
  SqlTuningRequest,
  SqlTuningResponse,
  TargetDbConnectionRequest,
  TargetDbConnectionResponse
} from '@/types/awr'

type SourceMode = 'MANUAL' | 'DIRECT'
type TopSqlSortColumn = 'ELAPSED' | 'BUFFER_GETS' | 'DISK_READS' | 'EXECUTIONS'
type SortDirection = 'ASC' | 'DESC'

interface ExecutionPlanTextBlock {
  type: 'text'
  lines: string[]
}

interface ExecutionPlanTableBlock {
  type: 'table'
  headers: string[]
  rows: string[][]
}

type ExecutionPlanBlock = ExecutionPlanTextBlock | ExecutionPlanTableBlock

interface IndexMetadataRow {
  table: string
  index: string
  columns: string
  access: string
  uniqueness: string
  status: string
  visibility: string
  logging: string
  stats: string
}

const route = useRoute()
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
const topSqlSortColumn = ref<TopSqlSortColumn>('ELAPSED')
const topSqlSortDirection = ref<SortDirection>('DESC')
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
const tuningQuestions = ref<SqlTuningQuestionResponse[]>([])
const tuningQuestion = ref('')
const history = ref<SqlTuningResponse[]>([])
const isTuning = ref(false)
const isAskingQuestion = ref(false)
const isLoadingQuestions = ref(false)
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
const runButtonLabel = computed(() => sourceMode.value === 'DIRECT' ? '선택한 SQL 분석' : '입력한 SQL 분석')
const showContextInputs = computed(() => sourceMode.value === 'MANUAL' || Boolean(directContext.value))
const canAskTuningQuestion = computed(() => Boolean(selectedResult.value?.tuningId && tuningQuestion.value.trim()))
const directSqlTextLabel = computed(() =>
  sourceMode.value === 'DIRECT'
    ? (directManualFallback.value ? '직접 입력할 SQL' : '수집된 SQL')
    : '분석할 SQL'
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
const displayedTopSql = computed(() => {
  const rows = excludeTunedTopSql.value
    ? directTopSql.value.filter((metric) => !tunedSqlIds.value.has(metric.sqlId))
    : [...directTopSql.value]
  return rows.sort(compareTopSql)
})
const hiddenTopSqlCount = computed(() => Math.max(directTopSql.value.length - displayedTopSql.value.length, 0))
const topSqlStatusMessage = computed(() => {
  if (!topSqlLoaded.value) return ''
  if (!directTopSql.value.length) return '조회된 SQL_ID가 없습니다.'
  if (excludeTunedTopSql.value && hiddenTopSqlCount.value) {
    return `전체 ${directTopSql.value.length}건 중 ${displayedTopSql.value.length}건 표시`
  }
  return `SQL_ID ${directTopSql.value.length}건 조회 완료`
})
const ariaSortDirection = computed(() =>
  topSqlSortDirection.value === 'DESC' ? 'descending' : 'ascending'
)
const topSqlEmptyMessage = computed(() => {
  if (directTopSql.value.length && excludeTunedTopSql.value) {
    return '조회된 SQL이 모두 숨김 처리되었습니다. 분석 완료 SQL 숨기기를 해제해주세요.'
  }
  return '현재 DB에서 조회된 SQL이 없습니다.'
})
const relatedTableIndexes = computed(() => contextSection(existingIndexes.value, 'Related Table Indexes'))
const planUsedIndexes = computed(() => contextSection(existingIndexes.value, 'Plan Used Indexes'))
const relatedIndexRows = computed(() => parseIndexRows(relatedTableIndexes.value))
const usedIndexRows = computed(() => parseIndexRows(planUsedIndexes.value))
const legacyExistingIndexes = computed(() => {
  if (!existingIndexes.value.trim()) return ''
  if (relatedTableIndexes.value || planUsedIndexes.value) return ''
  return existingIndexes.value
})
const executionPlanBlocks = computed(() => parseExecutionPlan(executionPlan.value))
const selectedIndexRecommendations = computed(() =>
  selectedResult.value?.indexRecommendations || []
)

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
    const routedConnectionId = routeConnectionId()
    if (routedConnectionId && connections.value.some((connection) => connection.id === routedConnectionId)) {
      selectedConnectionId.value = routedConnectionId
    }
    const routedSqlId = routeStringParam('sqlId')
    if (routedSqlId && !directSqlId.value.trim()) {
      directSqlId.value = routedSqlId
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
    connectionMessage.value = error instanceof Error ? error.message : 'DB 연결 목록을 불러오지 못했습니다.'
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
    await loadQuestionsForSelected()
    history.value = await getSqlTuningHistory()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'SQL 분석 중 오류가 발생했습니다.'
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
    await loadQuestionsForSelected()
    history.value = await getSqlTuningHistory()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'DB 연계 SQL 분석 중 오류가 발생했습니다.'
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
      connectionMessage.value = testResult.message || 'DB 연결 확인에 실패했습니다.'
      return
    }
    const saved = await createTargetDbConnection(connectionForm.value)
    selectedConnectionId.value = saved.id
    resetConnectionForm()
    await loadConnections(false)
    await handleConnectionChange()
    showConnectionForm.value = false
    connectionMessage.value = 'DB 연결을 저장하고 부하 SQL을 조회했습니다.'
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'DB 연결 저장에 실패했습니다.'
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
      ? `${result.databaseProductName || 'DB'} 연결에 성공했습니다. 저장하면 부하 SQL을 조회할 수 있습니다.`
      : result.message
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'DB 연결 확인에 실패했습니다.'
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
      ? `${selectedConnection.value?.name || '선택한 DB 연결'} 연결 확인에 성공했습니다.`
      : result.message
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : '저장된 DB 연결 확인에 실패했습니다.'
  } finally {
    isTestingConnection.value = false
  }
}

async function deleteSelectedConnection() {
  if (!selectedConnectionId.value || isDeletingConnection.value) return
  const connectionName = selectedConnection.value?.name || '선택한 DB 연결'
  if (!window.confirm(`${connectionName}을(를) 삭제하시겠습니까?`)) return
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
    connectionMessage.value = 'DB 연결을 삭제했습니다.'
    if (selectedConnectionId.value) {
      await loadDirectTopSql()
    }
  } catch (error) {
    connectionMessage.value = error instanceof Error ? error.message : 'DB 연결 삭제에 실패했습니다.'
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
    errorMessage.value = error instanceof Error ? error.message : 'DB에서 SQL 분석 정보를 수집하지 못했습니다.'
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
    errorMessage.value = error instanceof Error ? error.message : 'DB에서 부하 SQL을 조회하지 못했습니다.'
  } finally {
    isLoadingTopSql.value = false
  }
}

function topSqlOptions(): DirectTopSqlOptions {
  return {
    limit: topSqlLimit.value,
    sortBy: topSqlSortColumn.value
  }
}

async function setTopSqlSort(column: TopSqlSortColumn) {
  if (topSqlSortColumn.value === column) {
    topSqlSortDirection.value = topSqlSortDirection.value === 'DESC' ? 'ASC' : 'DESC'
    return
  }
  topSqlSortColumn.value = column
  topSqlSortDirection.value = 'DESC'
  await loadDirectTopSql()
}

function compareTopSql(left: SqlMetricResponse, right: SqlMetricResponse) {
  const leftValue = topSqlSortValue(left)
  const rightValue = topSqlSortValue(right)
  if (leftValue == null && rightValue == null) {
    return left.rankNo - right.rankNo
  }
  if (leftValue == null) return 1
  if (rightValue == null) return -1
  if (leftValue === rightValue) {
    return left.rankNo - right.rankNo
  }
  const direction = topSqlSortDirection.value === 'DESC' ? -1 : 1
  return (leftValue - rightValue) * direction
}

function topSqlSortValue(metric: SqlMetricResponse) {
  switch (topSqlSortColumn.value) {
    case 'BUFFER_GETS':
      return metric.bufferGets
    case 'DISK_READS':
      return metric.diskReads
    case 'EXECUTIONS':
      return metric.executions
    default:
      return metric.elapsedTimeSec
  }
}

function parseExecutionPlan(value: string): ExecutionPlanBlock[] {
  const blocks: ExecutionPlanBlock[] = []
  const textLines: string[] = []
  let tableLines: string[] = []

  const flushText = () => {
    const trimmed = trimEmptyEdges(textLines)
    if (trimmed.length) {
      blocks.push({ type: 'text', lines: trimmed })
    }
    textLines.length = 0
  }

  const flushTable = () => {
    if (!tableLines.length) return
    const parsedRows = tableLines.map(parseExecutionPlanCells).filter((row) => row.length)
    tableLines = []
    if (!parsedRows.length) return
    const headers = parsedRows[0].map((cell) => cell.trim())
    const rows = parsedRows.slice(1).map((row) =>
      row.map((cell, index) => formatExecutionPlanCell(cell, headers, index))
    )
    if (rows.length) {
      blocks.push({ type: 'table', headers, rows })
    } else {
      blocks.push({ type: 'text', lines: parsedRows[0] })
    }
  }

  value.split(/\r?\n/).forEach((line) => {
    if (isExecutionPlanSeparator(line)) {
      return
    }
    if (isExecutionPlanTableLine(line)) {
      flushText()
      tableLines.push(line)
      return
    }
    flushTable()
    textLines.push(line)
  })
  flushTable()
  flushText()
  return blocks
}

function parseExecutionPlanCells(line: string) {
  return line.split('|').slice(1, -1).map((cell) => cell.replace(/\s+$/, ''))
}

function formatExecutionPlanCell(cell: string, headers: string[], index: number) {
  if (isExecutionPlanOperationColumn(headers, index)) {
    return cell.replace(/^ /, '')
  }
  return cell.trim()
}

function isExecutionPlanTableLine(line: string) {
  const trimmed = line.trim()
  return trimmed.startsWith('|') && trimmed.endsWith('|')
}

function isExecutionPlanSeparator(line: string) {
  return /^-+$/.test(line.trim())
}

function isExecutionPlanOperationColumn(headers: string[], index: number) {
  return headers[index]?.trim().toUpperCase() === 'OPERATION'
}

function trimEmptyEdges(lines: string[]) {
  let start = 0
  let end = lines.length
  while (start < end && !lines[start].trim()) start += 1
  while (end > start && !lines[end - 1].trim()) end -= 1
  return lines.slice(start, end)
}

async function useTopSql(metric: SqlMetricResponse) {
  directSqlId.value = metric.sqlId
  sqlText.value = metric.sqlText || ''
  selectedResult.value = null
  tuningQuestions.value = []
  tuningQuestion.value = ''
  const existingHistory = historyBySqlId.value.get(metric.sqlId)
  if (existingHistory) {
    selectResult(existingHistory)
  }
  await fetchDirectContext()
}

function selectResult(item: SqlTuningResponse) {
  selectedResult.value = item
  restoreInput(item.input, item.metric?.sqlText)
  void loadQuestionsForSelected()
}

function restoreInput(input?: SqlTuningRequest | null, fallbackSqlText?: string | null) {
  sqlText.value = input?.sqlText || fallbackSqlText || ''
  executionPlan.value = input?.executionPlan || ''
  schemaDdl.value = input?.schemaDdl || ''
  existingIndexes.value = input?.existingIndexes || ''
  bindSamples.value = input?.bindSamples || ''
}

function contextSection(value: string, title: string) {
  if (!value.trim()) return ''
  const escapedTitle = title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = value.match(new RegExp(`(?:^|\\n)--\\s*${escapedTitle}\\s*\\n([\\s\\S]*?)(?=\\n--\\s+|$)`, 'i'))
  return match?.[1]?.trim() || ''
}

function parseIndexRows(value: string): IndexMetadataRow[] {
  return value
    .split(/\r?\n/)
    .map((line) => parseIndexRow(line))
    .filter((row): row is IndexMetadataRow => Boolean(row))
}

function parseIndexRow(line: string): IndexMetadataRow | null {
  const parts = line.split('|').map((part) => part.trim()).filter(Boolean)
  if (parts.length < 2) return null
  const attributes = new Map<string, string>()
  parts.slice(2).forEach((part) => {
    const separator = part.indexOf('=')
    if (separator < 1) return
    attributes.set(part.slice(0, separator).trim().toLowerCase(), part.slice(separator + 1).trim())
  })
  const stats = ['blevel', 'leaf_blocks', 'distinct_keys', 'clustering_factor', 'num_rows', 'last_analyzed']
    .map((key) => {
      const value = attributes.get(key)
      return value ? `${key}=${value}` : ''
    })
    .filter(Boolean)
    .join(', ')
  return {
    table: parts[0] || '-',
    index: parts[1] || '-',
    columns: unwrapAttribute(attributes.get('columns')) || '-',
    access: attributes.get('access') || '-',
    uniqueness: attributes.get('uniqueness') || '-',
    status: attributes.get('status') || '-',
    visibility: attributes.get('visibility') || '-',
    logging: attributes.get('logging') || '-',
    stats: stats || '-'
  }
}

function unwrapAttribute(value?: string) {
  if (!value) return ''
  const trimmed = value.trim()
  return trimmed.startsWith('(') && trimmed.endsWith(')')
    ? trimmed.slice(1, -1)
    : trimmed
}

function routeConnectionId() {
  const raw = routeStringParam('connectionId')
  if (!raw) return null
  const parsed = Number(raw)
  return Number.isFinite(parsed) ? parsed : null
}

function routeStringParam(name: string) {
  const value = route.query[name]
  if (Array.isArray(value)) return value[0] || ''
  return typeof value === 'string' ? value : ''
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

async function loadQuestionsForSelected() {
  tuningQuestion.value = ''
  tuningQuestions.value = []
  if (!selectedResult.value?.tuningId) return
  isLoadingQuestions.value = true
  try {
    tuningQuestions.value = await getSqlTuningQuestions(selectedResult.value.tuningId)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '이전 질문을 불러오지 못했습니다.'
  } finally {
    isLoadingQuestions.value = false
  }
}

async function askQuestion() {
  if (!selectedResult.value?.tuningId || !canAskTuningQuestion.value || isAskingQuestion.value) return
  isAskingQuestion.value = true
  errorMessage.value = ''
  try {
    const answer = await askSqlTuningQuestion(selectedResult.value.tuningId, {
      question: tuningQuestion.value.trim()
    })
    tuningQuestions.value = [...tuningQuestions.value, answer]
    tuningQuestion.value = ''
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '추가 질문에 대한 답변을 생성하지 못했습니다.'
  } finally {
    isAskingQuestion.value = false
  }
}

function renderMarkdown(value?: string | null) {
  if (!value) return ''
  const html: string[] = []
  const paragraphLines: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []
  let codeLines: string[] | null = null

  const flushParagraph = () => {
    if (!paragraphLines.length) return
    html.push(`<p>${renderInlineMarkdown(paragraphLines.join(' '))}</p>`)
    paragraphLines.length = 0
  }
  const flushList = () => {
    if (!listType || !listItems.length) return
    html.push(`<${listType}>${listItems.map((item) => `<li>${item}</li>`).join('')}</${listType}>`)
    listType = null
    listItems = []
  }
  const pushListItem = (type: 'ul' | 'ol', text: string) => {
    flushParagraph()
    if (listType && listType !== type) {
      flushList()
    }
    listType = type
    listItems.push(renderInlineMarkdown(text))
  }

  value.split(/\r?\n/).forEach((line) => {
    const fenceMatch = line.match(/^```/)
    if (fenceMatch) {
      if (codeLines) {
        html.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
        codeLines = null
      } else {
        flushParagraph()
        flushList()
        codeLines = []
      }
      return
    }
    if (codeLines) {
      codeLines.push(line)
      return
    }

    const trimmed = line.trim()
    if (!trimmed) {
      flushParagraph()
      flushList()
      return
    }
    const heading = trimmed.match(/^(#{1,6})\s+(.+)$/)
    if (heading) {
      flushParagraph()
      flushList()
      html.push(`<h4>${renderInlineMarkdown(heading[2])}</h4>`)
      return
    }
    const bullet = trimmed.match(/^[-*]\s+(.+)$/)
    if (bullet) {
      pushListItem('ul', bullet[1])
      return
    }
    const numbered = trimmed.match(/^\d+[.)]\s+(.+)$/)
    if (numbered) {
      pushListItem('ol', numbered[1])
      return
    }
    flushList()
    paragraphLines.push(trimmed)
  })

  if (codeLines) {
    html.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
  }
  flushParagraph()
  flushList()
  return html.join('')
}

function renderInlineMarkdown(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
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
    errorMessage.value = error instanceof Error ? error.message : 'DDL 복사에 실패했습니다.'
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

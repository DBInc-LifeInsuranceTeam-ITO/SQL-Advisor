<template>
  <div class="awr-page awr-chat-page awr-chat-pro-page">
    <div class="awr-upload-hero awr-chat-hero awr-chat-pro-hero">
      <div>
        <p class="awr-upload-eyebrow">AWR Report Analysis</p>
        <h1 class="awr-main-title">AI 리포트 분석</h1>
        <p>
          선택한 AWR 리포트의 SQL 지표, Wait Event, Section 근거를 기반으로
          병목 원인과 튜닝 방향을 질의할 수 있습니다.
        </p>
      </div>
    </div>

    <div class="awr-chat-pro-layout">
      <aside class="awr-chat-pro-sidebar">
        <section class="awr-panel awr-chat-pro-card awr-chat-target-card">
          <div class="awr-chat-card-head">
            <div>
              <span class="awr-chat-card-eyebrow">분석 대상</span>
              <h2>리포트 선택</h2>
            </div>

            <span v-if="selectedReport" class="awr-badge ok">#{{ selectedReport.id }}</span>
          </div>

          <div class="awr-chat-report-select-box">
            <label class="awr-chat-field-label" for="report-select">AWR 리포트</label>

            <div class="awr-chat-select-wrap">
              <select
                id="report-select"
                v-model.number="selectedReportId"
                class="awr-input awr-chat-select"
              >
                <option :value="0">리포트 선택</option>
                <option v-for="report in reports" :key="report.id" :value="report.id">
                  #{{ report.id }} {{ report.filename }}
                </option>
              </select>

              <span class="awr-chat-select-arrow">▾</span>
            </div>
          </div>

          <div v-if="selectedReport" class="awr-chat-report-summary">
            <div class="awr-chat-report-main">
              <span>파일명</span>
              <strong :title="selectedReport.filename">
                {{ selectedReport.filename }}
              </strong>
            </div>

            <div class="awr-chat-report-meta-grid">
              <div>
                <span>DB</span>
                <strong :title="normalizeValue(selectedReport.dbName)">
                  {{ normalizeValue(selectedReport.dbName) }}
                </strong>
              </div>

              <div>
                <span>Instance</span>
                <strong :title="normalizeValue(selectedReport.instanceName)">
                  {{ normalizeValue(selectedReport.instanceName) }}
                </strong>
              </div>
            </div>

            <div class="awr-chat-report-main">
              <span>분석 구간</span>
              <strong>
                {{ formatSnapshotText(selectedReport.snapBegin) }}
                ~
                {{ formatSnapshotText(selectedReport.snapEnd) }}
              </strong>
            </div>
          </div>

          <div v-else class="awr-chat-target-empty">
            분석할 AWR 리포트를 선택하세요.
          </div>
        </section>

        <section class="awr-panel awr-chat-pro-card awr-chat-history-card">
          <div class="awr-chat-card-head">
            <div>
              <span class="awr-chat-card-eyebrow">이전 질의</span>
              <h2>채팅 히스토리</h2>
            </div>

            <span class="awr-badge">{{ chatHistory.length }}</span>
          </div>

          <div v-if="isLoadingHistory" class="awr-empty compact">
            히스토리를 불러오는 중입니다.
          </div>

          <div v-else-if="!selectedReportId" class="awr-empty compact">
            리포트를 선택하면 히스토리가 표시됩니다.
          </div>

          <div v-else-if="chatHistory.length === 0" class="awr-empty compact">
            아직 저장된 채팅이 없습니다.
          </div>

          <ul v-else class="awr-chat-history-list">
            <li v-for="item in chatHistory" :key="item.chatId">
              <button
                :class="['awr-chat-history-item', { active: selectedHistoryId === item.chatId }]"
                type="button"
                @click="selectHistory(item)"
              >
                <span class="awr-chat-history-question">{{ item.question }}</span>
                <span class="awr-chat-history-meta">
                  {{ formatDateTime(item.createdAt) }}
                  <span v-if="item.model"> · {{ item.model }}</span>
                </span>
              </button>
            </li>
          </ul>
        </section>
      </aside>

      <main class="awr-chat-pro-main">
        <section class="awr-panel awr-chat-pro-card awr-chat-command-card">
          <div class="awr-chat-command-head">
            <div>
              <span class="awr-chat-card-eyebrow">AI 질의</span>
              <h2>질문하기</h2>
              <p>
                리포트 내용 기준으로 병목 원인, 우선순위, 조치 방향을 질문할 수 있습니다.
              </p>
            </div>

            <span v-if="answer" class="awr-status-chip done">답변 생성 완료</span>
          </div>

          <div class="awr-chat-prompt-chip-row">
            <button
              v-for="prompt in prompts"
              :key="prompt.label"
              class="awr-chat-prompt-chip"
              type="button"
              :disabled="!selectedReportId"
              @click="question = prompt.question"
            >
              {{ prompt.label }}
            </button>
          </div>

          <div class="awr-chat-question-box pro">
            <textarea
              v-model="question"
              class="awr-textarea awr-chat-question-textarea pro"
              placeholder="예: 이 AWR에서 제일 먼저 봐야 할 SQL은?"
            ></textarea>

            <div class="awr-chat-submit-row pro">
              <p class="awr-chat-helper">
                선택한 리포트의 SQL metric, Wait Event, Section 근거만 사용해 답변합니다.
              </p>

              <button
                class="awr-btn primary awr-chat-submit"
                type="button"
                :disabled="!canAsk || isAsking"
                @click="ask"
              >
                {{ isAsking ? '답변 생성 중' : '질문하기' }}
              </button>
            </div>
          </div>
        </section>

        <div v-if="errorMessage" class="awr-empty awr-chat-error">
          {{ errorMessage }}
        </div>

        <section
          v-if="!answer && !errorMessage"
          class="awr-panel awr-chat-pro-card awr-chat-ready-state"
        >
          <div class="awr-chat-ready-emoji">💬</div>

          <div class="awr-chat-ready-copy">
            <h3>질문을 입력하면 AI 분석 결과가 표시됩니다.</h3>
            <p>
              선택한 AWR 리포트를 기준으로 우선 확인 대상, 병목 원인, 조치 방향을 정리합니다.
            </p>
          </div>
        </section>

        <template v-if="answer">
          <section class="awr-panel awr-chat-pro-card awr-chat-answer-section pro">
            <div class="awr-chat-answer-header pro">
              <div>
                <span class="awr-chat-answer-label">AI Answer</span>
                <h3>{{ answer.question }}</h3>
              </div>

              <span class="awr-badge ok">confidence {{ answer.confidence }}</span>
            </div>

            <div class="awr-answer markdown-answer awr-chat-answer pro">
              <template v-for="(block, blockIndex) in answerBlocks" :key="blockIndex">
                <component
                  :is="block.level <= 2 ? 'h3' : 'h4'"
                  v-if="block.type === 'heading'"
                  class="md-heading"
                >
                  <template v-for="(segment, segmentIndex) in block.content" :key="segmentIndex">
                    <code v-if="segment.type === 'code'">{{ segment.text }}</code>
                    <strong v-else-if="segment.type === 'strong'">{{ segment.text }}</strong>
                    <span v-else>{{ segment.text }}</span>
                  </template>
                </component>

                <p v-else-if="block.type === 'paragraph'" class="md-paragraph">
                  <template v-for="(segment, segmentIndex) in block.content" :key="segmentIndex">
                    <code v-if="segment.type === 'code'">{{ segment.text }}</code>
                    <strong v-else-if="segment.type === 'strong'">{{ segment.text }}</strong>
                    <span v-else>{{ segment.text }}</span>
                  </template>
                </p>

                <blockquote v-else-if="block.type === 'quote'" class="md-quote">
                  <template v-for="(segment, segmentIndex) in block.content" :key="segmentIndex">
                    <code v-if="segment.type === 'code'">{{ segment.text }}</code>
                    <strong v-else-if="segment.type === 'strong'">{{ segment.text }}</strong>
                    <span v-else>{{ segment.text }}</span>
                  </template>
                </blockquote>

                <ol v-else-if="block.type === 'list' && block.ordered" class="md-list">
                  <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
                    <template v-for="(segment, segmentIndex) in item" :key="segmentIndex">
                      <code v-if="segment.type === 'code'">{{ segment.text }}</code>
                      <strong v-else-if="segment.type === 'strong'">{{ segment.text }}</strong>
                      <span v-else>{{ segment.text }}</span>
                    </template>
                  </li>
                </ol>

                <ul v-else-if="block.type === 'list'" class="md-list">
                  <li v-for="(item, itemIndex) in block.items" :key="itemIndex">
                    <template v-for="(segment, segmentIndex) in item" :key="segmentIndex">
                      <code v-if="segment.type === 'code'">{{ segment.text }}</code>
                      <strong v-else-if="segment.type === 'strong'">{{ segment.text }}</strong>
                      <span v-else>{{ segment.text }}</span>
                    </template>
                  </li>
                </ul>

                <pre v-else-if="block.type === 'code'" class="md-code"><code>{{ block.text }}</code></pre>
              </template>
            </div>
          </section>

          <section
            v-if="answer.citations.length"
            class="awr-panel awr-chat-pro-card awr-chat-evidence-card pro"
          >
            <div class="awr-chat-section-head">
              <div>
                <span class="awr-chat-card-eyebrow">답변 근거</span>
                <h3>답변에 사용된 리포트 구간</h3>
              </div>

              <span class="awr-badge small">{{ answer.citations.length }}</span>
            </div>

            <ul class="awr-chat-citation-list pro">
              <li v-for="citation in answer.citations" :key="citation">
                {{ formatCitation(citation) }}
              </li>
            </ul>
          </section>

          <section
            v-if="answer.evidenceSql.length"
            class="awr-panel awr-chat-pro-card awr-chat-evidence-card pro"
          >
            <div class="awr-chat-section-head">
              <div>
                <span class="awr-chat-card-eyebrow">SQL 근거</span>
                <h3>근거 SQL</h3>
              </div>

              <span class="awr-badge small">{{ answer.evidenceSql.length }}건</span>
            </div>

            <div class="awr-table-wrap awr-chat-table-wrap">
              <table class="awr-table awr-report-list-table">
                <thead>
                  <tr>
                    <th>SQL_ID</th>
                    <th>Section</th>
                    <th>Elapsed</th>
                    <th>CPU</th>
                    <th>Gets</th>
                    <th>Reads</th>
                    <th>Execs</th>
                  </tr>
                </thead>

                <tbody>
                  <tr
                    v-for="metric in answer.evidenceSql"
                    :key="`${metric.sectionName}-${metric.sqlId}-${metric.rankNo}`"
                  >
                    <td>
                      <span class="awr-chat-sql-id">{{ metric.sqlId }}</span>
                    </td>
                    <td>{{ metric.sectionName }}</td>
                    <td>{{ formatNumber(metric.elapsedTimeSec) }}</td>
                    <td>{{ formatNumber(metric.cpuTimeSec) }}</td>
                    <td>{{ formatNumber(metric.bufferGets) }}</td>
                    <td>{{ formatNumber(metric.diskReads) }}</td>
                    <td>{{ formatNumber(metric.executions) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section
            v-if="answer.evidenceWaitEvents.length"
            class="awr-panel awr-chat-pro-card awr-chat-evidence-card pro"
          >
            <div class="awr-chat-section-head">
              <div>
                <span class="awr-chat-card-eyebrow">Wait Event 근거</span>
                <h3>근거 Wait Event</h3>
              </div>

              <span class="awr-badge small">{{ answer.evidenceWaitEvents.length }}건</span>
            </div>

            <div class="awr-table-wrap awr-chat-table-wrap">
              <table class="awr-table awr-report-list-table">
                <thead>
                  <tr>
                    <th>Wait Class</th>
                    <th>Event Name</th>
                    <th>Total Wait Sec</th>
                    <th>Avg Wait ms</th>
                    <th>DB Time %</th>
                  </tr>
                </thead>

                <tbody>
                  <tr
                    v-for="event in answer.evidenceWaitEvents"
                    :key="`${event.waitClass}-${event.eventName}`"
                  >
                    <td>{{ event.waitClass }}</td>
                    <td>{{ event.eventName }}</td>
                    <td>{{ formatNumber(event.totalWaitTimeSec) }}</td>
                    <td>{{ formatNumber(event.avgWaitMs) }}</td>
                    <td>{{ formatNumber(event.dbTimePercent) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { chatWithAwr, getAwrChatHistory, getAwrReports } from '@/api/awr'
import type { ChatHistoryResponse, ChatResponse, ReportSummaryResponse } from '@/types/awr'

type MarkdownSegment = {
  type: 'text' | 'strong' | 'code'
  text: string
}

type MarkdownBlock =
  | { type: 'heading'; level: number; content: MarkdownSegment[] }
  | { type: 'paragraph'; content: MarkdownSegment[] }
  | { type: 'quote'; content: MarkdownSegment[] }
  | { type: 'list'; ordered: boolean; items: MarkdownSegment[][] }
  | { type: 'code'; language: string; text: string }

type PromptTemplate = {
  label: string
  question: string
}

const route = useRoute()

const reports = ref<ReportSummaryResponse[]>([])
const selectedReportId = ref(0)
const question = ref('')
const answer = ref<ChatResponse | null>(null)
const chatHistory = ref<ChatHistoryResponse[]>([])
const selectedHistoryId = ref<number | null>(null)
const isAsking = ref(false)
const isLoadingHistory = ref(false)
const errorMessage = ref('')

const prompts: PromptTemplate[] = [
  {
    label: '우선 SQL',
    question: '이 AWR에서 제일 먼저 봐야 할 SQL은?'
  },
  {
    label: 'CPU/I/O 병목',
    question: 'CPU 병목인지 I/O 병목인지 판단해줘'
  },
  {
    label: 'Wait Event',
    question: 'Top Wait Event 기준으로 원인을 설명해줘'
  },
  {
    label: 'DB Time 병목',
    question: 'DB Time 기준으로 가장 의심되는 병목을 정리해줘'
  },
  {
    label: '운영 조치 순서',
    question: '운영 담당자가 바로 확인해야 할 조치 순서를 알려줘'
  }
]

const selectedReport = computed(() => {
  return reports.value.find((report) => report.id === selectedReportId.value) || null
})

const canAsk = computed(() => {
  return selectedReportId.value > 0 && question.value.trim().length > 0
})

const answerBlocks = computed(() => {
  return parseMarkdown(answer.value?.answer || '')
})

onMounted(async () => {
  try {
    reports.value = await getAwrReports()

    const routeReportId = Number(route.query.reportId || route.params.id || 0)

    selectedReportId.value = routeReportId || reports.value[0]?.id || 0
    question.value = String(route.query.question || prompts[0].question)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '리포트 목록을 불러오지 못했습니다.'
  }
})

watch(selectedReportId, () => {
  answer.value = null
  selectedHistoryId.value = null
  void loadChatHistory()
})

async function ask() {
  if (!canAsk.value) return

  isAsking.value = true
  errorMessage.value = ''

  try {
    answer.value = await chatWithAwr(selectedReportId.value, question.value.trim())
    await loadChatHistory()
    selectedHistoryId.value = chatHistory.value[0]?.chatId || null
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '질의응답에 실패했습니다.'
  } finally {
    isAsking.value = false
  }
}

async function loadChatHistory() {
  if (!selectedReportId.value) {
    chatHistory.value = []
    return
  }

  isLoadingHistory.value = true

  try {
    chatHistory.value = await getAwrChatHistory(selectedReportId.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '채팅 히스토리를 불러오지 못했습니다.'
  } finally {
    isLoadingHistory.value = false
  }
}

function selectHistory(item: ChatHistoryResponse) {
  selectedHistoryId.value = item.chatId
  question.value = item.question

  answer.value = {
    reportId: item.reportId,
    question: item.question,
    answer: item.answer,
    citations: item.citations || [],
    evidenceSql: item.evidenceSql || [],
    evidenceWaitEvents: item.evidenceWaitEvents || [],
    confidence: item.confidence
  }
}

function normalizeValue(value?: string | null) {
  const trimmed = value?.trim()

  if (!trimmed || trimmed.toUpperCase() === 'UNKNOWN') {
    return '미확인'
  }

  return trimmed
}

function formatSnapshotText(value?: string | null) {
  if (!value) return '-'

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    return value
  }

  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) return '-'

  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: 2
  }).format(value)
}

function formatDateTime(value?: string) {
  if (!value) return '-'

  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(new Date(value))
}

function formatCitation(value: string) {
  return value
    .replace(/\s*\/\s*chunk\s+\d+/gi, '')
    .replace(/_/g, ' ')
    .trim()
}

function parseMarkdown(value: string): MarkdownBlock[] {
  const lines = value.replace(/\r\n/g, '\n').split('\n')
  const blocks: MarkdownBlock[] = []

  let paragraphLines: string[] = []
  let currentList: { ordered: boolean; items: MarkdownSegment[][] } | null = null
  let codeLanguage = ''
  let codeLines: string[] | null = null

  function flushParagraph() {
    if (paragraphLines.length === 0) return

    blocks.push({
      type: 'paragraph',
      content: parseInline(paragraphLines.join(' ').trim())
    })

    paragraphLines = []
  }

  function flushList() {
    if (!currentList) return

    blocks.push({
      type: 'list',
      ordered: currentList.ordered,
      items: currentList.items
    })

    currentList = null
  }

  function flushCode() {
    if (!codeLines) return

    blocks.push({
      type: 'code',
      language: codeLanguage,
      text: codeLines.join('\n')
    })

    codeLanguage = ''
    codeLines = null
  }

  for (const line of lines) {
    const trimmed = line.trim()

    if (codeLines) {
      if (trimmed.startsWith('```')) {
        flushCode()
      } else {
        codeLines.push(line)
      }

      continue
    }

    const codeMatch = trimmed.match(/^```(\S*)/)
    if (codeMatch) {
      flushParagraph()
      flushList()
      codeLanguage = codeMatch[1] || ''
      codeLines = []
      continue
    }

    if (!trimmed) {
      flushParagraph()
      flushList()
      continue
    }

    const headingMatch = trimmed.match(/^(#{1,4})\s+(.+)$/)
    if (headingMatch) {
      flushParagraph()
      flushList()

      blocks.push({
        type: 'heading',
        level: headingMatch[1].length,
        content: parseInline(headingMatch[2])
      })

      continue
    }

    const quoteMatch = trimmed.match(/^>\s?(.+)$/)
    if (quoteMatch) {
      flushParagraph()
      flushList()

      blocks.push({
        type: 'quote',
        content: parseInline(quoteMatch[1])
      })

      continue
    }

    const unorderedMatch = trimmed.match(/^[-*+]\s+(.+)$/)
    const orderedMatch = trimmed.match(/^\d+[.)]\s+(.+)$/)
    const listText = unorderedMatch?.[1] || orderedMatch?.[1]

    if (listText) {
      flushParagraph()

      const ordered = Boolean(orderedMatch)

      if (!currentList || currentList.ordered !== ordered) {
        flushList()
        currentList = { ordered, items: [] }
      }

      currentList.items.push(parseInline(listText))
      continue
    }

    flushList()
    paragraphLines.push(trimmed)
  }

  flushCode()
  flushParagraph()
  flushList()

  return blocks
}

function parseInline(value: string): MarkdownSegment[] {
  const segments: MarkdownSegment[] = []
  const pattern = /(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__)/g

  let lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = pattern.exec(value)) !== null) {
    if (match.index > lastIndex) {
      segments.push({
        type: 'text',
        text: value.slice(lastIndex, match.index)
      })
    }

    const token = match[0]

    if (token.startsWith('`')) {
      segments.push({
        type: 'code',
        text: token.slice(1, -1)
      })
    } else {
      segments.push({
        type: 'strong',
        text: token.slice(2, -2)
      })
    }

    lastIndex = match.index + token.length
  }

  if (lastIndex < value.length) {
    segments.push({
      type: 'text',
      text: value.slice(lastIndex)
    })
  }

  return segments.length ? segments : [{ type: 'text', text: value }]
}
</script>

<style src="./awr.css"></style>

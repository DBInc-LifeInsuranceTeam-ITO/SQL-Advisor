<template>
  <div class="awr-page awr-chat-page">
    <section class="awr-chat-card">
      <header class="awr-chat-header">
        <div class="awr-chat-heading">
          <div>
            <p class="awr-chat-eyebrow">AWR AI 분석</p>
            <h1>AI 리포트 분석</h1>
          </div>

          <div class="awr-chat-report-picker">
            <label for="awr-report-select">분석 리포트</label>

            <div class="awr-chat-select-wrap">
              <select
                id="awr-report-select"
                v-model.number="selectedReportId"
                class="awr-chat-select-native"
              >
                <option :value="0">리포트를 선택하세요</option>
                <option
                  v-for="report in reports"
                  :key="report.id"
                  :value="report.id"
                >
                  #{{ report.id }} {{ report.filename }}
                </option>
              </select>

              <div class="awr-chat-selected-report">
                <div class="awr-chat-selected-report-text">
                  <span v-if="selectedReport" class="awr-chat-selected-id">
                    #{{ selectedReport.id }}
                  </span>

                  <strong>
                    {{
                      selectedReport
                        ? selectedReport.filename
                        : '리포트를 선택하세요'
                    }}
                  </strong>
                </div>

                <span class="awr-chat-select-chevron">▾</span>
              </div>
            </div>
          </div>
        </div>

        <div class="awr-chat-prompts">
          <span>추천 질문</span>
          <button
            v-for="prompt in prompts"
            :key="prompt.label"
            type="button"
            :disabled="!selectedReportId"
            @click="question = prompt.question"
          >
            {{ prompt.label }}
          </button>
        </div>
      </header>

      <div ref="conversationRef" class="awr-chat-body">
        <div v-if="errorMessage" class="awr-empty awr-chat-error">
          {{ errorMessage }}
        </div>

        <div v-else-if="messages.length === 0 && !isAsking" class="awr-chat-empty">
          <p>질문을 입력하면 선택한 AWR 리포트를 기준으로 분석합니다.</p>
        </div>

        <template
          v-for="message in messages"
          :key="message.localId"
        >
          <div class="awr-chat-message user">
            <div class="awr-chat-avatar user">Q</div>

            <div class="awr-chat-bubble user">
              <span class="awr-chat-message-label">질문</span>
              <p>{{ message.question }}</p>
            </div>
          </div>

          <div class="awr-chat-message assistant">
            <div class="awr-chat-avatar assistant">AI</div>

            <div class="awr-chat-bubble assistant">
              <div class="awr-chat-answer">
                <template
                  v-for="(block, blockIndex) in parseMarkdown(message.answer)"
                  :key="blockIndex"
                >
                  <component
                    :is="block.level <= 2 ? 'h3' : 'h4'"
                    v-if="block.type === 'heading'"
                    class="md-heading"
                  >
                    <template
                      v-for="(segment, segmentIndex) in block.content"
                      :key="segmentIndex"
                    >
                      <code v-if="segment.type === 'code'">
                        {{ segment.text }}
                      </code>
                      <strong v-else-if="segment.type === 'strong'">
                        {{ segment.text }}
                      </strong>
                      <span v-else>{{ segment.text }}</span>
                    </template>
                  </component>

                  <p
                    v-else-if="block.type === 'paragraph'"
                    class="md-paragraph"
                  >
                    <template
                      v-for="(segment, segmentIndex) in block.content"
                      :key="segmentIndex"
                    >
                      <code v-if="segment.type === 'code'">
                        {{ segment.text }}
                      </code>
                      <strong v-else-if="segment.type === 'strong'">
                        {{ segment.text }}
                      </strong>
                      <span v-else>{{ segment.text }}</span>
                    </template>
                  </p>

                  <blockquote
                    v-else-if="block.type === 'quote'"
                    class="md-quote"
                  >
                    <template
                      v-for="(segment, segmentIndex) in block.content"
                      :key="segmentIndex"
                    >
                      <code v-if="segment.type === 'code'">
                        {{ segment.text }}
                      </code>
                      <strong v-else-if="segment.type === 'strong'">
                        {{ segment.text }}
                      </strong>
                      <span v-else>{{ segment.text }}</span>
                    </template>
                  </blockquote>

                  <ol
                    v-else-if="block.type === 'list' && block.ordered"
                    class="md-list"
                  >
                    <li
                      v-for="(item, itemIndex) in block.items"
                      :key="itemIndex"
                    >
                      <template
                        v-for="(segment, segmentIndex) in item"
                        :key="segmentIndex"
                      >
                        <code v-if="segment.type === 'code'">
                          {{ segment.text }}
                        </code>
                        <strong v-else-if="segment.type === 'strong'">
                          {{ segment.text }}
                        </strong>
                        <span v-else>{{ segment.text }}</span>
                      </template>
                    </li>
                  </ol>

                  <ul
                    v-else-if="block.type === 'list'"
                    class="md-list"
                  >
                    <li
                      v-for="(item, itemIndex) in block.items"
                      :key="itemIndex"
                    >
                      <template
                        v-for="(segment, segmentIndex) in item"
                        :key="segmentIndex"
                      >
                        <code v-if="segment.type === 'code'">
                          {{ segment.text }}
                        </code>
                        <strong v-else-if="segment.type === 'strong'">
                          {{ segment.text }}
                        </strong>
                        <span v-else>{{ segment.text }}</span>
                      </template>
                    </li>
                  </ul>

                  <pre
                    v-else-if="block.type === 'code'"
                    class="md-code"
                  ><code>{{ block.text }}</code></pre>
                </template>
              </div>

              <div
                v-if="
                  message.citations.length ||
                  message.evidenceSql.length ||
                  message.evidenceWaitEvents.length
                "
                class="awr-chat-evidence"
              >
                <details v-if="message.citations.length">
                  <summary>
                    답변 근거
                    <span>{{ message.citations.length }}건</span>
                  </summary>

                  <ul class="awr-chat-citation-list">
                    <li
                      v-for="citation in message.citations"
                      :key="citation"
                    >
                      {{ formatCitation(citation) }}
                    </li>
                  </ul>
                </details>

                <details v-if="message.evidenceSql.length">
                  <summary>
                    근거 SQL
                    <span>{{ message.evidenceSql.length }}건</span>
                  </summary>

                  <div class="awr-table-wrap awr-chat-table-wrap">
                    <table class="awr-table">
                      <thead>
                        <tr>
                          <th>SQL_ID</th>
                          <th>기준 항목</th>
                          <th>수행시간</th>
                          <th>CPU</th>
                          <th>Buffer Gets</th>
                          <th>Disk Reads</th>
                          <th>실행 횟수</th>
                        </tr>
                      </thead>

                      <tbody>
                        <tr
                          v-for="metric in message.evidenceSql"
                          :key="`${metric.sectionName}-${metric.sqlId}-${metric.rankNo}`"
                        >
                          <td>{{ metric.sqlId }}</td>
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
                </details>

                <details v-if="message.evidenceWaitEvents.length">
                  <summary>
                    근거 대기 이벤트
                    <span>{{ message.evidenceWaitEvents.length }}건</span>
                  </summary>

                  <div class="awr-table-wrap awr-chat-table-wrap">
                    <table class="awr-table">
                      <thead>
                        <tr>
                          <th>대기 클래스</th>
                          <th>이벤트</th>
                          <th>총 대기시간</th>
                          <th>평균 대기(ms)</th>
                          <th>DB Time 비율</th>
                        </tr>
                      </thead>

                      <tbody>
                        <tr
                          v-for="event in message.evidenceWaitEvents"
                          :key="`${event.waitClass}-${event.eventName}`"
                        >
                          <td>{{ event.waitClass }}</td>
                          <td>{{ event.eventName }}</td>
                          <td>{{ formatNumber(event.totalWaitTimeSec) }}</td>
                          <td>{{ formatNumber(event.avgWaitMs) }}</td>
                          <td>{{ formatNumber(event.dbTimePercent) }}%</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </details>
              </div>

              <div class="awr-chat-answer-meta">
                <span v-if="message.createdAt">
                  {{ formatDateTime(message.createdAt) }}
                </span>
                <span v-if="message.model">
                  {{ message.model }}
                </span>
              </div>
            </div>
          </div>
        </template>

        <div v-if="isAsking" class="awr-chat-loading-message">
          <div class="awr-chat-avatar assistant">AI</div>

          <div class="awr-chat-loading-bubble">
            <span>AWR 리포트 분석 중</span>
            <div class="awr-chat-loading-dots" aria-label="분석 중">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </div>
        </div>
      </div>

      <footer class="awr-chat-footer">
        <div class="awr-chat-composer">
          <textarea
            v-model="question"
            placeholder="AWR 리포트에 대해 질문하세요."
            @keydown.enter.exact.prevent="ask"
          ></textarea>

          <button
            class="awr-chat-send-button"
            type="button"
            :disabled="!canAsk || isAsking"
            :aria-label="isAsking ? '분석 중' : '질문 전송'"
            @click="ask"
          >
            <span v-if="isAsking" class="awr-chat-send-spinner"></span>
            <span v-else class="awr-chat-send-arrow">↑</span>
          </button>
        </div>

        <details class="awr-chat-history">
          <summary>
            이전 질문
            <span>{{ chatHistory.length }}</span>
          </summary>

          <div v-if="isLoadingHistory" class="awr-empty compact">
            이전 질문을 불러오는 중입니다.
          </div>

          <div
            v-else-if="!selectedReportId"
            class="awr-empty compact"
          >
            리포트를 선택하면 이전 질문이 표시됩니다.
          </div>

          <div
            v-else-if="chatHistory.length === 0"
            class="awr-empty compact"
          >
            저장된 질문이 없습니다.
          </div>

          <ul v-else>
            <li
              v-for="item in chatHistory"
              :key="item.chatId"
            >
              <button
                :class="{ active: selectedHistoryId === item.chatId }"
                type="button"
                @click="selectHistory(item)"
              >
                <strong>{{ item.question }}</strong>
                <span>
                  {{ formatDateTime(item.createdAt) }}
                  <template v-if="item.model">
                    · {{ item.model }}
                  </template>
                </span>
              </button>
            </li>
          </ul>
        </details>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  chatWithAwr,
  getAwrChatHistory,
  getAwrReports
} from '@/api/awr'
import type {
  ChatHistoryResponse,
  ChatResponse,
  ReportSummaryResponse
} from '@/types/awr'

type MarkdownSegment = {
  type: 'text' | 'strong' | 'code'
  text: string
}

type MarkdownBlock =
  | {
      type: 'heading'
      level: number
      content: MarkdownSegment[]
    }
  | {
      type: 'paragraph'
      content: MarkdownSegment[]
    }
  | {
      type: 'quote'
      content: MarkdownSegment[]
    }
  | {
      type: 'list'
      ordered: boolean
      items: MarkdownSegment[][]
    }
  | {
      type: 'code'
      language: string
      text: string
    }

type PromptTemplate = {
  label: string
  question: string
}

type ChatMessage = ChatResponse & {
  localId: string
  createdAt?: string
  model?: string
}

const route = useRoute()

const reports = ref<ReportSummaryResponse[]>([])
const selectedReportId = ref(0)
const question = ref('')
const messages = ref<ChatMessage[]>([])
const conversationRef = ref<HTMLElement | null>(null)
const chatHistory = ref<ChatHistoryResponse[]>([])
const selectedHistoryId = ref<number | null>(null)
const isAsking = ref(false)
const isLoadingHistory = ref(false)
const errorMessage = ref('')

const prompts: PromptTemplate[] = [
  {
    label: '우선 확인 SQL',
    question: '이 AWR에서 제일 먼저 봐야 할 SQL은?'
  },
  {
    label: 'CPU·I/O 병목',
    question: 'CPU 병목인지 I/O 병목인지 판단해줘'
  },
  {
    label: '대기 이벤트',
    question: 'Top Wait Event 기준으로 원인을 설명해줘'
  },
  {
    label: 'DB Time 분석',
    question: 'DB Time 기준으로 가장 의심되는 병목을 정리해줘'
  },
  {
    label: '운영 조치 순서',
    question: '운영 담당자가 바로 확인해야 할 조치 순서를 알려줘'
  }
]

const selectedReport = computed(() => {
  return (
    reports.value.find(
      (report) => report.id === selectedReportId.value
    ) || null
  )
})

const selectedHistory = computed(() => {
  return (
    chatHistory.value.find(
      (item) => item.chatId === selectedHistoryId.value
    ) || null
  )
})

const canAsk = computed(() => {
  return (
    selectedReportId.value > 0 &&
    question.value.trim().length > 0
  )
})



onMounted(async () => {
  try {
    reports.value = await getAwrReports()

    const routeReportId = Number(
      route.query.reportId ||
      route.params.id ||
      0
    )

    selectedReportId.value =
      routeReportId ||
      reports.value[0]?.id ||
      0

    question.value = String(
      route.query.question ||
      prompts[0].question
    )
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '리포트 목록을 불러오지 못했습니다.'
  }
})

watch(selectedReportId, () => {
  messages.value = []
  selectedHistoryId.value = null
  void loadChatHistory()
})

async function ask() {
  if (!canAsk.value || isAsking.value) {
    return
  }

  const submittedQuestion = question.value.trim()

  isAsking.value = true
  errorMessage.value = ''
  question.value = ''

  await scrollToBottom()

  try {
    const result = await chatWithAwr(
      selectedReportId.value,
      submittedQuestion
    )

    messages.value.push({
      ...result,
      localId: createLocalId(),
      createdAt: new Date().toISOString()
    })

    await loadChatHistory()

    selectedHistoryId.value =
      chatHistory.value[0]?.chatId ||
      null

    const latestHistory = chatHistory.value[0]

    if (latestHistory) {
      const latestMessage =
        messages.value[messages.value.length - 1]

      if (latestMessage) {
        latestMessage.createdAt = latestHistory.createdAt
        latestMessage.model = latestHistory.model
      }
    }
  } catch (error) {
    question.value = submittedQuestion

    errorMessage.value =
      error instanceof Error
        ? error.message
        : '질의응답에 실패했습니다.'
  } finally {
    isAsking.value = false
    await scrollToBottom()
  }
}

async function loadChatHistory() {
  if (!selectedReportId.value) {
    chatHistory.value = []
    return
  }

  isLoadingHistory.value = true

  try {
    chatHistory.value = await getAwrChatHistory(
      selectedReportId.value
    )
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '채팅 히스토리를 불러오지 못했습니다.'
  } finally {
    isLoadingHistory.value = false
  }
}

function selectHistory(item: ChatHistoryResponse) {
  selectedHistoryId.value = item.chatId
  question.value = ''

  messages.value = [
    {
      reportId: item.reportId,
      question: item.question,
      answer: item.answer,
      citations: item.citations || [],
      evidenceSql: item.evidenceSql || [],
      evidenceWaitEvents: item.evidenceWaitEvents || [],
      confidence: item.confidence,
      localId: `history-${item.chatId}`,
      createdAt: item.createdAt,
      model: item.model
    }
  ]

  void scrollToBottom()
}

async function scrollToBottom() {
  await nextTick()

  if (!conversationRef.value) {
    return
  }

  conversationRef.value.scrollTop =
    conversationRef.value.scrollHeight
}

function createLocalId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function normalizeValue(value?: string | null) {
  const trimmed = value?.trim()

  if (
    !trimmed ||
    trimmed.toUpperCase() === 'UNKNOWN'
  ) {
    return '미확인'
  }

  if (trimmed.length > 30) {
    return `${trimmed.slice(0, 27)}...`
  }

  return trimmed
}

function formatSnapshotText(value?: string | null) {
  if (!value) {
    return '-'
  }

  const date = new Date(value)

  if (Number.isNaN(date.getTime())) {
    const trimmed = value.trim()

    if (
      /^(time|snap time|end snap time)$/i.test(trimmed)
    ) {
      return '-'
    }

    return trimmed
  }

  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function formatNumber(value?: number | null) {
  if (
    value === null ||
    value === undefined
  ) {
    return '-'
  }

  return new Intl.NumberFormat('ko-KR', {
    maximumFractionDigits: 2
  }).format(value)
}

function formatDateTime(value?: string) {
  if (!value) {
    return '-'
  }

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
  const lines = value
    .replace(/\r\n/g, '\n')
    .split('\n')

  const blocks: MarkdownBlock[] = []

  let paragraphLines: string[] = []
  let currentList: {
    ordered: boolean
    items: MarkdownSegment[][]
  } | null = null

  let codeLanguage = ''
  let codeLines: string[] | null = null

  function flushParagraph() {
    if (paragraphLines.length === 0) {
      return
    }

    blocks.push({
      type: 'paragraph',
      content: parseInline(
        paragraphLines.join(' ').trim()
      )
    })

    paragraphLines = []
  }

  function flushList() {
    if (!currentList) {
      return
    }

    blocks.push({
      type: 'list',
      ordered: currentList.ordered,
      items: currentList.items
    })

    currentList = null
  }

  function flushCode() {
    if (!codeLines) {
      return
    }

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

    const headingMatch =
      trimmed.match(/^(#{1,4})\s+(.+)$/)

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

    const quoteMatch =
      trimmed.match(/^>\s?(.+)$/)

    if (quoteMatch) {
      flushParagraph()
      flushList()

      blocks.push({
        type: 'quote',
        content: parseInline(quoteMatch[1])
      })

      continue
    }

    const unorderedMatch =
      trimmed.match(/^[-*+]\s+(.+)$/)

    const orderedMatch =
      trimmed.match(/^\d+[.)]\s+(.+)$/)

    const listText =
      unorderedMatch?.[1] ||
      orderedMatch?.[1]

    if (listText) {
      flushParagraph()

      const ordered = Boolean(orderedMatch)

      if (
        !currentList ||
        currentList.ordered !== ordered
      ) {
        flushList()
        currentList = {
          ordered,
          items: []
        }
      }

      currentList.items.push(
        parseInline(listText)
      )

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

function parseInline(
  value: string
): MarkdownSegment[] {
  const segments: MarkdownSegment[] = []

  const pattern =
    /(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__)/g

  let lastIndex = 0
  let match: RegExpExecArray | null

  while (
    (match = pattern.exec(value)) !== null
  ) {
    if (match.index > lastIndex) {
      segments.push({
        type: 'text',
        text: value.slice(
          lastIndex,
          match.index
        )
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

    lastIndex =
      match.index + token.length
  }

  if (lastIndex < value.length) {
    segments.push({
      type: 'text',
      text: value.slice(lastIndex)
    })
  }

  return segments.length
    ? segments
    : [{ type: 'text', text: value }]
}
</script>

<style src="./awr.css"></style>

<style scoped>

.awr-chat-header,
.awr-chat-body {
  width: min(100%, 1180px);
  margin-right: auto;
  margin-left: auto;
}

.awr-chat-header + .awr-chat-body {
  margin-top: 1rem;
}
.awr-chat-page {
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 1rem 1.25rem;
}

.awr-chat-card {
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 2rem);
  overflow: visible;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}

.awr-chat-header {
  flex: 0 0 auto;
  padding: 0.9rem 1rem;
  border: 1px solid var(--awr-green-line);
  border-radius: var(--awr-radius-lg);
  background:
    linear-gradient(
      100deg,
      var(--awr-green-soft) 0%,
      #f3faf7 42%,
      var(--awr-bg) 100%
    );
  box-shadow: var(--awr-shadow-sm);
}

.awr-chat-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
}

.awr-chat-heading h1 {
  margin: 0;
  font-size: 1.75rem;
  font-weight: 900;
  line-height: 1.3;
  letter-spacing: -0.03em;
}

.awr-chat-eyebrow {
  margin: 0 0 0.2rem;
  color: #0a8f4d;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.06em;
}

.awr-chat-report-picker {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  width: min(100%, 34rem);
}

.awr-chat-report-picker label {
  flex: 0 0 auto;
  color: #5e6d7f;
  font-size: 0.78rem;
  font-weight: 700;
}

.awr-chat-select-wrap {
  position: relative;
  min-width: 0;
  flex: 1;
}

.awr-chat-select-native {
  position: absolute;
  inset: 0;
  z-index: 2;
  width: 100%;
  height: 100%;
  cursor: pointer;
  opacity: 0;
}

.awr-chat-selected-report {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  min-height: 2.7rem;
  padding: 0.42rem 0.55rem 0.42rem 0.7rem;
  border: 1px solid var(--awr-green-line);
  border-radius: 0.7rem;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: inset 0 0 0 1px rgba(10, 143, 77, 0.03);
}

.awr-chat-select-wrap:hover .awr-chat-selected-report {
  border-color: var(--awr-green-btn);
}

.awr-chat-select-wrap:focus-within .awr-chat-selected-report {
  border-color: var(--awr-green-btn);
  box-shadow: 0 0 0 0.2rem rgba(10, 143, 77, 0.1);
}

.awr-chat-selected-report-text {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  min-width: 0;
}

.awr-chat-selected-id {
  flex: 0 0 auto;
  padding: 0.18rem 0.42rem;
  border-radius: 999px;
  background: var(--awr-green-soft);
  color: var(--awr-green-dark);
  font-size: 0.72rem;
  font-weight: 900;
}

.awr-chat-selected-report strong {
  min-width: 0;
  overflow: hidden;
  color: #172437;
  font-size: 0.84rem;
  font-weight: 850;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.awr-chat-select-chevron {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.8rem;
  height: 1.8rem;
  flex: 0 0 auto;
  border-radius: 0.45rem;
  background: #f1f7f4;
  color: var(--awr-green-dark);
  font-size: 0.78rem;
  font-weight: 900;
}


.awr-chat-prompts {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  margin-top: 0.7rem;
  overflow-x: auto;
}

.awr-chat-prompts > span {
  flex: 0 0 auto;
  color: #0a8f4d;
  font-size: 0.76rem;
  font-weight: 800;
}

.awr-chat-prompts button {
  flex: 0 0 auto;
  padding: 0.38rem 0.68rem;
  border: 1px solid #d7e1e8;
  border-radius: 999px;
  background: #fff;
  color: #244036;
  font-size: 0.76rem;
  font-weight: 700;
  cursor: pointer;
}

.awr-chat-prompts button:hover:not(:disabled) {
  border-color: #0a8f4d;
  color: #087743;
}

.awr-chat-body {
  flex: 1 1 auto;
  min-height: 14rem;
  overflow-y: auto;
  margin-top: 1rem;
  padding: 1.25rem 1.4rem;
  border: 1px solid #dce4ec;
  border-radius: 0.85rem;
  background: #f8fafb;
}

.awr-chat-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 14rem;
  padding: 1rem;
  text-align: center;
}


.awr-chat-empty p {
  margin: 0;
  color: #7a8797;
  font-size: 0.82rem;
}

.awr-chat-loading-message {
  display: flex;
  align-items: flex-start;
  gap: 0.65rem;
  width: min(100%, 1180px);
  margin: 1.25rem auto 0;
}

.awr-chat-loading-bubble {
  display: inline-flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 0.95rem;
  border: 1px solid #dfe6ec;
  border-radius: 0.25rem 1rem 1rem 1rem;
  background: #fff;
  color: #415065;
  font-size: 0.82rem;
  font-weight: 700;
  box-shadow: 0 0.2rem 0.6rem rgba(25, 45, 70, 0.05);
}

.awr-chat-loading-dots {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
}

.awr-chat-loading-dots span {
  width: 0.4rem;
  height: 0.4rem;
  border-radius: 50%;
  background: #0a8f4d;
  animation: awr-chat-bounce 1.2s infinite ease-in-out;
}

.awr-chat-loading-dots span:nth-child(2) {
  animation-delay: 0.15s;
}

.awr-chat-loading-dots span:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes awr-chat-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }

  40% {
    transform: translateY(-0.25rem);
    opacity: 1;
  }
}

@keyframes awr-chat-spin {
  to {
    transform: rotate(360deg);
  }
}

.awr-chat-message {
  display: flex;
  align-items: flex-start;
  gap: 0.65rem;
  width: min(100%, 1180px);
  margin-right: auto;
  margin-left: auto;
}

.awr-chat-message + .awr-chat-message {
  margin-top: 1.25rem;
}

.awr-chat-message.user {
  justify-content: flex-end;
}

.awr-chat-message.user .awr-chat-avatar {
  order: 2;
}

.awr-chat-message.user .awr-chat-bubble {
  order: 1;
}

.awr-chat-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.1rem;
  height: 2.1rem;
  flex: 0 0 auto;
  border-radius: 50%;
  font-size: 0.72rem;
  font-weight: 900;
}

.awr-chat-avatar.user {
  background: #142238;
  color: #fff;
}

.awr-chat-avatar.assistant {
  background: #e7f7ee;
  color: #0a8f4d;
}

.awr-chat-bubble {
  min-width: 0;
}

.awr-chat-bubble.user {
  width: fit-content;
  min-width: 16rem;
  max-width: min(62%, 46rem);
  padding: 0.8rem 1rem;
  border-radius: 1rem 1rem 0.25rem 1rem;
  background: #142238;
  color: #fff;
}

.awr-chat-message-label {
  display: block;
  margin-bottom: 0.25rem;
  color: #aebbd0;
  font-size: 0.68rem;
  font-weight: 700;
}

.awr-chat-bubble.user p {
  margin: 0;
  line-height: 1.55;
  word-break: keep-all;
  overflow-wrap: break-word;
}

.awr-chat-bubble.assistant {
  width: calc(100% - 2.75rem);
  max-width: none;
  padding: 1rem 1.1rem;
  border: 1px solid #dfe6ec;
  border-radius: 0.25rem 1rem 1rem 1rem;
  background: #fff;
}



.awr-chat-answer {
  padding-top: 0;
  color: #243244;
  line-height: 1.75;
}

.awr-chat-answer .md-heading {
  margin: 1.15rem 0 0.5rem;
  color: #101b2d;
}

.awr-chat-answer .md-heading:first-child {
  margin-top: 0;
}

.awr-chat-answer .md-paragraph {
  margin: 0.65rem 0;
}

.awr-chat-answer .md-list {
  margin: 0.65rem 0;
  padding-left: 1.35rem;
}

.awr-chat-answer .md-quote {
  margin: 0.8rem 0;
  padding: 0.75rem 0.9rem;
  border-left: 0.25rem solid #0a8f4d;
  background: #f4f8f6;
}

.awr-chat-answer code {
  padding: 0.12rem 0.3rem;
  border-radius: 0.3rem;
  background: #eef2f5;
  font-size: 0.88em;
}

.awr-chat-answer .md-code {
  overflow-x: auto;
  padding: 0.85rem;
  border-radius: 0.6rem;
  background: #172437;
  color: #f3f6f8;
}

.awr-chat-evidence {
  display: grid;
  gap: 0.55rem;
  margin-top: 1rem;
}

.awr-chat-evidence details {
  border: 1px solid #e0e6eb;
  border-radius: 0.65rem;
  background: #fafcfd;
}

.awr-chat-evidence summary {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.72rem 0.85rem;
  cursor: pointer;
  font-weight: 800;
}

.awr-chat-evidence summary span {
  color: #718096;
  font-size: 0.74rem;
}

.awr-chat-citation-list {
  margin: 0;
  padding: 0 1.2rem 0.8rem 2rem;
}

.awr-chat-table-wrap {
  margin: 0 0.75rem 0.75rem;
}

.awr-chat-answer-meta {
  display: flex;
  justify-content: flex-end;
  gap: 0.6rem;
  margin-top: 0.7rem;
  color: #8a96a5;
  font-size: 0.7rem;
}

.awr-chat-footer {
  flex: 0 0 auto;
  width: min(100%, 1180px);
  margin: 1rem auto 0;
  padding: 0;
  border: 0;
  background: transparent;
}

.awr-chat-composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 2.4rem;
  gap: 0.65rem;
  align-items: end;
  padding: 0.85rem;
  border: 1px solid #dce4ec;
  border-radius: 0.85rem;
  background: #fff;
}

.awr-chat-composer textarea {
  width: 100%;
  min-height: 3.2rem;
  max-height: 8rem;
  resize: vertical;
  padding: 0.8rem 0.9rem;
  border: 1px solid #ccd7df;
  border-radius: 0.75rem;
  font: inherit;
  line-height: 1.45;
  outline: none;
}

.awr-chat-composer textarea:focus {
  border-color: #0a8f4d;
  box-shadow: 0 0 0 0.2rem rgba(10, 143, 77, 0.1);
}

.awr-chat-send-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.4rem;
  height: 2.4rem;
  margin-bottom: 0.28rem;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #0a8f4d;
  color: #fff;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    opacity 0.15s ease,
    background 0.15s ease;
}

.awr-chat-send-button:hover:not(:disabled) {
  transform: translateY(-1px);
  background: #087743;
}

.awr-chat-send-button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.awr-chat-send-arrow {
  font-size: 1rem;
  font-weight: 900;
  line-height: 1;
}

.awr-chat-send-spinner {
  width: 0.85rem;
  height: 0.85rem;
  border: 0.12rem solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: awr-chat-spin 0.8s linear infinite;
}

.awr-chat-history {
  margin-top: 0.65rem;
}

.awr-chat-history > summary {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  color: #59697a;
  font-size: 0.76rem;
  font-weight: 800;
  cursor: pointer;
}

.awr-chat-history > summary span {
  display: inline-flex;
  min-width: 1.25rem;
  height: 1.25rem;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: #edf3f6;
  font-size: 0.68rem;
}

.awr-chat-history ul {
  display: grid;
  gap: 0.45rem;
  max-height: 13rem;
  overflow-y: auto;
  margin: 0.65rem 0 0;
  padding: 0;
  list-style: none;
}

.awr-chat-history li button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  width: 100%;
  padding: 0.65rem 0.75rem;
  border: 1px solid #e0e6eb;
  border-radius: 0.55rem;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.awr-chat-history li button.active {
  border-color: #0a8f4d;
  background: #eff9f4;
}

.awr-chat-history li strong {
  min-width: 0;
  overflow: hidden;
  font-size: 0.8rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.awr-chat-history li span {
  flex: 0 0 auto;
  color: #7a8797;
  font-size: 0.68rem;
}

@media (max-width: 900px) {
  .awr-chat-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .awr-chat-report-picker {
    min-width: 0;
  }

  .awr-chat-bubble.user {
    max-width: 85%;
  }

  .awr-chat-body {
    padding: 1rem;
  }
}

@media (max-width: 640px) {
  .awr-chat-page {
    padding: 0.75rem;
  }

  .awr-chat-card {
    min-height: 100vh;
  }

  .awr-chat-report-picker {
    align-items: stretch;
    flex-direction: column;
  }

  .awr-chat-message {
    gap: 0.4rem;
  }

  .awr-chat-avatar {
    width: 1.8rem;
    height: 1.8rem;
  }

  .awr-chat-bubble.user {
    min-width: 0;
    max-width: calc(100% - 2.2rem);
  }

  .awr-chat-composer {
    grid-template-columns: minmax(0, 1fr) 2.4rem;
  }

  .awr-chat-send-button {
    width: 2.4rem;
    height: 2.4rem;
  }

  .awr-chat-history li button {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>

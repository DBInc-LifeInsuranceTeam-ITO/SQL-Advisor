<template>
  <div class="awr-page awr-chat-page awr-ai-chat-page">
    <div class="awr-upload-hero awr-ai-chat-hero">
      <div>
        <p class="awr-upload-eyebrow">AWR Report Analysis</p>
        <h1 class="awr-main-title">AI 리포트 분석</h1>
        <p>
          선택한 AWR 리포트를 기준으로 병목 원인, 우선순위, 운영 조치 방향을 AI에게 질의할 수 있습니다.
        </p>
      </div>
    </div>

    <div class="awr-ai-chat-shell">
      <main class="awr-ai-chat-main">
        <section class="awr-ai-chat-window">
          <div class="awr-ai-chat-topbar">
            <div class="awr-ai-chat-title">
              <span class="awr-ai-chat-status-dot"></span>
              <div>
                <strong>AI 분석 채팅</strong>
                <span>SQL 지표 · Wait Event · Section 근거 기반 답변</span>
              </div>
            </div>

            <div class="awr-ai-report-picker">
              <span>분석 리포트</span>
              <div class="awr-chat-select-wrap">
                <select
                  v-model.number="selectedReportId"
                  class="awr-input awr-chat-select awr-ai-report-select"
                >
                  <option :value="0">리포트 선택</option>
                  <option v-for="report in reports" :key="report.id" :value="report.id">
                    #{{ report.id }} {{ report.filename }}
                  </option>
                </select>
                <span class="awr-chat-select-arrow">▾</span>
              </div>
            </div>
          </div>

          <div class="awr-ai-prompt-strip">
            <span>추천 질문</span>
            <button
              v-for="prompt in prompts"
              :key="prompt.label"
              class="awr-ai-prompt-chip"
              type="button"
              :disabled="!selectedReportId"
              @click="question = prompt.question"
            >
              {{ prompt.label }}
            </button>
          </div>

          <div class="awr-ai-conversation">
            <div v-if="errorMessage" class="awr-empty awr-chat-error">
              {{ errorMessage }}
            </div>

            <div v-else-if="!answer" class="awr-ai-empty-state">
              <div class="awr-ai-empty-orb">AI</div>
              <h2>리포트를 선택하고 질문을 입력해보세요.</h2>
              <p>
                우선 확인해야 할 SQL, Wait Event 기준 병목, CPU/I/O 의심 구간,
                운영 조치 순서를 AI가 정리합니다.
              </p>

              <div class="awr-ai-empty-examples">
                <button
                  v-for="prompt in prompts.slice(0, 3)"
                  :key="prompt.label"
                  type="button"
                  :disabled="!selectedReportId"
                  @click="question = prompt.question"
                >
                  {{ prompt.question }}
                </button>
              </div>
            </div>

            <template v-else>
              <div class="awr-ai-message user">
                <div class="awr-ai-avatar user">Q</div>
                <div class="awr-ai-bubble user">
                  <div class="awr-ai-message-meta">사용자 질문</div>
                  <p>{{ answer.question }}</p>
                </div>
              </div>

              <div class="awr-ai-message assistant">
                <div class="awr-ai-avatar assistant">AI</div>

                <div class="awr-ai-bubble assistant">
                  <div class="awr-ai-answer-head">
                    <div>
                      <span>AI Answer</span>
                      <strong>분석 결과</strong>
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

                  <div
                    v-if="
                      answer.citations.length ||
                      answer.evidenceSql.length ||
                      answer.evidenceWaitEvents.length
                    "
                    class="awr-ai-evidence-area"
                  >
                    <details v-if="answer.citations.length" class="awr-ai-evidence-details">
                      <summary>
                        <span>답변 근거</span>
                        <em>{{ answer.citations.length }}건</em>
                      </summary>

                      <ul class="awr-chat-citation-list pro">
                        <li v-for="citation in answer.citations" :key="citation">
                          {{ formatCitation(citation) }}
                        </li>
                      </ul>
                    </details>

                    <details v-if="answer.evidenceSql.length" class="awr-ai-evidence-details">
                      <summary>
                        <span>근거 SQL</span>
                        <em>{{ answer.evidenceSql.length }}건</em>
                      </summary>

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
                    </details>

                    <details v-if="answer.evidenceWaitEvents.length" class="awr-ai-evidence-details">
                      <summary>
                        <span>근거 Wait Event</span>
                        <em>{{ answer.evidenceWaitEvents.length }}건</em>
                      </summary>

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
                    </details>
                  </div>
                </div>
              </div>
            </template>
          </div>

          <div class="awr-ai-composer">
            <textarea
              v-model="question"
              class="awr-ai-composer-input"
              placeholder="예: 이 AWR에서 제일 먼저 봐야 할 SQL은?"
              @keydown.enter.exact.prevent="ask"
            ></textarea>

            <button
              class="awr-ai-send-button"
              type="button"
              :disabled="!canAsk || isAsking"
              @click="ask"
            >
              <span v-if="isAsking">...</span>
              <span v-else>↑</span>
            </button>
          </div>
        </section>
      </main>

      <aside class="awr-ai-chat-sidebar">
        <section class="awr-panel awr-ai-side-card">
          <div class="awr-ai-side-head">
            <span>분석 대상</span>
            <strong>선택 리포트</strong>
          </div>

          <div v-if="selectedReport" class="awr-ai-selected-report">
            <div class="awr-ai-selected-id">#{{ selectedReport.id }}</div>

            <div>
              <span>파일명</span>
              <strong :title="selectedReport.filename">{{ selectedReport.filename }}</strong>
            </div>

            <div class="awr-ai-report-meta-grid">
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

            <div>
              <span>분석 구간</span>
              <strong>
                {{ formatSnapshotText(selectedReport.snapBegin) }}
                ~
                {{ formatSnapshotText(selectedReport.snapEnd) }}
              </strong>
            </div>
          </div>

          <div v-else class="awr-empty compact">
            분석할 AWR 리포트를 선택하세요.
          </div>
        </section>

        <section class="awr-panel awr-ai-side-card awr-ai-history-card">
          <div class="awr-ai-side-head row">
            <div>
              <span>이전 질의</span>
              <strong>채팅 히스토리</strong>
            </div>

            <em>{{ chatHistory.length }}</em>
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

          <ul v-else class="awr-ai-history-list">
            <li v-for="item in chatHistory" :key="item.chatId">
              <button
                :class="['awr-ai-history-item', { active: selectedHistoryId === item.chatId }]"
                type="button"
                @click="selectHistory(item)"
              >
                <span>{{ item.question }}</span>
                <em>
                  {{ formatDateTime(item.createdAt) }}
                  <template v-if="item.model"> · {{ item.model }}</template>
                </em>
              </button>
            </li>
          </ul>
        </section>
      </aside>
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
  if (!canAsk.value || isAsking.value) return

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

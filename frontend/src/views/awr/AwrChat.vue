<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>AI 리포트 분석</h1>
        <p>선택한 AWR의 SQL metric, wait event, section 근거만 사용해 튜닝 질문에 답합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-upload' })">AWR 업로드</button>
      </div>
    </div>

    <div class="awr-chat-layout">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">리포트 선택</h2>
        </div>
        <select v-model.number="selectedReportId">
          <option :value="0">선택</option>
          <option v-for="report in reports" :key="report.id" :value="report.id">
            #{{ report.id }} {{ report.filename }}
          </option>
        </select>

        <div class="awr-stack" style="margin-top: 1rem;">
          <button
            v-for="prompt in prompts"
            :key="prompt"
            class="awr-btn"
            type="button"
            :disabled="!selectedReportId"
            @click="question = prompt"
          >
            {{ prompt }}
          </button>
        </div>

        <div class="awr-chat-history">
          <div class="awr-panel-header compact">
            <h2 class="awr-panel-title">채팅 히스토리</h2>
            <span class="awr-badge">{{ chatHistory.length }}</span>
          </div>
          <div v-if="isLoadingHistory" class="awr-empty compact">히스토리를 불러오는 중입니다.</div>
          <div v-else-if="!selectedReportId" class="awr-empty compact">리포트를 선택하면 히스토리가 표시됩니다.</div>
          <div v-else-if="chatHistory.length === 0" class="awr-empty compact">아직 저장된 채팅이 없습니다.</div>
          <ul v-else class="awr-history-list">
            <li v-for="item in chatHistory" :key="item.chatId">
              <button
                :class="['awr-history-item', { active: selectedHistoryId === item.chatId }]"
                type="button"
                @click="selectHistory(item)"
              >
                <span class="awr-history-question">{{ item.question }}</span>
                <span class="awr-history-meta">
                  {{ formatDateTime(item.createdAt) }}
                  <span v-if="item.model"> · {{ item.model }}</span>
                </span>
              </button>
            </li>
          </ul>
        </div>
      </section>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">질문</h2>
          <span v-if="answer" class="awr-badge">confidence {{ answer.confidence }}</span>
        </div>

        <textarea v-model="question" class="awr-textarea" placeholder="이 AWR에서 제일 먼저 봐야 할 SQL은?"></textarea>
        <div class="awr-actions" style="margin-top: 0.75rem;">
          <button class="awr-btn primary" type="button" :disabled="!canAsk || isAsking" @click="ask">
            {{ isAsking ? '답변 생성 중' : '질문하기' }}
          </button>
        </div>

        <div v-if="errorMessage" class="awr-empty" style="margin-top: 1rem;">{{ errorMessage }}</div>

        <div v-if="answer" class="awr-stack" style="margin-top: 1rem;">
          <div class="awr-answer markdown-answer">
            <template v-for="(block, blockIndex) in answerBlocks" :key="blockIndex">
              <component :is="block.level <= 2 ? 'h3' : 'h4'" v-if="block.type === 'heading'" class="md-heading">
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

          <div class="awr-evidence-block">
            <div class="awr-panel-header">
              <h3 class="awr-panel-title">근거 Citation</h3>
            </div>
            <ul class="awr-list">
              <li v-for="citation in answer.citations" :key="citation">{{ citation }}</li>
            </ul>
          </div>

          <div class="awr-table-wrap" v-if="answer.evidenceSql.length">
            <table class="awr-table">
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
                <tr v-for="metric in answer.evidenceSql" :key="`${metric.sectionName}-${metric.sqlId}-${metric.rankNo}`">
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
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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

const router = useRouter()
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

const prompts = [
  '이 AWR에서 제일 먼저 봐야 할 SQL은?',
  'CPU 병목인지 I/O 병목인지 판단해줘',
  'Top Wait Event 기준으로 원인을 설명해줘'
]

const canAsk = computed(() => selectedReportId.value > 0 && question.value.trim().length > 0)
const answerBlocks = computed(() => parseMarkdown(answer.value?.answer || ''))

onMounted(async () => {
  try {
    reports.value = await getAwrReports()
    const routeReportId = Number(route.query.reportId || route.params.id || 0)
    selectedReportId.value = routeReportId || reports.value[0]?.id || 0
    question.value = String(route.query.question || prompts[0])
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

function formatNumber(value?: number | null) {
  if (value === null || value === undefined) return '-'
  return new Intl.NumberFormat('ko-KR', { maximumFractionDigits: 2 }).format(value)
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
      segments.push({ type: 'text', text: value.slice(lastIndex, match.index) })
    }

    const token = match[0]
    if (token.startsWith('`')) {
      segments.push({ type: 'code', text: token.slice(1, -1) })
    } else {
      segments.push({ type: 'strong', text: token.slice(2, -2) })
    }
    lastIndex = match.index + token.length
  }

  if (lastIndex < value.length) {
    segments.push({ type: 'text', text: value.slice(lastIndex) })
  }

  return segments.length ? segments : [{ type: 'text', text: value }]
}
</script>

<style src="./awr.css"></style>

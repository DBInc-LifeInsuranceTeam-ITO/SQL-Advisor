<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>SQLAdvisor</h1>
        <p>AWR 기반 SQL_ID, DB Time, Wait profile을 구조화하고 분석 우선순위를 정리합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn primary" type="button" @click="router.push({ name: 'awr-upload' })">AWR 업로드</button>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-chat' })">Chat</button>
      </div>
    </div>

    <section class="awr-grid">
      <div class="awr-kpi">
        <div class="awr-kpi-label">Reports</div>
        <div class="awr-kpi-value">{{ reports.length }}</div>
        <div class="awr-kpi-sub">업로드된 AWR</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Top SQL Rows</div>
        <div class="awr-kpi-value">{{ totalSql }}</div>
        <div class="awr-kpi-sub">SQL ordered section</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Wait Events</div>
        <div class="awr-kpi-value">{{ totalWaits }}</div>
        <div class="awr-kpi-sub">foreground 중심</div>
      </div>
      <div class="awr-kpi">
        <div class="awr-kpi-label">Ready</div>
        <div class="awr-kpi-value">{{ readyCount }}</div>
        <div class="awr-kpi-sub">INDEXED 상태</div>
      </div>
    </section>

    <div class="awr-split">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">최근 리포트</h2>
          <button class="awr-btn" type="button" @click="router.push({ name: 'awr-reports' })">전체 보기</button>
        </div>

        <div v-if="reports.length === 0" class="awr-empty">AWR HTML 또는 TXT 파일을 업로드하면 분석 현황이 표시됩니다.</div>
        <ul v-else class="awr-list">
          <li v-for="report in reports.slice(0, 5)" :key="report.id">
            <button class="awr-link" type="button" @click="router.push({ name: 'awr-report-detail', params: { id: report.id } })">
              {{ report.filename }}
            </button>
            <div class="awr-muted">{{ report.dbName }} / {{ report.instanceName }} · SQL {{ report.topSqlCount }} · Wait {{ report.waitEventCount }}</div>
          </li>
        </ul>
      </section>

      <section class="awr-panel ai-settings-panel">
        <div class="awr-panel-header">
          <div>
            <h2 class="awr-panel-title">AI 설정</h2>
            <p class="ai-panel-subtitle">현재 사용 중인 모델과 설정 출처를 먼저 확인한 뒤, 웹 저장값을 수정합니다.</p>
          </div>
          <button class="awr-btn" type="button" :disabled="isLoadingModels" @click="loadModelOptions">
            {{ isLoadingModels ? '모델 조회 중' : '모델 새로고침' }}
          </button>
        </div>

        <div v-if="aiConfig" class="ai-current-grid">
          <div class="ai-current-card">
            <div class="ai-card-label">현재 LLM</div>
            <div class="ai-card-value">{{ aiConfig.chatModel }}</div>
            <div class="ai-card-meta">
              <span class="awr-badge ok">{{ aiConfig.llmProvider }}</span>
              <span :class="['awr-badge', sourceBadgeClass(activeChatModelSource)]">{{ sourceLabel(activeChatModelSource) }}</span>
            </div>
          </div>
          <div class="ai-current-card">
            <div class="ai-card-label">현재 Embedding</div>
            <div class="ai-card-value">{{ aiConfig.embeddingModel }}</div>
            <div class="ai-card-meta">
              <span class="awr-badge ok">{{ aiConfig.embeddingProvider }}</span>
              <span :class="['awr-badge', sourceBadgeClass(activeEmbeddingModelSource)]">{{ sourceLabel(activeEmbeddingModelSource) }}</span>
            </div>
          </div>
        </div>

        <div v-if="aiConfig" class="ai-source-summary">
          <span :class="['awr-badge', sourceBadgeClass(aiConfig.settingSources.llmProvider)]">
            LLM 선택: {{ sourceLabel(aiConfig.settingSources.llmProvider) }}
          </span>
          <span :class="['awr-badge', sourceBadgeClass(aiConfig.settingSources.embeddingProvider)]">
            Embedding 선택: {{ sourceLabel(aiConfig.settingSources.embeddingProvider) }}
          </span>
          <span :class="['awr-badge', aiConfig.llmApiKeyConfigured ? 'ok' : 'warn']">
            LLM 키 {{ aiConfig.llmApiKeyConfigured ? '설정됨' : '필요' }}
          </span>
          <span :class="['awr-badge', aiConfig.embeddingApiKeyConfigured ? 'ok' : 'warn']">
            Embedding 키 {{ aiConfig.embeddingApiKeyConfigured ? '설정됨' : '필요' }}
          </span>
        </div>

        <div v-if="aiConfig?.missingProviderKeys.length" class="ai-warning-box">
          누락된 설정: {{ aiConfig.missingProviderKeys.join(', ') }}
        </div>

        <div v-if="aiConfig" class="ai-provider-grid">
          <article
            v-for="provider in aiConfig.providerConfigs"
            :key="provider.provider"
            :class="['ai-provider-card', { active: provider.selectedForChat || provider.selectedForEmbedding }]"
          >
            <div class="ai-provider-head">
              <strong>{{ provider.displayName }}</strong>
              <div class="ai-provider-badges">
                <span v-if="provider.selectedForChat" class="awr-badge ok">현재 LLM</span>
                <span v-if="provider.selectedForEmbedding" class="awr-badge ok">현재 Embedding</span>
                <span v-if="!provider.selectedForChat && !provider.selectedForEmbedding" class="awr-badge">추가 설정</span>
              </div>
            </div>
            <dl class="ai-provider-details">
              <div>
                <dt>API Key</dt>
                <dd>
                  {{ provider.apiKeyConfigured ? '설정됨' : '없음' }}
                  <span :class="['source-chip', sourceBadgeClass(provider.apiKeySource)]">{{ sourceLabel(provider.apiKeySource) }}</span>
                </dd>
              </div>
              <div v-if="provider.chatModel">
                <dt>Chat Model</dt>
                <dd>
                  {{ provider.chatModel }}
                  <span :class="['source-chip', sourceBadgeClass(provider.chatModelSource)]">{{ sourceLabel(provider.chatModelSource) }}</span>
                </dd>
              </div>
              <div v-if="provider.embeddingModel">
                <dt>Embedding Model</dt>
                <dd>
                  {{ provider.embeddingModel }}
                  <span :class="['source-chip', sourceBadgeClass(provider.embeddingModelSource)]">{{ sourceLabel(provider.embeddingModelSource) }}</span>
                </dd>
              </div>
              <div v-if="provider.baseUrl">
                <dt>Base URL</dt>
                <dd>
                  {{ provider.baseUrl }}
                  <span :class="['source-chip', sourceBadgeClass(provider.baseUrlSource)]">{{ sourceLabel(provider.baseUrlSource) }}</span>
                </dd>
              </div>
            </dl>
          </article>
        </div>

        <form class="awr-form ai-edit-form" @submit.prevent="saveAiConfig">
          <div class="ai-section-title">웹에서 저장할 설정</div>
          <div class="awr-form-grid">
            <label class="awr-field">
              <span>실제 사용할 LLM Provider</span>
              <select v-model="aiForm.llmProvider">
                <option value="local">local</option>
                <option value="openai">openai</option>
                <option value="gemini">gemini</option>
                <option value="ollama">ollama</option>
              </select>
            </label>
            <label class="awr-field">
              <span>실제 사용할 Embedding Provider</span>
              <select v-model="aiForm.embeddingProvider">
                <option value="none">none</option>
                <option value="openai">openai</option>
                <option value="gemini">gemini</option>
                <option value="ollama">ollama</option>
              </select>
            </label>
          </div>

          <div class="awr-form-grid">
            <label v-if="usesProvider('openai')" class="awr-field">
              <span>OpenAI API Key 웹 저장</span>
              <input v-model="aiForm.openaiApiKey" type="password" autocomplete="off" placeholder="비워두면 기존 값 유지" />
            </label>
            <label v-if="usesProvider('gemini')" class="awr-field">
              <span>Gemini API Key 웹 저장</span>
              <input v-model="aiForm.geminiApiKey" type="password" autocomplete="off" placeholder="비워두면 기존 값 유지" />
            </label>
          </div>

          <div class="awr-inline-row">
            <label v-if="usesProvider('openai')" class="awr-field compact">
              <span>웹 저장 OpenAI 키 삭제 확인</span>
              <input v-model="openaiDeleteInput" type="text" autocomplete="off" placeholder="값을 입력하면 웹 저장 키 삭제" />
            </label>
            <label v-if="usesProvider('gemini')" class="awr-field compact">
              <span>웹 저장 Gemini 키 삭제 확인</span>
              <input v-model="geminiDeleteInput" type="text" autocomplete="off" placeholder="값을 입력하면 웹 저장 키 삭제" />
            </label>
          </div>

          <div class="ai-section-title">현재 선택된 모델 설정</div>
          <div class="awr-form-grid">
            <label v-if="aiForm.llmProvider === 'openai'" class="awr-field">
              <span>OpenAI Chat Model</span>
              <select v-model="aiForm.openaiChatModel">
                <option v-for="model in openaiChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="aiForm.embeddingProvider === 'openai'" class="awr-field">
              <span>OpenAI Embedding Model</span>
              <select v-model="aiForm.openaiEmbeddingModel">
                <option v-for="model in openaiEmbeddingModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="aiForm.llmProvider === 'gemini'" class="awr-field">
              <span>Gemini Chat Model</span>
              <select v-model="aiForm.geminiChatModel">
                <option v-for="model in geminiChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="aiForm.embeddingProvider === 'gemini'" class="awr-field">
              <span>Gemini Embedding Model</span>
              <select v-model="aiForm.geminiEmbeddingModel">
                <option v-for="model in geminiEmbeddingModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="usesProvider('ollama')" class="awr-field">
              <span>Ollama Base URL</span>
              <input v-model="aiForm.ollamaBaseUrl" type="text" autocomplete="off" />
            </label>
            <label v-if="aiForm.llmProvider === 'ollama'" class="awr-field">
              <span>Ollama Chat Model</span>
              <select v-model="aiForm.ollamaChatModel">
                <option v-for="model in ollamaChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="aiForm.embeddingProvider === 'ollama'" class="awr-field">
              <span>Ollama Embedding Model</span>
              <select v-model="aiForm.ollamaEmbeddingModel">
                <option v-for="model in ollamaEmbeddingModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <div v-if="!hasModelSettings" class="awr-empty compact">현재 선택된 Provider에는 추가 모델 설정이 없습니다.</div>
          </div>

          <div class="awr-form-actions">
            <div class="awr-muted">
              환경변수 값은 그대로 두고, 이 화면에서는 웹 저장값만 추가/수정합니다.
            </div>
            <button class="awr-btn primary" type="submit" :disabled="isSavingAi">
              {{ isSavingAi ? '저장 중' : '웹 설정 저장' }}
            </button>
          </div>
          <p v-if="aiMessage" class="awr-form-message ok">{{ aiMessage }}</p>
          <p v-if="aiError" class="awr-form-message warn">{{ aiError }}</p>
          <p v-if="modelOptions.warnings.length" class="awr-form-message warn">{{ modelOptions.warnings[0] }}</p>
        </form>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAiConfig, getAiModelOptions, getAwrReports, updateAiConfig } from '@/api/awr'
import type { AiConfigResponse, AiConfigUpdateRequest, AiModelOptionsResponse, ConfigSource, ReportSummaryResponse } from '@/types/awr'

const router = useRouter()
const reports = ref<ReportSummaryResponse[]>([])
const aiConfig = ref<AiConfigResponse | null>(null)
const isSavingAi = ref(false)
const isLoadingModels = ref(false)
const aiMessage = ref('')
const aiError = ref('')
const modelOptions = ref<AiModelOptionsResponse>(emptyModelOptions())
const aiForm = ref<AiConfigUpdateRequest>(defaultAiForm())
const openaiDeleteInput = ref('')
const geminiDeleteInput = ref('')

const totalSql = computed(() => reports.value.reduce((sum, report) => sum + report.topSqlCount, 0))
const totalWaits = computed(() => reports.value.reduce((sum, report) => sum + report.waitEventCount, 0))
const readyCount = computed(() => reports.value.filter((report) => report.status === 'INDEXED').length)
const activeChatModelSource = computed(() => activeModelSource(aiConfig.value?.llmProvider, 'chat'))
const activeEmbeddingModelSource = computed(() => activeModelSource(aiConfig.value?.embeddingProvider, 'embedding'))
const openaiChatModelChoices = computed(() => withSelected(modelOptions.value.openaiChatModels, aiForm.value.openaiChatModel, 'gpt-4.1-mini'))
const openaiEmbeddingModelChoices = computed(() => withSelected(modelOptions.value.openaiEmbeddingModels, aiForm.value.openaiEmbeddingModel, 'text-embedding-3-small'))
const geminiChatModelChoices = computed(() => withSelected(modelOptions.value.geminiChatModels, aiForm.value.geminiChatModel, 'gemini-3.1-flash-lite'))
const geminiEmbeddingModelChoices = computed(() => withSelected(modelOptions.value.geminiEmbeddingModels, aiForm.value.geminiEmbeddingModel, 'gemini-embedding-001'))
const ollamaChatModelChoices = computed(() => withSelected(modelOptions.value.ollamaChatModels, aiForm.value.ollamaChatModel, 'llama3.1'))
const ollamaEmbeddingModelChoices = computed(() => withSelected(modelOptions.value.ollamaEmbeddingModels, aiForm.value.ollamaEmbeddingModel, 'embeddinggemma'))

onMounted(async () => {
  try {
    const [reportResult, aiResult, modelResult] = await Promise.all([
      getAwrReports(),
      getAiConfig(),
      getAiModelOptions()
    ])
    reports.value = reportResult
    aiConfig.value = aiResult
    modelOptions.value = modelResult
    syncAiForm(aiResult)
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  }
})

async function saveAiConfig() {
  isSavingAi.value = true
  aiMessage.value = ''
  aiError.value = ''
  try {
    const saved = await updateAiConfig({
      ...aiForm.value,
      openaiApiKey: aiForm.value.openaiApiKey?.trim() || undefined,
      geminiApiKey: aiForm.value.geminiApiKey?.trim() || undefined,
      ollamaBaseUrl: aiForm.value.ollamaBaseUrl?.trim() || undefined,
      ollamaChatModel: aiForm.value.ollamaChatModel?.trim() || undefined,
      ollamaEmbeddingModel: aiForm.value.ollamaEmbeddingModel?.trim() || undefined,
      clearOpenaiApiKey: openaiDeleteInput.value.trim().length > 0,
      clearGeminiApiKey: geminiDeleteInput.value.trim().length > 0
    })
    aiConfig.value = saved
    syncAiForm(saved)
    openaiDeleteInput.value = ''
    geminiDeleteInput.value = ''
    await loadModelOptions()
    aiMessage.value = 'AI 웹 설정을 저장했습니다.'
  } catch (error) {
    aiError.value = error instanceof Error ? error.message : 'AI 설정 저장에 실패했습니다.'
  } finally {
    isSavingAi.value = false
  }
}

async function loadModelOptions() {
  isLoadingModels.value = true
  try {
    modelOptions.value = await getAiModelOptions()
  } catch (error) {
    console.error('Failed to load AI model options:', error)
  } finally {
    isLoadingModels.value = false
  }
}

function syncAiForm(config: AiConfigResponse) {
  aiForm.value = {
    llmProvider: config.llmProvider || 'local',
    embeddingProvider: config.embeddingProvider || 'none',
    openaiApiKey: '',
    openaiChatModel: config.openaiChatModel || 'gpt-4.1-mini',
    openaiEmbeddingModel: config.openaiEmbeddingModel || 'text-embedding-3-small',
    geminiApiKey: '',
    geminiChatModel: config.geminiChatModel || 'gemini-3.1-flash-lite',
    geminiEmbeddingModel: config.geminiEmbeddingModel || 'gemini-embedding-001',
    ollamaBaseUrl: config.ollamaBaseUrl || 'http://host.docker.internal:11434',
    ollamaChatModel: config.ollamaChatModel || 'llama3.1',
    ollamaEmbeddingModel: config.ollamaEmbeddingModel || 'embeddinggemma',
    clearOpenaiApiKey: false,
    clearGeminiApiKey: false
  }
}

function defaultAiForm(): AiConfigUpdateRequest {
  return {
    llmProvider: 'local',
    embeddingProvider: 'none',
    openaiApiKey: '',
    openaiChatModel: 'gpt-4.1-mini',
    openaiEmbeddingModel: 'text-embedding-3-small',
    geminiApiKey: '',
    geminiChatModel: 'gemini-3.1-flash-lite',
    geminiEmbeddingModel: 'gemini-embedding-001',
    ollamaBaseUrl: 'http://host.docker.internal:11434',
    ollamaChatModel: 'llama3.1',
    ollamaEmbeddingModel: 'embeddinggemma',
    clearOpenaiApiKey: false,
    clearGeminiApiKey: false
  }
}

function emptyModelOptions(): AiModelOptionsResponse {
  return {
    openaiChatModels: ['gpt-4.1-mini'],
    openaiEmbeddingModels: ['text-embedding-3-small'],
    geminiChatModels: ['gemini-3.1-flash-lite'],
    geminiEmbeddingModels: ['gemini-embedding-001'],
    ollamaChatModels: ['llama3.1'],
    ollamaEmbeddingModels: ['embeddinggemma'],
    warnings: []
  }
}

function activeModelSource(provider?: string, type?: 'chat' | 'embedding'): ConfigSource {
  const sources = aiConfig.value?.settingSources || {}
  if (provider === 'openai') {
    return type === 'chat' ? sources.openaiChatModel : sources.openaiEmbeddingModel
  }
  if (provider === 'gemini') {
    return type === 'chat' ? sources.geminiChatModel : sources.geminiEmbeddingModel
  }
  if (provider === 'ollama') {
    return type === 'chat' ? sources.ollamaChatModel : sources.ollamaEmbeddingModel
  }
  return 'builtin'
}

function sourceLabel(source?: ConfigSource) {
  if (source === 'web') return '웹 저장'
  if (source === 'env') return '.env/서버 설정'
  if (source === 'builtin') return '기본 내장'
  return '설정 없음'
}

function sourceBadgeClass(source?: ConfigSource) {
  if (source === 'web') return 'ok'
  if (source === 'env') return ''
  if (source === 'builtin') return 'ok'
  return 'warn'
}

function usesProvider(provider: 'openai' | 'gemini' | 'ollama') {
  return aiForm.value.llmProvider === provider || aiForm.value.embeddingProvider === provider
}

const hasModelSettings = computed(() => {
  return aiForm.value.llmProvider !== 'local' || !['none', 'local'].includes(aiForm.value.embeddingProvider)
})

function withSelected(options: string[], selected?: string, fallback?: string) {
  return Array.from(new Set([...options, selected, fallback].filter((value): value is string => Boolean(value?.trim()))))
}
</script>

<style src="./awr.css"></style>

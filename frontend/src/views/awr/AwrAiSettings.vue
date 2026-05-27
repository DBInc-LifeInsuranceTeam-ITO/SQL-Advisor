<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>AI 설정</h1>
        <p>현재 사용 중인 모델과 설정 출처를 확인하고, 웹 저장값을 수정합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn" type="button" :disabled="isLoadingModels" @click="loadModelOptions">
          {{ isLoadingModels ? '모델 조회 중' : '모델 새로고침' }}
        </button>
      </div>
    </div>

    <section class="awr-panel ai-settings-panel">
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

      <div class="ai-source-summary">
        <button
          class="ai-provider-action"
          type="button"
          :disabled="aiForm.embeddingProvider === 'none'"
          @click="selectEmbeddingProvider('none')"
        >
          Embedding 끄기
        </button>
        <span class="awr-muted">Embedding을 끄면 RAG 유사도 검색 없이 저장된 구조화 데이터 중심으로 동작합니다.</span>
      </div>

      <div v-if="aiConfig" class="ai-provider-grid">
        <article
          v-for="provider in realProviderConfigs"
          :key="provider.provider"
          :class="['ai-provider-card', { active: isProviderSelected(provider.provider) }]"
        >
          <div class="ai-provider-head">
            <strong>{{ provider.displayName }}</strong>
            <div class="ai-provider-badges">
              <span v-if="provider.selectedForChat" class="awr-badge ok">현재 LLM</span>
              <span v-if="provider.selectedForEmbedding" class="awr-badge ok">현재 Embedding</span>
              <span v-if="aiForm.llmProvider === provider.provider" class="awr-badge">LLM 선택 예정</span>
              <span v-if="provider.provider === aiForm.embeddingProvider" class="awr-badge">Embedding 선택 예정</span>
              <span v-if="!provider.selectedForChat && !provider.selectedForEmbedding" class="awr-badge">추가 설정</span>
            </div>
          </div>
          <div class="ai-provider-actions">
            <button
              class="ai-provider-action"
              type="button"
              :disabled="aiForm.llmProvider === provider.provider"
              @click="selectLlmProvider(provider.provider)"
            >
              LLM으로 사용
            </button>
            <button
              v-if="provider.provider !== 'internal'"
              class="ai-provider-action"
              type="button"
              :disabled="aiForm.embeddingProvider === provider.provider"
              @click="selectEmbeddingProvider(provider.provider)"
            >
              Embedding으로 사용
            </button>
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
          <div class="ai-provider-models">
            <label v-if="provider.provider === 'openai'" class="awr-field">
              <span>API Key</span>
              <div class="ai-key-mode">
                <button type="button" :class="{ active: isKeyModeActive(provider.provider, openaiKeyMode, 'env') }" @click="openaiKeyMode = 'env'">env 설정 사용</button>
                <button type="button" :class="{ active: isKeyModeActive(provider.provider, openaiKeyMode, 'web') }" @click="openaiKeyMode = 'web'">키 입력</button>
              </div>
              <input v-if="openaiKeyMode === 'web'" v-model="aiForm.openaiApiKey" type="password" autocomplete="off" placeholder="비워두면 기존 웹 저장 키 유지" />
            </label>
            <label v-if="provider.provider === 'openai'" class="awr-field">
              <span>Chat Model</span>
              <select v-model="aiForm.openaiChatModel">
                <option v-for="model in openaiChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="provider.provider === 'openai'" class="awr-field">
              <span>Embedding Model</span>
              <select v-model="aiForm.openaiEmbeddingModel">
                <option v-for="model in openaiEmbeddingModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="provider.provider === 'gemini'" class="awr-field">
              <span>API Key</span>
              <div class="ai-key-mode">
                <button type="button" :class="{ active: isKeyModeActive(provider.provider, geminiKeyMode, 'env') }" @click="geminiKeyMode = 'env'">env 설정 사용</button>
                <button type="button" :class="{ active: isKeyModeActive(provider.provider, geminiKeyMode, 'web') }" @click="geminiKeyMode = 'web'">키 입력</button>
              </div>
              <input v-if="geminiKeyMode === 'web'" v-model="aiForm.geminiApiKey" type="password" autocomplete="off" placeholder="비워두면 기존 웹 저장 키 유지" />
            </label>
            <label v-if="provider.provider === 'gemini'" class="awr-field">
              <span>Chat Model</span>
              <select v-model="aiForm.geminiChatModel">
                <option v-for="model in geminiChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="provider.provider === 'gemini'" class="awr-field">
              <span>Embedding Model</span>
              <select v-model="aiForm.geminiEmbeddingModel">
                <option v-for="model in geminiEmbeddingModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="provider.provider === 'internal'" class="awr-field">
              <span>API Key</span>
              <div class="ai-key-mode">
                <button type="button" :class="{ active: isKeyModeActive(provider.provider, internalKeyMode, 'env') }" @click="internalKeyMode = 'env'">env 설정 사용</button>
                <button type="button" :class="{ active: isKeyModeActive(provider.provider, internalKeyMode, 'web') }" @click="internalKeyMode = 'web'">키 입력</button>
              </div>
              <input v-if="internalKeyMode === 'web'" v-model="aiForm.internalApiKey" type="password" autocomplete="off" placeholder="LiteLLM Master Key" />
            </label>
            <label v-if="provider.provider === 'internal'" class="awr-field">
              <span>Base URL</span>
              <input v-model="aiForm.internalBaseUrl" type="text" autocomplete="off" placeholder="http://<노드IP>:30434/v1/chat/completions" />
            </label>
            <label v-if="provider.provider === 'internal'" class="awr-field">
              <span>Chat Model</span>
              <select v-model="aiForm.internalChatModel">
                <option v-for="model in internalChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="provider.provider === 'ollama'" class="awr-field">
              <span>Base URL</span>
              <input v-model="aiForm.ollamaBaseUrl" type="text" autocomplete="off" />
            </label>
            <label v-if="provider.provider === 'ollama'" class="awr-field">
              <span>Chat Model</span>
              <select v-model="aiForm.ollamaChatModel">
                <option v-for="model in ollamaChatModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
            <label v-if="provider.provider === 'ollama'" class="awr-field">
              <span>Embedding Model</span>
              <select v-model="aiForm.ollamaEmbeddingModel">
                <option v-for="model in ollamaEmbeddingModelChoices" :key="model" :value="model">{{ model }}</option>
              </select>
            </label>
          </div>
        </article>
      </div>

      <form class="awr-form ai-edit-form" @submit.prevent="saveAiConfig">
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
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getAiConfig, getAiModelOptions, updateAiConfig } from '@/api/awr'
import type { AiConfigResponse, AiConfigUpdateRequest, AiModelOptionsResponse, ConfigSource } from '@/types/awr'

const REAL_PROVIDERS = ['openai', 'gemini', 'internal', 'ollama']

const aiConfig = ref<AiConfigResponse | null>(null)
const isSavingAi = ref(false)
const isLoadingModels = ref(false)
const aiMessage = ref('')
const aiError = ref('')
const modelOptions = ref<AiModelOptionsResponse>(emptyModelOptions())
const aiForm = ref<AiConfigUpdateRequest>(defaultAiForm())
const openaiKeyMode = ref<'env' | 'web'>('env')
const geminiKeyMode = ref<'env' | 'web'>('env')
const internalKeyMode = ref<'env' | 'web'>('env')

const activeChatModelSource = computed(() => activeModelSource(aiConfig.value?.llmProvider, 'chat'))
const activeEmbeddingModelSource = computed(() => activeModelSource(aiConfig.value?.embeddingProvider, 'embedding'))
const openaiChatModelChoices = computed(() => withSelected(modelOptions.value.openaiChatModels, aiForm.value.openaiChatModel, 'gpt-4.1-mini'))
const openaiEmbeddingModelChoices = computed(() => withSelected(modelOptions.value.openaiEmbeddingModels, aiForm.value.openaiEmbeddingModel, 'text-embedding-3-small'))
const geminiChatModelChoices = computed(() => withSelected(modelOptions.value.geminiChatModels, aiForm.value.geminiChatModel, 'gemini-3.1-flash-lite'))
const geminiEmbeddingModelChoices = computed(() => withSelected(modelOptions.value.geminiEmbeddingModels, aiForm.value.geminiEmbeddingModel, 'gemini-embedding-001'))
const internalChatModelChoices = computed(() => withSelected(modelOptions.value.internalChatModels, aiForm.value.internalChatModel, 'gemma3-12b'))
const ollamaChatModelChoices = computed(() => withSelected(modelOptions.value.ollamaChatModels, aiForm.value.ollamaChatModel, 'llama3.1'))
const ollamaEmbeddingModelChoices = computed(() => withSelected(modelOptions.value.ollamaEmbeddingModels, aiForm.value.ollamaEmbeddingModel, 'embeddinggemma'))
const realProviderConfigs = computed(() => aiConfig.value?.providerConfigs.filter((provider) => provider.provider !== 'local') || [])

onMounted(async () => {
  try {
    const [aiResult, modelResult] = await Promise.all([
      getAiConfig(),
      getAiModelOptions()
    ])
    aiConfig.value = aiResult
    modelOptions.value = modelResult
    syncAiForm(aiResult)
  } catch (error) {
    aiError.value = error instanceof Error ? error.message : 'AI 설정을 불러오지 못했습니다.'
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
      internalApiKey: aiForm.value.internalApiKey?.trim() || undefined,
      internalBaseUrl: aiForm.value.internalBaseUrl?.trim() || undefined,
      internalChatModel: aiForm.value.internalChatModel?.trim() || undefined,
      ollamaBaseUrl: aiForm.value.ollamaBaseUrl?.trim() || undefined,
      ollamaChatModel: aiForm.value.ollamaChatModel?.trim() || undefined,
      ollamaEmbeddingModel: aiForm.value.ollamaEmbeddingModel?.trim() || undefined,
      clearOpenaiApiKey: openaiKeyMode.value === 'env',
      clearGeminiApiKey: geminiKeyMode.value === 'env',
      clearInternalApiKey: internalKeyMode.value === 'env'
    })
    aiConfig.value = saved
    syncAiForm(saved)
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
    llmProvider: normalizeLlmProvider(config),
    embeddingProvider: normalizeEmbeddingProvider(config.embeddingProvider),
    openaiApiKey: '',
    openaiChatModel: config.openaiChatModel || 'gpt-4.1-mini',
    openaiEmbeddingModel: config.openaiEmbeddingModel || 'text-embedding-3-small',
    geminiApiKey: '',
    geminiChatModel: config.geminiChatModel || 'gemini-3.1-flash-lite',
    geminiEmbeddingModel: config.geminiEmbeddingModel || 'gemini-embedding-001',
    internalApiKey: '',
    internalBaseUrl: config.internalBaseUrl || '',
    internalChatModel: config.internalChatModel || 'gemma3-12b',
    ollamaBaseUrl: config.ollamaBaseUrl || 'http://host.docker.internal:11434',
    ollamaChatModel: config.ollamaChatModel || 'llama3.1',
    ollamaEmbeddingModel: config.ollamaEmbeddingModel || 'embeddinggemma',
    clearOpenaiApiKey: false,
    clearGeminiApiKey: false
  }
  openaiKeyMode.value = config.settingSources.openaiApiKey === 'web' ? 'web' : 'env'
  geminiKeyMode.value = config.settingSources.geminiApiKey === 'web' ? 'web' : 'env'
  internalKeyMode.value = config.settingSources.internalApiKey === 'web' ? 'web' : 'env'
}

function defaultAiForm(): AiConfigUpdateRequest {
  return {
    llmProvider: 'openai',
    embeddingProvider: 'none',
    openaiApiKey: '',
    openaiChatModel: 'gpt-4.1-mini',
    openaiEmbeddingModel: 'text-embedding-3-small',
    geminiApiKey: '',
    geminiChatModel: 'gemini-3.1-flash-lite',
    geminiEmbeddingModel: 'gemini-embedding-001',
    internalApiKey: '',
    internalBaseUrl: '',
    internalChatModel: 'gemma3-12b',
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
    internalChatModels: ['gemma3-12b'],
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
  if (provider === 'internal') {
    return type === 'chat' ? sources.internalChatModel : 'none'
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

function usesProvider(provider: 'openai' | 'gemini' | 'internal' | 'ollama') {
  return aiForm.value.llmProvider === provider || aiForm.value.embeddingProvider === provider
}

function normalizeLlmProvider(config: AiConfigResponse) {
  if (REAL_PROVIDERS.includes(config.llmProvider)) {
    return config.llmProvider
  }
  return config.configuredProviders.find((provider) => REAL_PROVIDERS.includes(provider)) || 'openai'
}

function normalizeEmbeddingProvider(provider: string) {
  return REAL_PROVIDERS.includes(provider) ? provider : 'none'
}

function isProviderSelected(provider: string) {
  return aiForm.value.llmProvider === provider || aiForm.value.embeddingProvider === provider
}

function isKeyModeActive(provider: string, currentMode: 'env' | 'web', buttonMode: 'env' | 'web') {
  return isProviderSelected(provider) && currentMode === buttonMode
}

function selectLlmProvider(provider: string) {
  aiForm.value.llmProvider = provider
}

function selectEmbeddingProvider(provider: string) {
  aiForm.value.embeddingProvider = provider
}

function withSelected(options: string[], selected?: string, fallback?: string) {
  return Array.from(new Set([...options, selected, fallback].filter((value): value is string => Boolean(value?.trim()))))
}
</script>

<style src="./awr.css"></style>

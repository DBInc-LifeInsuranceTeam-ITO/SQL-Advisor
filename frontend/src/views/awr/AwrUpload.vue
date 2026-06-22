<template>
  <div class="awr-page awr-upload-page">
    <div class="awr-upload-hero">
      <div>
        <p class="awr-upload-eyebrow">AWR Report Analysis</p>
        <h1 class="awr-main-title">AWR 분석 요청</h1>
        <p>
          AWR 리포트를 업로드하면 SQL 성능 지표와 주요 Wait Event를 분석하여
          점검이 필요한 대상을 빠르게 확인할 수 있습니다.
        </p>
      </div>
    </div>

    <div class="awr-upload-layout">
      <section class="awr-panel awr-upload-card">
        <div class="awr-upload-section-title">
          <div>
            <h2>리포트 등록</h2>
            <p>HTML, TXT, PDF 형식의 AWR 리포트를 등록할 수 있습니다.</p>
          </div>
          <span class="awr-format-badge">HTML · TXT · PDF</span>
        </div>

        <div class="awr-file-upload-box">
          <label class="awr-file-select-button">
            파일 선택
            <input type="file" accept=".html,.htm,.txt,.log,.pdf" @change="handleFileChange" />
          </label>

          <div class="awr-file-selected-info">
            <strong>{{ selectedFile ? selectedFile.name : '선택된 파일이 없습니다.' }}</strong>
            <span>{{ selectedFile ? fileSizeLabel : 'HTML, TXT, PDF 파일을 등록하세요.' }}</span>
          </div>
        </div>

        <div class="awr-choice-group">
          <label :class="['awr-choice-card', { active: visibility === 'SHARED' }]">
            <input v-model="visibility" type="radio" value="SHARED" />
            <div>
              <strong>공유 분석</strong>
              <span>팀 사용자가 함께 결과를 조회할 수 있습니다.</span>
            </div>
          </label>

          <label :class="['awr-choice-card', { active: visibility === 'PRIVATE' }]">
            <input v-model="visibility" type="radio" value="PRIVATE" />
            <div>
              <strong>비공개 분석</strong>
              <span>업로드한 본인과 관리자만 조회할 수 있습니다.</span>
            </div>
          </label>
        </div>

        <div class="awr-upload-submit-row">
          <p v-if="errorMessage" class="awr-upload-error">{{ errorMessage }}</p>
          <button
            class="awr-btn primary awr-upload-submit"
            type="button"
            :disabled="!selectedFile || isUploading"
            @click="upload"
          >
            {{ isUploading ? '분석 요청 중' : '분석 요청하기' }}
          </button>
        </div>
      </section>

      <aside class="awr-panel awr-guide-card">
        <h2>분석 후 확인할 수 있어요</h2>

        <div class="awr-guide-list">
          <div>
            <strong>Top SQL</strong>
            <span>부하가 큰 SQL과 주요 성능 지표를 확인합니다.</span>
          </div>
          <div>
            <strong>Wait Event</strong>
            <span>대기 이벤트를 기반으로 병목 구간을 파악합니다.</span>
          </div>
          <div>
            <strong>AI 리포트 분석</strong>
            <span>분석 결과를 기반으로 원인과 조치 방향을 질의할 수 있습니다.</span>
          </div>
        </div>

        <div class="awr-guide-note">
          <strong>Tip</strong>
          <span>운영 DB 장애나 성능 저하 시점의 AWR을 등록하면 분석 정확도가 높아집니다.</span>
        </div>
      </aside>
    </div>

    <section v-if="uploadResult" class="awr-panel awr-upload-result-card compact">
      <div class="awr-result-main compact">
        <span :class="['awr-status-chip', statusClass(currentStatus)]">
          {{ statusLabel(currentStatus) }}
        </span>

        <div class="awr-result-content">
          <div class="awr-result-title-row">
            <div>
              <h2>{{ resultTitle }}</h2>
              <strong class="awr-result-filename">{{ uploadResult.filename }}</strong>
            </div>

            <button
              class="awr-btn success awr-result-primary-button"
              type="button"
              :disabled="!isCompletedStatus"
              @click="goToResult"
            >
              {{ isCompletedStatus ? '분석 결과 보기' : '분석 완료 후 확인' }}
            </button>
          </div>

          <div class="awr-progress-box">
            <div class="awr-progress-head">
              <span>{{ progressTitle }}</span>
              <strong>{{ progressPercent }}%</strong>
            </div>

            <div class="awr-progress-track">
              <div
                :class="[
                  'awr-progress-fill',
                  {
                    failed: isFailedStatus,
                    completed: isCompletedStatus
                  }
                ]"
                :style="{ width: `${progressPercent}%` }"
              ></div>
            </div>

            <div class="awr-step-list">
              <div
                v-for="(step, index) in progressSteps"
                :key="step.key"
                :class="[
                  'awr-step-item',
                  {
                    done: index < activeStepIndex || isCompletedStatus,
                    active: index === activeStepIndex && !isCompletedStatus && !isFailedStatus,
                    failed: isFailedStatus && index === activeStepIndex
                  }
                ]"
              >
                <span class="awr-step-dot">
                  <span v-if="index < activeStepIndex || isCompletedStatus">✓</span>
                  <span v-else-if="isFailedStatus && index === activeStepIndex">!</span>
                </span>
                <span class="awr-step-label">{{ step.label }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAwrStatus, uploadAwrReport } from '@/api/awr'
import type { ReportVisibility, StatusResponse, UploadResponse } from '@/types/awr'

const router = useRouter()

const selectedFile = ref<File | null>(null)
const isUploading = ref(false)
const errorMessage = ref('')
const uploadResult = ref<UploadResponse | null>(null)
const statusInfo = ref<StatusResponse | null>(null)
const visibility = ref<ReportVisibility>('SHARED')

let statusTimer: ReturnType<typeof window.setInterval> | null = null

const progressSteps = [
  { key: 'received', label: '요청 접수' },
  { key: 'extract', label: '파일 분석' },
  { key: 'metrics', label: 'SQL 지표 추출' },
  { key: 'done', label: '결과 생성' }
]

const fileSizeLabel = computed(() => {
  if (!selectedFile.value) return ''

  const size = selectedFile.value.size

  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`

  return `${(size / 1024 / 1024).toFixed(1)} MB`
})

const currentStatus = computed(() => {
  return statusInfo.value?.status || uploadResult.value?.status || ''
})

const normalizedStatus = computed(() => {
  return currentStatus.value?.toUpperCase() || ''
})

const isFailedStatus = computed(() => {
  return normalizedStatus.value === 'FAILED' || normalizedStatus.value === 'ERROR'
})

const isCompletedStatus = computed(() => {
  return ['INDEXED', 'COMPLETED', 'DONE'].includes(normalizedStatus.value)
})

const activeStepIndex = computed(() => {
  const status = normalizedStatus.value
  const currentStep = statusInfo.value?.currentStep?.toUpperCase() || ''
  const progress = statusInfo.value?.progress ?? 0

  if (status === 'FAILED' || status === 'ERROR') return 1
  if (status === 'INDEXED' || status === 'COMPLETED' || status === 'DONE') return 3

  if (currentStep.includes('INDEX')) return 2

  if (
    currentStep.includes('PARSE') ||
    currentStep.includes('EXTRACT') ||
    currentStep.includes('OCR') ||
    currentStep.includes('FILE')
  ) {
    return 1
  }

  if (
    currentStep.includes('SQL') ||
    currentStep.includes('WAIT') ||
    currentStep.includes('METRIC')
  ) {
    return 2
  }

  if (progress >= 90) return 3
  if (progress >= 65) return 2
  if (progress >= 35) return 1

  return 0
})

const progressPercent = computed(() => {
  const apiProgress = statusInfo.value?.progress

  if (typeof apiProgress === 'number' && apiProgress > 0) {
    return Math.min(100, Math.max(0, Math.round(apiProgress)))
  }

  const status = normalizedStatus.value

  if (status === 'FAILED' || status === 'ERROR') return 100
  if (status === 'QUEUED') return 25
  if (status === 'PROCESSING' || status === 'PARSING') return 55
  if (status === 'INDEXING') return 78
  if (status === 'INDEXED' || status === 'COMPLETED' || status === 'DONE') return 100

  return uploadResult.value ? 25 : 0
})

const progressTitle = computed(() => {
  const status = normalizedStatus.value
  const currentStep = statusInfo.value?.currentStep

  if (status === 'FAILED' || status === 'ERROR') return '오류가 발생했습니다'
  if (status === 'INDEXED' || status === 'COMPLETED' || status === 'DONE') return '분석 완료'
  if (currentStep) return statusStepLabel(currentStep)
  if (status === 'QUEUED') return '분석 대기 중'
  if (status === 'PROCESSING' || status === 'PARSING') return '리포트 분석 중'
  if (status === 'INDEXING') return '결과 생성 중'

  return '요청 접수'
})

const resultTitle = computed(() => {
  if (isFailedStatus.value) return '분석 실패'
  if (isCompletedStatus.value) return '분석 완료'
  if (normalizedStatus.value === 'QUEUED') return '분석 대기 중'

  return '분석 진행 중'
})

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement

  stopStatusPolling()

  selectedFile.value = input.files?.[0] || null
  uploadResult.value = null
  statusInfo.value = null
  errorMessage.value = ''
}

function statusLabel(status: string) {
  const normalized = status?.toUpperCase()

  if (normalized === 'QUEUED') return '분석 대기'
  if (normalized === 'PROCESSING' || normalized === 'PARSING' || normalized === 'INDEXING') return '분석 중'
  if (normalized === 'INDEXED' || normalized === 'COMPLETED' || normalized === 'DONE') return '분석 완료'
  if (normalized === 'FAILED' || normalized === 'ERROR') return '분석 실패'

  return '접수 완료'
}

function statusClass(status: string) {
  const normalized = status?.toUpperCase()

  if (normalized === 'INDEXED' || normalized === 'COMPLETED' || normalized === 'DONE') return 'done'
  if (normalized === 'FAILED' || normalized === 'ERROR') return 'failed'
  if (normalized === 'PROCESSING' || normalized === 'PARSING' || normalized === 'INDEXING') return 'processing'

  return 'waiting'
}

function statusStepLabel(step: string) {
  const normalized = step.toUpperCase()

  if (normalized.includes('QUEUE')) return '분석 대기 중'
  if (normalized.includes('UPLOAD') || normalized.includes('SAVE')) return '리포트 등록 중'
  if (normalized.includes('EXTRACT') || normalized.includes('OCR') || normalized.includes('FILE')) {
    return '파일 분석 중'
  }
  if (normalized.includes('PARSE')) return 'AWR 분석 중'
  if (normalized.includes('SQL')) return 'SQL 지표 추출 중'
  if (normalized.includes('WAIT')) return 'Wait Event 분석 중'
  if (normalized.includes('INDEX')) return '결과 생성 중'
  if (normalized.includes('DONE') || normalized.includes('COMPLETE')) return '분석 완료'

  return '분석 진행 중'
}

async function upload() {
  if (!selectedFile.value) return

  isUploading.value = true
  errorMessage.value = ''
  stopStatusPolling()

  try {
    const result = await uploadAwrReport(selectedFile.value, visibility.value)

    uploadResult.value = result
    statusInfo.value = {
      reportId: result.id,
      status: result.status,
      progress: result.status?.toUpperCase() === 'QUEUED' ? 25 : 0,
      currentStep: 'QUEUE',
      completedSteps: [],
      warnings: []
    }

    startStatusPolling(result.id)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '분석 요청에 실패했습니다.'
  } finally {
    isUploading.value = false
  }
}

function goToResult() {
  if (!uploadResult.value || !isCompletedStatus.value) return

  router.push({
    name: 'awr-report-detail',
    params: { id: uploadResult.value.id }
  })
}

function startStatusPolling(reportId: number) {
  stopStatusPolling()

  refreshStatus(reportId)

  statusTimer = window.setInterval(() => {
    refreshStatus(reportId)
  }, 3000)
}

function stopStatusPolling() {
  if (!statusTimer) return

  window.clearInterval(statusTimer)
  statusTimer = null
}

async function refreshStatus(reportId: number) {
  try {
    const latestStatus = await getAwrStatus(reportId)

    statusInfo.value = latestStatus

    if (uploadResult.value) {
      uploadResult.value = {
        ...uploadResult.value,
        status: latestStatus.status
      }
    }

    const status = latestStatus.status?.toUpperCase()

    if (['INDEXED', 'COMPLETED', 'DONE', 'FAILED', 'ERROR'].includes(status)) {
      stopStatusPolling()
    }
  } catch (error) {
    console.warn('AWR status polling failed', error)
  }
}

onBeforeUnmount(() => {
  stopStatusPolling()
})
</script>

<style src="./awr.css"></style>

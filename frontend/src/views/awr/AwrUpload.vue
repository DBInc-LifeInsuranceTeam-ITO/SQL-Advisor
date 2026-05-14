<template>
  <div class="awr-page">
    <div class="awr-page-header">
      <div>
        <h1>AWR 업로드</h1>
        <p>업로드 후 worker가 HTML/TXT/PDF 추출과 OCR fallback을 처리하고, API가 파싱/RAG indexing을 이어서 수행합니다.</p>
      </div>
      <div class="awr-actions">
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-reports' })">리포트 목록</button>
      </div>
    </div>

    <div class="awr-split">
      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">파일 등록</h2>
          <span class="awr-badge">HTML · TXT · PDF</span>
        </div>

        <div class="awr-upload-box">
          <input type="file" accept=".html,.htm,.txt,.log,.pdf" @change="handleFileChange" />
          <div v-if="selectedFile" class="awr-file-name">{{ selectedFile.name }}</div>
        </div>

        <div class="awr-actions" style="margin-top: 1rem;">
          <button class="awr-btn primary" type="button" :disabled="!selectedFile || isUploading" @click="upload">
            {{ isUploading ? '업로드 중' : '분석 시작' }}
          </button>
        </div>

        <p v-if="errorMessage" class="awr-empty" style="margin-top: 1rem;">{{ errorMessage }}</p>
      </section>

      <section class="awr-panel">
        <div class="awr-panel-header">
          <h2 class="awr-panel-title">처리 파이프라인</h2>
        </div>
        <ul class="awr-list">
          <li>파일 타입 판별</li>
          <li>Worker 텍스트 추출/OCR fallback</li>
          <li>API AWR 섹션 분리</li>
          <li>Top SQL · Wait Event metric 추출</li>
          <li>Embedding · pgvector RAG indexing</li>
        </ul>
      </section>
    </div>

    <section v-if="uploadResult" class="awr-panel">
      <div class="awr-panel-header">
        <h2 class="awr-panel-title">업로드 결과</h2>
        <span :class="['awr-badge', uploadResult.status === 'INDEXED' ? 'ok' : 'warn']">{{ uploadResult.status }}</span>
      </div>
      <p class="awr-muted">{{ uploadResult.message }}</p>
      <div class="awr-actions" style="justify-content: flex-start; margin-top: 1rem;">
        <button class="awr-btn success" type="button" @click="router.push({ name: 'awr-report-detail', params: { id: uploadResult.id } })">
          분석 결과 보기
        </button>
        <button class="awr-btn" type="button" @click="router.push({ name: 'awr-chat', query: { reportId: uploadResult.id } })">
          Chat 열기
        </button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { uploadAwrReport } from '@/api/awr'
import type { UploadResponse } from '@/types/awr'

const router = useRouter()
const selectedFile = ref<File | null>(null)
const isUploading = ref(false)
const errorMessage = ref('')
const uploadResult = ref<UploadResponse | null>(null)

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] || null
  uploadResult.value = null
  errorMessage.value = ''
}

async function upload() {
  if (!selectedFile.value) return
  isUploading.value = true
  errorMessage.value = ''
  try {
    uploadResult.value = await uploadAwrReport(selectedFile.value)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '업로드에 실패했습니다.'
  } finally {
    isUploading.value = false
  }
}
</script>

<style src="./awr.css"></style>

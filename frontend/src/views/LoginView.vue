<template>
  <main class="login-page">
    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-panel-top">
        <img src="/assets/logo_google.png" alt="" class="login-top-icon" />
        <span>Google 계정으로 로그인</span>
      </div>

      <div class="login-body">
        <div class="login-brand">
          <img src="/assets/logo_db.png" alt="DB" class="login-logo" />
          <h1 id="login-title">로그인</h1>
          <p><strong>SQL Advisor</strong>(으)로 이동</p>
        </div>

        <form class="login-form" aria-label="로그인" @submit.prevent="handleGoogleLogin">
          <div v-if="isLoading" class="login-state">로그인 설정을 확인하는 중입니다.</div>
          <template v-else>
            <label class="login-field">
              <input
                v-model.trim="loginIdentifier"
                type="text"
                autocomplete="username"
                placeholder=" "
                :disabled="isSubmitting || !googleReady"
                required
              />
              <span>이메일</span>
            </label>

            <button class="login-help" type="button">이메일을 잊으셨나요?</button>

            <p class="login-copy">

            </p>

            <p v-if="errorMessage" class="login-error">{{ errorMessage }}</p>

            <div class="login-actions">
              <button class="login-create" type="button">계정 만들기</button>
              <button class="login-btn" type="submit" :disabled="!canSubmit">
                {{ isSubmitting ? '로그인 중' : '다음' }}
              </button>
            </div>
          </template>
        </form>
      </div>
    </section>

    <footer class="login-footer">
      <span>한국어</span>
      <nav aria-label="로그인 도움말">
        <a href="#" @click.prevent>도움말</a>
        <a href="#" @click.prevent>개인정보처리방침</a>
        <a href="#" @click.prevent>약관</a>
      </nav>
    </footer>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

type GoogleCredentialResponse = {
  credential?: string
}

type GooglePromptNotification = {
  isNotDisplayed?: () => boolean
  isSkippedMoment?: () => boolean
  isDismissedMoment?: () => boolean
}

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: {
            client_id: string
            callback: (response: GoogleCredentialResponse) => void
            ux_mode?: 'popup' | 'redirect'
            login_hint?: string
          }) => void
          prompt: (callback?: (notification: GooglePromptNotification) => void) => void
        }
      }
    }
  }
}

const GOOGLE_SCRIPT_ID = 'google-identity-service'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const isLoading = ref(true)
const isSubmitting = ref(false)
const googleReady = ref(false)
const loginIdentifier = ref('')
const errorMessage = ref('')
const canSubmit = computed(() => googleReady.value && !isSubmitting.value)

onMounted(async () => {
  try {
    await authStore.initialize()
    if (!authStore.authEnabled || authStore.isAuthenticated) {
      await router.replace(redirectTarget())
      return
    }
    await loadGoogleScript()
    googleReady.value = authStore.googleConfigured && Boolean(window.google)
    if (!googleReady.value) {
      errorMessage.value = 'Google 로그인 설정을 확인하세요.'
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '로그인 화면을 준비하지 못했습니다.'
  } finally {
    isLoading.value = false
  }
})

async function handleGoogleLogin() {
  if (!canSubmit.value) return
  if (!loginIdentifier.value) {
    errorMessage.value = 'ID 또는 이메일을 입력하세요.'
    return
  }
  errorMessage.value = ''
  isSubmitting.value = true
  try {
    window.google?.accounts.id.initialize({
      client_id: authStore.googleClientId,
      callback: handleGoogleCredential,
      ux_mode: 'popup',
      login_hint: loginIdentifier.value
    })
    window.google?.accounts.id.prompt((notification) => {
      if (notification.isNotDisplayed?.() || notification.isSkippedMoment?.() || notification.isDismissedMoment?.()) {
        isSubmitting.value = false
        errorMessage.value = 'Google 로그인 창을 열 수 없습니다.'
      }
    })
  } catch (error) {
    isSubmitting.value = false
    errorMessage.value = error instanceof Error ? error.message : 'Google 로그인에 실패했습니다.'
  }
}

async function handleGoogleCredential(response: GoogleCredentialResponse) {
  if (!response.credential) {
    isSubmitting.value = false
    errorMessage.value = 'Google credential이 비어 있습니다.'
    return
  }
  try {
    await authStore.loginWithGoogle(response.credential)
    await router.replace(redirectTarget())
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'Google 로그인에 실패했습니다.'
  } finally {
    isSubmitting.value = false
  }
}

function loadGoogleScript() {
  if (window.google) return Promise.resolve()
  const existingScript = document.getElementById(GOOGLE_SCRIPT_ID) as HTMLScriptElement | null
  if (existingScript) {
    return new Promise<void>((resolve, reject) => {
      existingScript.addEventListener('load', () => resolve(), { once: true })
      existingScript.addEventListener('error', () => reject(new Error('Google 로그인 스크립트를 불러오지 못했습니다.')), { once: true })
    })
  }

  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.id = GOOGLE_SCRIPT_ID
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    script.defer = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Google 로그인 스크립트를 불러오지 못했습니다.'))
    document.head.appendChild(script)
  })
}

function redirectTarget() {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/dashboard'
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-rows: 1fr auto;
  align-items: center;
  justify-items: center;
  background: #eef3f9;
  /* 상하 여백 조정 */
  padding: 3rem 1.5rem 8rem;
}

.login-panel {
  /* 포털 화면에 맞춰 전체 박스 넓이를 조금 더 넓게 조정 (기존 44.8rem -> 52rem) */
  width: min(52rem, 100%);
  /* 최소 높이 추가로 안정감 부여 */
  min-height: 28rem;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #e4e7eb;
  border-radius: 22px;
  background: #ffffff;
  /* 그림자 효과 추가로 입체감 부여 (선택 사항) */
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}

.login-panel-top {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  /* 상단 바 높이 소폭 증가 */
  min-height: 3rem;
  border-bottom: 1px solid #d8dde3;
  padding: 0 1.2rem;
  color: #2f3337;
  font-size: 0.85rem;
  font-weight: 700;
}

.login-top-icon {
  width: 1.2rem;
  height: 1.2rem;
  object-fit: contain;
}

.login-body {
  flex: 1;
  display: grid;
  /* 폼 영역(우측) 넓이를 더 여유있게 확보 (기존 19.3rem -> 22rem) */
  grid-template-columns: minmax(0, 1fr) minmax(20rem, 22rem);
  gap: 5rem;
  /* 내부 패딩을 넓혀서 답답하지 않게 조정 */
  padding: 3rem 3.5rem;
}

.login-brand {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 100%;
}

.login-logo {
  width: 3rem;
  height: auto;
  object-fit: contain;
  margin-bottom: 1rem;
}

.login-panel h1 {
  margin: 0 0 0.5rem;
  color: #202124;
  /* 타이틀 크기 소폭 확대 */
  font-size: 2.2rem;
  font-weight: 500;
  letter-spacing: -0.5px;
  line-height: 1.3;
}

.login-brand p {
  margin: 0;
  color: #5f6368;
  font-size: 1.05rem;
}

.login-brand strong {
  color: #0b57d0;
  font-weight: 700;
}

.login-form {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 100%;
  padding-top: 7.25rem;
  box-sizing: border-box;
}

.login-field {
  position: relative;
  width: 100%;
}

.login-field span {
  position: absolute;
  top: -0.45rem;
  left: 0.72rem;
  background: #ffffff;
  color: #0b57d0;
  padding: 0 0.25rem;
  font-size: 0.75rem;
  font-weight: 700;
  pointer-events: none;
}

.login-field input {
  width: 100%;
  /* 입력창 높이 소폭 증가 */
  min-height: 3.2rem;
  border: 2px solid #0b57d0;
  border-radius: 6px;
  color: #202124;
  font-size: 1.05rem;
  padding: 0.6rem 0.85rem;
  outline: 0;
}

.login-field input:disabled {
  background: #f8fafd;
  border-color: #d8dde3;
  color: #5f6368;
}

.login-help,
.login-create {
  width: fit-content;
  border: 0;
  background: transparent;
  color: #0b57d0;
  font-size: 0.85rem;
  font-weight: 700;
  cursor: pointer;
}

.login-help {
  margin-top: 0.6rem;
  padding: 0;
}

.login-copy {
  margin: 2.5rem 0 0;
  color: #3c4043;
  font-size: 0.8rem;
  line-height: 1.45;
}

.login-btn {
  min-width: 4.5rem;
  min-height: 2.6rem;
  border: 0;
  border-radius: 999px;
  background: #0b57d0;
  color: #ffffff;
  padding: 0 1.5rem;
  font-size: 0.9rem;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.2s ease;
}

.login-btn:hover:not(:disabled) {
  background: #0842a0;
}

.login-btn:disabled {
  background: #a8c7fa;
  cursor: not-allowed;
}

.login-state {
  margin-top: 1rem;
  color: #5f6368;
  font-size: 0.9rem;
}

.login-state.warn,
.login-error {
  color: #b3261e;
}

.login-error {
  margin: 0.8rem 0 0;
  font-size: 0.85rem;
  font-weight: 700;
}

.login-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 1.5rem;
  margin-top: auto;
  padding-top: 2.5rem;
}

.login-create {
  padding: 0.6rem 0.5rem;
}

.login-footer {
  /* 푸터 넓이도 본문과 맞춤 */
  width: min(52rem, 100%);
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 1.5rem;
  color: #5f6368;
  font-size: 0.8rem;
}

.login-footer nav {
  display: flex;
  gap: 2rem;
}

.login-footer a {
  color: inherit;
  text-decoration: none;
}

.login-footer a:hover {
  text-decoration: underline;
}

/* 모바일/태블릿 반응형 처리 */
@media (max-width: 820px) {
  .login-page {
    align-items: start;
    padding: 1.5rem 1rem;
  }

  .login-panel {
    min-height: auto;
    border-radius: 16px;
  }

  .login-body {
    grid-template-columns: 1fr;
    gap: 2.5rem;
    padding: 2rem 1.5rem;
  }

  .login-brand {
    min-height: auto;
    text-align: center;
    align-items: center;
  }

  .login-form {
    min-height: auto;
  }

  .login-actions {
    padding-top: 2rem;
  }

  .login-footer {
    flex-direction: column;
    align-items: center;
    gap: 1.2rem;
  }

  .login-footer nav {
    gap: 1.5rem;
    flex-wrap: wrap;
    justify-content: center;
  }
}
</style>

<template>
  <main class="auth-callback" aria-live="polite">
    <p v-if="errorMessage" class="auth-callback__error">{{ errorMessage }}</p>
    <p v-else>로그인 처리 중입니다.</p>
    <button v-if="errorMessage && authStore.googleConfigured" type="button" @click="startGoogleLogin">
      다시 로그인
    </button>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

type GoogleRedirectResult = {
  idToken: string
  state: string
  error: string
  errorDescription: string
}

const GOOGLE_STATE_KEY = 'sqladvisor.google.state'
const GOOGLE_NONCE_KEY = 'sqladvisor.google.nonce'
const GOOGLE_REDIRECT_KEY = 'sqladvisor.google.redirect'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const errorMessage = ref('')

onMounted(async () => {
  try {
    await authStore.initialize()
    if (!authStore.authEnabled) {
      await router.replace(redirectTarget())
      return
    }

    const redirectResult = readGoogleRedirectResult()
    if (redirectResult) {
      await completeGoogleRedirect(redirectResult)
      return
    }

    if (authStore.isAuthenticated) {
      await router.replace(redirectTarget())
      return
    }

    if (!authStore.googleConfigured) {
      throw new Error('Google 로그인 설정을 확인하세요.')
    }

    startGoogleLogin()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '로그인을 처리하지 못했습니다.'
  }
})

function startGoogleLogin() {
  errorMessage.value = ''

  const state = randomToken()
  const nonce = randomToken()
  sessionStorage.setItem(GOOGLE_STATE_KEY, state)
  sessionStorage.setItem(GOOGLE_NONCE_KEY, nonce)
  sessionStorage.setItem(GOOGLE_REDIRECT_KEY, redirectTarget())

  const params = new URLSearchParams({
    client_id: authStore.googleClientId,
    redirect_uri: `${window.location.origin}/login`,
    response_type: 'id_token',
    scope: 'openid email profile',
    state,
    nonce,
    prompt: 'select_account'
  })

  window.location.assign(`https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`)
}

async function completeGoogleRedirect(result: GoogleRedirectResult) {
  clearUrlHash()
  if (result.error) {
    clearStoredGoogleLogin()
    throw new Error(result.errorDescription || result.error)
  }

  const expectedState = sessionStorage.getItem(GOOGLE_STATE_KEY)
  if (!expectedState || result.state !== expectedState) {
    clearStoredGoogleLogin()
    throw new Error('Google 로그인 상태값이 일치하지 않습니다.')
  }

  if (!result.idToken) {
    clearStoredGoogleLogin()
    throw new Error('Google credential이 비어 있습니다.')
  }

  const nonce = sessionStorage.getItem(GOOGLE_NONCE_KEY) || ''
  const storedRedirect = sessionStorage.getItem(GOOGLE_REDIRECT_KEY) || '/dashboard'
  clearStoredGoogleLogin()
  await authStore.loginWithGoogle(result.idToken, nonce)
  await router.replace(safeRedirect(storedRedirect))
}

function readGoogleRedirectResult(): GoogleRedirectResult | null {
  if (!window.location.hash) return null
  const params = new URLSearchParams(window.location.hash.slice(1))
  if (!params.has('id_token') && !params.has('error')) return null
  return {
    idToken: params.get('id_token') || '',
    state: params.get('state') || '',
    error: params.get('error') || '',
    errorDescription: params.get('error_description') || ''
  }
}

function clearUrlHash() {
  const url = new URL(window.location.href)
  url.hash = ''
  window.history.replaceState(null, document.title, `${url.pathname}${url.search}`)
}

function clearStoredGoogleLogin() {
  sessionStorage.removeItem(GOOGLE_STATE_KEY)
  sessionStorage.removeItem(GOOGLE_NONCE_KEY)
  sessionStorage.removeItem(GOOGLE_REDIRECT_KEY)
}

function redirectTarget() {
  const redirect = route.query.redirect
  return safeRedirect(typeof redirect === 'string' ? redirect : '/dashboard')
}

function safeRedirect(value: string) {
  return value.startsWith('/') && !value.startsWith('//') ? value : '/dashboard'
}

function randomToken() {
  const bytes = new Uint8Array(16)
  window.crypto.getRandomValues(bytes)
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}
</script>

<style scoped>
.auth-callback {
  min-height: 100vh;
  display: grid;
  place-content: center;
  gap: 1rem;
  background: #eef3f9;
  color: #202124;
  font-size: 0.95rem;
}

.auth-callback__error {
  color: #b3261e;
  font-weight: 700;
}

.auth-callback button {
  min-height: 2.5rem;
  border: 0;
  border-radius: 999px;
  background: #0b57d0;
  color: #ffffff;
  padding: 0 1.4rem;
  font-weight: 700;
  cursor: pointer;
}
</style>

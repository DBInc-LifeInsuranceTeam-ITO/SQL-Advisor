import { defineStore } from 'pinia'
import { getAuthConfig, getCurrentUser, loginWithGoogleCredential, loginWithInternalIdentifier, loginWithLocalIdentifier, logout } from '@/api/auth'
import type { AuthConfigResponse, CurrentUserResponse, UserRole } from '@/types/auth'

function normalizeRole(role?: string | null): UserRole {
  const normalized = role?.trim().toUpperCase()
  if (normalized === 'ADMIN') return 'ADMIN'
  if (normalized === 'MONITOR') return 'MONITOR'
  return 'USER'
}

const INTERNAL_LOGIN_KEY = 'loginEno'
let initializationPromise: Promise<void> | null = null

export const useAuthStore = defineStore('auth', {
  state: () => ({
    config: null as AuthConfigResponse | null,
    user: null as CurrentUserResponse | null,
    initialized: false,
    isLoading: false
  }),
  getters: {
    authEnabled: (state) => Boolean(state.config?.authEnabled),
    authMode: (state) => state.config?.authMode || 'external',
    googleConfigured: (state) => Boolean(state.config?.googleConfigured),
    googleClientId: (state) => state.config?.googleClientId || '',
    internalLoginEnabled: (state) => Boolean(state.config?.internalLoginEnabled),
    localLoginEnabled: (state) => Boolean(state.config?.localLoginEnabled),
    isAuthenticated: (state) => Boolean(state.user?.authenticated),
    normalizedRole: (state) => normalizeRole(state.user?.role),
    isAdmin: (state) => normalizeRole(state.user?.role) === 'ADMIN',
    isUser: (state) => normalizeRole(state.user?.role) === 'USER',
    isMonitor: (state) => normalizeRole(state.user?.role) === 'MONITOR',

    // 조회 가능 권한
    canRead: (state) => ['ADMIN', 'USER', 'MONITOR'].includes(normalizeRole(state.user?.role)),

    // 쓰기/실행 가능 권한
    canWrite: (state) => ['ADMIN', 'USER'].includes(normalizeRole(state.user?.role))

  },
  actions: {
    async initialize() {
      if (this.initialized) return
      if (initializationPromise) return initializationPromise
      this.isLoading = true
      initializationPromise = (async () => {
        this.config = await getAuthConfig()
        if (this.config.authEnabled) {
          this.user = await getCurrentUser()
        } else {
          this.user = { authenticated: false, authProviders: [] }
        }
      })()
      try {
        await initializationPromise
      } finally {
        initializationPromise = null
        this.initialized = true
        this.isLoading = false
      }
    },
    async refreshUser() {
      this.user = await getCurrentUser()
      return this.user
    },
    async loginWithGoogle(credential: string, nonce?: string) {
      this.user = await loginWithGoogleCredential(credential, nonce)
      return this.user
    },
    async loginWithLocal(identifier: string) {
      this.user = await loginWithLocalIdentifier(identifier)
      return this.user
    },
    async loginWithInternal(identifier?: string) {
      this.user = await loginWithInternalIdentifier(identifier)
      return this.user
    },
    async logout() {
      await logout()
      if (this.authMode === 'internal') {
        localStorage.removeItem(INTERNAL_LOGIN_KEY)
      }
      this.user = { authenticated: false, authProviders: [] }
      this.initialized = false
      this.config = null
    }
  }
})

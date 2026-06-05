import { defineStore } from 'pinia'
import { getAuthConfig, getCurrentUser, loginWithGoogleCredential, loginWithInternalIdentifier, loginWithLocalIdentifier, logout } from '@/api/auth'
import type { AuthConfigResponse, CurrentUserResponse } from '@/types/auth'

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
    isAdmin: (state) => state.user?.role === 'ADMIN'
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
    }
  }
})

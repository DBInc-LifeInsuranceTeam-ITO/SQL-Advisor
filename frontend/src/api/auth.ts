import api from '@/services/api'
import type { AuthConfigResponse, CurrentUserResponse } from '@/types/auth'

export async function getAuthConfig() {
  const response = await api.get<AuthConfigResponse>('/auth/config')
  return response.data
}

export async function getCurrentUser() {
  const response = await api.get<CurrentUserResponse>('/auth/me')
  return response.data
}

export async function loginWithGoogleCredential(credential: string, nonce?: string) {
  const response = await api.post<CurrentUserResponse>('/auth/google', { credential, nonce })
  return response.data
}

export async function loginWithLocalIdentifier(identifier: string) {
  const response = await api.post<CurrentUserResponse>('/auth/local', { identifier })
  return response.data
}

export async function loginWithInternalIdentifier(identifier?: string) {
  const response = await api.post<CurrentUserResponse>('/auth/internal', identifier ? { identifier } : {})
  return response.data
}

export async function logout() {
  await api.post<void>('/auth/logout')
}

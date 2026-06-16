import api from '@/services/api'
import type {
  AuthConfigResponse,
  CurrentUserResponse,
  UpdateUserRoleRequest,
  UserSummaryResponse
} from '@/types/auth'

type ApiResponse<T> = {
  success?: boolean
  message?: string
  data?: T
  result?: T
}

function unwrap<T>(value: ApiResponse<T> | T): T {
  const wrapped = value as ApiResponse<T>

  if (wrapped?.data !== undefined) {
    return wrapped.data
  }

  if (wrapped?.result !== undefined) {
    return wrapped.result
  }

  return value as T
}

export async function getAuthConfig() {
  const response = await api.get<AuthConfigResponse | ApiResponse<AuthConfigResponse>>('/auth/config')
  return unwrap<AuthConfigResponse>(response.data)
}

export async function getCurrentUser() {
  const response = await api.get<CurrentUserResponse | ApiResponse<CurrentUserResponse>>('/auth/me')
  return unwrap<CurrentUserResponse>(response.data)
}

export async function loginWithGoogleCredential(credential: string, nonce?: string) {
  const response = await api.post<CurrentUserResponse | ApiResponse<CurrentUserResponse>>(
    '/auth/google',
    { credential, nonce }
  )
  return unwrap<CurrentUserResponse>(response.data)
}

export async function loginWithLocalIdentifier(identifier: string) {
  const response = await api.post<CurrentUserResponse | ApiResponse<CurrentUserResponse>>(
    '/auth/local',
    { identifier }
  )
  return unwrap<CurrentUserResponse>(response.data)
}

export async function loginWithInternalIdentifier(identifier?: string) {
  const response = await api.post<CurrentUserResponse | ApiResponse<CurrentUserResponse>>(
    '/auth/internal',
    identifier ? { identifier } : {}
  )
  return unwrap<CurrentUserResponse>(response.data)
}

export async function getUsers() {
  const response = await api.get<UserSummaryResponse[] | ApiResponse<UserSummaryResponse[]>>('/admin/users')
  return unwrap<UserSummaryResponse[]>(response.data)
}

export async function updateUserRole(userId: number, payload: UpdateUserRoleRequest) {
  const response = await api.patch<UserSummaryResponse | ApiResponse<UserSummaryResponse>>(
    `/admin/users/${userId}/role`,
    payload
  )
  return unwrap<UserSummaryResponse>(response.data)
}

export async function logout() {
  await api.post<void>('/auth/logout')
}

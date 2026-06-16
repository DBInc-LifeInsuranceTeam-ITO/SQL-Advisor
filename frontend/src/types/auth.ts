export type UserRole = 'ADMIN' | 'USER' | 'MONITOR'

export interface AuthConfigResponse {
  authEnabled: boolean
  authMode: string
  googleConfigured: boolean
  googleClientId: string
  internalLoginEnabled: boolean
  localLoginEnabled: boolean
}

export interface CurrentUserResponse {
  authenticated: boolean
  id?: number | null
  email?: string | null
  displayName?: string | null
  pictureUrl?: string | null
  role?: UserRole | string | null
  authProviders: string[]
}

export interface UserSummaryResponse {
  id: number
  email: string
  displayName?: string | null
  pictureUrl?: string | null
  role: UserRole | string
  enabled: boolean
  authProviders: string[]
}

export interface UpdateUserRoleRequest {
  role: UserRole
}

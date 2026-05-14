export interface AuthConfigResponse {
  authEnabled: boolean
  googleConfigured: boolean
  googleClientId: string
  localLoginEnabled: boolean
}

export interface CurrentUserResponse {
  authenticated: boolean
  id?: number | null
  email?: string | null
  displayName?: string | null
  pictureUrl?: string | null
  role?: string | null
  authProviders: string[]
}

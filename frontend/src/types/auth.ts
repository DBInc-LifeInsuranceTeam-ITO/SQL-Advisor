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
  role?: string | null
  authProviders: string[]
}

import axios, { type AxiosError, type AxiosInstance } from 'axios'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'
const INTERNAL_LOGIN_KEY = 'loginEno'

export const api: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // CORS 쿠키 전송 허용
  paramsSerializer: { indexes: null }
})

api.interceptors.request.use(
  (config) => {
    const loginEno = localStorage.getItem(INTERNAL_LOGIN_KEY)?.trim()
    if (loginEno) {
      config.headers['X-Login-Eno'] = loginEno
    }

    if (import.meta.env.DEV) {
      console.log(`[API Request] ${config.method?.toUpperCase()} ${config.url}`, maskSensitive(config.data))
    }
    return config
  },
  (error) => {
    console.error('[API Request Error]', error)
    return Promise.reject(error)
  }
)

api.interceptors.response.use(
  (response) => {
    if (import.meta.env.DEV) {
      console.log(`[API Response] ${response.config.method?.toUpperCase()} ${response.config.url}`, response.data)
    }

    if (response.data && typeof response.data === 'object' && 'success' in response.data) {
      if (response.data.success) {
        response.data = response.data.data || response.data
      } else {
        return Promise.reject(new Error(response.data.error?.message || '요청 처리 실패'))
      }
    }
    
    return response
  },
  (error: AxiosError) => {
    console.error('[API Response Error]', error)
    let message = error.message

    if (error.response) {
      const status = error.response.status
      const data = error.response.data as { error?: { message?: string }; message?: string }
      message = data?.error?.message || data?.message || message

      switch (status) {
        case 400:
          console.error('잘못된 요청:', data?.error?.message || data?.message || '요청 형식이 올바르지 않습니다.')
          break
        case 401:
          console.error('인증 필요:', data?.error?.message || '로그인이 필요합니다.')
          // 필요시 로그인 페이지로 리다이렉트
          break
        case 403:
          console.error('권한 없음:', data?.error?.message || '접근 권한이 없습니다.')
          break
        case 404:
          console.error('리소스 없음:', data?.error?.message || '요청한 리소스를 찾을 수 없습니다.')
          break
        case 500:
          console.error('서버 오류:', data?.error?.message || '서버 내부 오류가 발생했습니다.')
          break
        default:
          console.error(`HTTP ${status} 오류:`, data?.error?.message || error.message)
      }
    } else if (error.request) {
      console.error('네트워크 오류: 서버에 연결할 수 없습니다.')
      message = '서버에 연결할 수 없습니다.'
    } else {
      console.error('요청 오류:', error.message)
    }

    return Promise.reject(new Error(message))
  }
)

export const apiService = {
  async get<T = unknown>(url: string, params?: Record<string, unknown>) {
    const response = await api.get<T>(url, { params })
    return response.data
  },

  async post<T = unknown>(url: string, data?: unknown) {
    const response = await api.post<T>(url, data)
    return response.data
  },

  async put<T = unknown>(url: string, data?: unknown) {
    const response = await api.put<T>(url, data)
    return response.data
  },

  async delete<T = unknown>(url: string) {
    const response = await api.delete<T>(url)
    return response.data
  },

  async download(url: string, filename?: string) {
    const response = await api.get(url, {
      responseType: 'blob'
    })

    const blob = new Blob([response.data])
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = filename || 'download'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
  }
}

function maskSensitive(value: unknown): unknown {
  if (!value || typeof value !== 'object' || value instanceof FormData) {
    return value
  }
  if (Array.isArray(value)) {
    return value.map((item) => maskSensitive(item))
  }
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).map(([key, item]) => [
      key,
      key.toLowerCase().includes('apikey') ? '***' : maskSensitive(item)
    ])
  )
}

export default api

import type { AuthUser } from './types'

const TOKEN_KEY = 'telerad_auth'

export function loadAuth(): AuthUser | null {
  const raw = localStorage.getItem(TOKEN_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    return null
  }
}

export function saveAuth(user: AuthUser) {
  localStorage.setItem(TOKEN_KEY, JSON.stringify(user))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
}

export async function api<T>(
  path: string,
  options: RequestInit = {},
  token?: string | null,
): Promise<T> {
  const headers = new Headers(options.headers || {})
  if (!(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const auth = token ?? loadAuth()?.accessToken
  if (auth) headers.set('Authorization', `Bearer ${auth}`)

  const res = await fetch(path, { ...options, headers })
  if (!res.ok) {
    let message = res.statusText
    try {
      const body = await res.json()
      message = body.error || body.message || message
    } catch {
      /* ignore */
    }
    // Expired/missing session (401, or 403 when no stored user) must not look like a
    // broken page: drop the stale token and send the user to the login screen.
    if (res.status === 401 || (res.status === 403 && !loadAuth())) {
      clearAuth()
      if (!location.pathname.startsWith('/login')) {
        location.href = '/login?expired=1'
      }
    }
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  const ct = res.headers.get('content-type') || ''
  if (ct.includes('application/json')) return res.json()
  return res as unknown as T
}

export async function apiBlob(path: string): Promise<Blob> {
  const auth = loadAuth()?.accessToken
  const res = await fetch(path, {
    headers: auth ? { Authorization: `Bearer ${auth}` } : {},
  })
  if (!res.ok) throw new Error('Download failed')
  return res.blob()
}

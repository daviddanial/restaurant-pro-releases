import type { AuthUser } from './types'

const USER_KEY = 'telerad_auth'
const SHARE_KEY = 'telerad_share_token'

/** Prefer share session token, else staff JWT — used by api + Cornerstone WADO. */
export function getBearerToken(): string | null {
  try {
    const share = sessionStorage.getItem(SHARE_KEY)
    if (share) return share
  } catch {
    /* ignore */
  }
  try {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) return null
    const user = JSON.parse(raw) as AuthUser
    return user.accessToken || null
  } catch {
    return null
  }
}

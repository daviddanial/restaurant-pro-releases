const SHARE_TOKEN_KEY = 'telerad_share_token'

export function saveShareToken(token: string) {
  sessionStorage.setItem(SHARE_TOKEN_KEY, token)
}

export function loadShareToken(): string | null {
  return sessionStorage.getItem(SHARE_TOKEN_KEY)
}

export function clearShareToken() {
  sessionStorage.removeItem(SHARE_TOKEN_KEY)
  sessionStorage.removeItem('telerad_share_main_study')
}

export async function shareApi<T>(path: string, options: RequestInit = {}, token?: string | null): Promise<T> {
  const headers = new Headers(options.headers || {})
  if (!(options.body instanceof FormData) && !headers.has('Content-Type') && options.body) {
    headers.set('Content-Type', 'application/json')
  }
  const auth = token ?? loadShareToken()
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
    throw new Error(message)
  }
  if (res.status === 204) return undefined as T
  const ct = res.headers.get('content-type') || ''
  if (ct.includes('application/json')) return res.json()
  return res as unknown as T
}

export async function shareBlob(path: string): Promise<Blob> {
  const auth = loadShareToken()
  const res = await fetch(path, {
    headers: auth ? { Authorization: `Bearer ${auth}` } : {},
  })
  if (!res.ok) throw new Error('Download failed')
  return res.blob()
}

export type ShareCreateResult = {
  shareId: string
  url: string
  accessCode: string
  expiresAt: string
  mainStudyId: string
  priorCount: number
  studyCount: number
  facilityName: string
  patientLabel: string | null
  modality: string | null
  studyDescription: string | null
  studyDate: string | null
}

export type ShareListItem = {
  id: string
  patientId: string
  mainStudyId: string | null
  patientName: string | null
  createdAt: string
  expiresAt: string
  revokedAt: string | null
  studyCount: number
  priorCount: number
  includeReports: boolean
  allowDicomDownload: boolean
  active: boolean
}

export type ShareStudyItem = {
  id: string
  studyInstanceUid: string
  modality: string | null
  studyDescription: string | null
  studyDate: string | null
  status: string
  instanceCount: number
  hasSignedReport: boolean
  signedReportId: string | null
}

export type ShareStudyDetail = {
  study: ShareStudyItem
  series: {
    id: string
    seriesInstanceUid: string
    modality: string | null
    seriesNumber: number | null
    seriesDescription: string | null
    instanceCount: number
    instances: {
      id: string
      sopInstanceUid: string
      instanceNumber: number | null
      rows: number | null
      columns: number | null
      numberOfFrames: number
    }[]
  }[]
}

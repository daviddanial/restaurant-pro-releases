import { api } from './api'
import { getBearerToken } from './authToken'
import type { CloudStatus, StudyListItem } from './types'

/**
 * Where the viewer loads a study's images from (KB note 13).
 *
 * Inside the hospital the images come straight from the hospital server over the hospital
 * network, with a short-lived image pass from the cloud; elsewhere they come from the cloud
 * copy. The choice is automatic: if the hospital server answers, it is used.
 */

export interface ImageAccess {
  cloudStatus: CloudStatus
  onHospitalServer: boolean
  gatewayId: string | null
  lanUrl: string | null
  cloudInstances: number
  totalInstances: number
  cloudRequestedAt: string | null
}

interface EdgePass {
  gatewayId: string
  lanUrl: string
  studyInstanceUid: string
  token: string
  expiresAt: string
}

export type ImageSource =
  | { kind: 'cloud' }
  | { kind: 'hospital'; base: string }
  /** Images only on the hospital server, which this browser cannot reach (or no longer stored). */
  | { kind: 'unavailable'; access: ImageAccess | null }

interface EdgeEntry {
  base: string
  token: string
  expiresAt: number
  studyId: string
  refresh: ReturnType<typeof setTimeout> | null
}

/** Studies (by Study Instance UID) whose images load from a hospital server. */
const edgeStudies = new Map<string, EdgeEntry>()
/** Hospital server base URL → last probe result. */
const probes = new Map<string, { ok: boolean; at: number; pending?: Promise<boolean> }>()

const PROBE_TIMEOUT_MS = 2500
const PROBE_OK_TTL_MS = 60_000
const PROBE_FAIL_TTL_MS = 15_000
const REFRESH_BEFORE_MS = 5 * 60_000

export const cloudHasImages = (s: CloudStatus | undefined | null) =>
  s == null || s === 'AVAILABLE' || s === 'PENDING_DELETE'

/** Base for the DICOM paths (`…/studies/{study}/series/{series}/instances/{sop}`). */
export function imageBase(studyUid: string): string {
  const edge = edgeStudies.get(studyUid)
  if (edge) return `${edge.base}/dicom`
  return new URL('/api/dicom', window.location.origin).href
}

export function isHospitalSource(studyUid: string): boolean {
  return edgeStudies.has(studyUid)
}

/** Authorization header for an image URL: the image pass for the hospital server, else the session token. */
export function imageAuthHeaders(url: string): Record<string, string> {
  for (const [studyUid, edge] of edgeStudies) {
    if (url.startsWith(`${edge.base}/dicom/studies/${studyUid}/`)) {
      return { Authorization: `Bearer ${edge.token}` }
    }
  }
  if (isOtherOrigin(url)) return {} // never send the cloud session token anywhere else
  const token = getBearerToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function isOtherOrigin(url: string): boolean {
  try {
    return new URL(url, window.location.origin).origin !== window.location.origin
  } catch {
    return true
  }
}

/** True when the hospital server answers from this browser (cached briefly). */
export function probeHospitalServer(base: string): Promise<boolean> {
  const now = Date.now()
  const hit = probes.get(base)
  if (hit?.pending) return hit.pending
  if (hit && now - hit.at < (hit.ok ? PROBE_OK_TTL_MS : PROBE_FAIL_TTL_MS)) return Promise.resolve(hit.ok)
  const pending = (async () => {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), PROBE_TIMEOUT_MS)
    try {
      const res = await fetch(`${base}/health`, { cache: 'no-store', signal: ctrl.signal, credentials: 'omit' })
      if (!res.ok) return false
      const body = (await res.json()) as { ok?: boolean }
      return body.ok === true
    } catch {
      return false
    } finally {
      clearTimeout(timer)
    }
  })()
  probes.set(base, { ok: hit?.ok ?? false, at: hit?.at ?? 0, pending })
  return pending.then((ok) => {
    probes.set(base, { ok, at: Date.now() })
    return ok
  })
}

async function fetchPass(studyId: string): Promise<EdgePass> {
  return api<EdgePass>(`/api/studies/${studyId}/edge-pass`, { method: 'POST' })
}

function register(studyId: string, pass: EdgePass) {
  const prev = edgeStudies.get(pass.studyInstanceUid)
  if (prev?.refresh) clearTimeout(prev.refresh)
  const expiresAt = Date.parse(pass.expiresAt)
  const entry: EdgeEntry = {
    base: pass.lanUrl.replace(/\/+$/, ''),
    token: pass.token,
    expiresAt,
    studyId,
    refresh: null,
  }
  // Renew the pass before it runs out, so a long read never stops loading images.
  const delay = Math.max(30_000, expiresAt - Date.now() - REFRESH_BEFORE_MS)
  entry.refresh = setTimeout(() => {
    fetchPass(studyId)
      .then((next) => register(studyId, next))
      .catch(() => { /* keep the current pass; it still works until it expires */ })
  }, delay)
  edgeStudies.set(pass.studyInstanceUid, entry)
}

export function forgetHospitalSource(studyUid: string) {
  const entry = edgeStudies.get(studyUid)
  if (entry?.refresh) clearTimeout(entry.refresh)
  edgeStudies.delete(studyUid)
}

export const fetchImageAccess = (studyId: string) => api<ImageAccess>(`/api/studies/${studyId}/image-access`)

export const requestCloudCopy = (studyId: string) =>
  api<ImageAccess>(`/api/studies/${studyId}/cloud-copy`, { method: 'POST' })

/**
 * Decide where to load a study's images from, and get an image pass when it is the hospital
 * server. Call before building image IDs for the study.
 */
export async function resolveImageSource(
  study: Pick<StudyListItem, 'id' | 'studyInstanceUid' | 'cloudStatus' | 'onHospitalServer'>,
): Promise<ImageSource> {
  const inCloud = cloudHasImages(study.cloudStatus)
  if (!study.onHospitalServer) {
    forgetHospitalSource(study.studyInstanceUid)
    return inCloud ? { kind: 'cloud' } : { kind: 'unavailable', access: null }
  }
  let access: ImageAccess | null = null
  try {
    access = await fetchImageAccess(study.id)
  } catch {
    access = null
  }
  const lanUrl = access?.lanUrl?.replace(/\/+$/, '')
  if (lanUrl && (await probeHospitalServer(lanUrl))) {
    try {
      register(study.id, await fetchPass(study.id))
      return { kind: 'hospital', base: lanUrl }
    } catch {
      /* fall back to the cloud below */
    }
  }
  forgetHospitalSource(study.studyInstanceUid)
  const cloudNow = access ? cloudHasImages(access.cloudStatus) : inCloud
  return cloudNow ? { kind: 'cloud' } : { kind: 'unavailable', access }
}

/** Test hook. */
export function _resetImageSources() {
  for (const uid of [...edgeStudies.keys()]) forgetHospitalSource(uid)
  probes.clear()
}

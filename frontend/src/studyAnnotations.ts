import { api, apiBlob } from './api'

/** Measurement saved with a study (server DTO). `data` is the viewer's own JSON. */
export interface SavedMeasurement {
  id: string
  seriesInstanceUid: string
  sopInstanceUid: string
  frameNumber: number
  toolName: string
  data: string
  summary: string | null
  location: string | null
  createdBy: string
  createdByName: string | null
  mine: boolean
  createdAt: string
  updatedAt: string
}

export interface KeyImage {
  id: string
  seriesInstanceUid: string
  sopInstanceUid: string
  frameNumber: number
  windowCenter: number | null
  windowWidth: number | null
  location: string | null
  createdBy: string
  createdByName: string | null
  mine: boolean
  createdAt: string
  hasSnapshot: boolean
}

export interface KeyImageRequest {
  seriesInstanceUid: string
  sopInstanceUid: string
  frameNumber: number
  windowCenter: number | null
  windowWidth: number | null
  location: string
  snapshotDataUrl: string | null
}

// ------------------------------------------------------------------ API

/**
 * Per-study cache of saved measurements, kept in sync with this session's saves and deletes,
 * so viewports re-entering a series (or several panes) do not refetch or resurrect deleted ones.
 */
const measurementCache = new Map<string, Promise<Map<string, SavedMeasurement>>>()

export function loadMeasurements(studyId: string): Promise<Map<string, SavedMeasurement>> {
  let p = measurementCache.get(studyId)
  if (!p) {
    p = api<SavedMeasurement[]>(`/api/studies/${studyId}/measurements`)
      .then((rows) => new Map(rows.map((m) => [m.id, m])))
    p.catch(() => measurementCache.delete(studyId))
    measurementCache.set(studyId, p)
  }
  return p
}

export async function saveMeasurement(studyId: string, id: string, body: {
  seriesInstanceUid: string
  sopInstanceUid: string
  frameNumber: number
  toolName: string
  data: string
  summary: string | null
  location: string
}): Promise<SavedMeasurement> {
  const saved = await api<SavedMeasurement>(`/api/studies/${studyId}/measurements/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
  const cache = measurementCache.get(studyId)
  if (cache) (await cache).set(saved.id, saved)
  return saved
}

export async function deleteMeasurement(studyId: string, id: string): Promise<void> {
  await api(`/api/studies/${studyId}/measurements/${id}`, { method: 'DELETE' })
  const cache = measurementCache.get(studyId)
  if (cache) (await cache).delete(id)
}

/** Fresh list (report editor): bypasses and refreshes the cache. */
export async function fetchMeasurements(studyId: string): Promise<SavedMeasurement[]> {
  measurementCache.delete(studyId)
  return [...(await loadMeasurements(studyId)).values()]
}

export const listKeyImages = (studyId: string) => api<KeyImage[]>(`/api/studies/${studyId}/key-images`)

export const addKeyImage = (studyId: string, body: KeyImageRequest) =>
  api<KeyImage>(`/api/studies/${studyId}/key-images`, { method: 'POST', body: JSON.stringify(body) })

export const deleteKeyImage = (studyId: string, id: string) =>
  api<void>(`/api/studies/${studyId}/key-images/${id}`, { method: 'DELETE' })

export const keyImageBlob = (studyId: string, id: string) => apiBlob(`/api/studies/${studyId}/key-images/${id}/image`)

// ------------------------------------------------------------------ pure helpers

type Stats = Record<string, unknown>

const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)
const unitLabel = (u: unknown) => (typeof u === 'string' && u.trim() && u !== 'raw' ? ` ${u.trim()}` : '')
const fmt = (v: number, digits: number) => v.toFixed(digits)

/**
 * Human-readable value of a measurement, from the tool's cached statistics.
 * Returns null when the statistics are not computed yet.
 */
export function measurementSummary(toolName: string, cachedStats: Record<string, Stats> | undefined): string | null {
  const s = cachedStats ? Object.values(cachedStats).find((v) => v && typeof v === 'object') : undefined
  if (!s) return null
  switch (toolName) {
    case 'Length': {
      const len = num(s.length)
      return len == null ? null : `Length ${fmt(len, 1)}${unitLabel(s.unit ?? 'mm')}`
    }
    case 'Angle': {
      const a = num(s.angle)
      return a == null ? null : `Angle ${fmt(a, 1)}°`
    }
    case 'CobbAngle': {
      const a = num(s.angle)
      return a == null ? null : `Cobb angle ${fmt(a, 1)}°`
    }
    case 'EllipticalROI': {
      const area = num(s.area)
      const mean = num(s.mean)
      if (area == null) return null
      const u = unitLabel(s.modalityUnit)
      const parts = [`Ellipse area ${fmt(area, area >= 100 ? 0 : 1)}${unitLabel(s.areaUnit ?? 'mm²')}`]
      if (mean != null) parts.push(`mean ${fmt(mean, 1)}${u}`)
      const sd = num(s.stdDev), min = num(s.min), max = num(s.max)
      if (sd != null) parts.push(`SD ${fmt(sd, 1)}`)
      if (min != null && max != null) parts.push(`min ${fmt(min, 0)} / max ${fmt(max, 0)}${u}`)
      return parts.join(', ')
    }
    case 'StackProbe':
    case 'Probe': {
      const v = num(s.value)
      if (v == null) return null
      const u = typeof s.modalityUnit === 'string' ? s.modalityUnit : ''
      return u === 'HU' ? `Density ${fmt(v, 0)} HU` : `Signal ${fmt(v, 0)}${unitLabel(u)}`
    }
    default:
      return null
  }
}

/** "Liver_NC 2.00, image 45" (and ", frame 3" for multi-frame instances). */
export function locationLabel(seriesDescription: string | null | undefined, imageNumber: number, frame: number, multiFrame: boolean) {
  const series = seriesDescription?.trim() || 'Series'
  return `${series}, image ${imageNumber}${multiFrame ? `, frame ${frame}` : ''}`
}

/** Text block for the report's findings: one line per measurement, in drawing order. */
export function measurementsReportText(rows: SavedMeasurement[]): string {
  const lines = rows
    .filter((m) => m.summary)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .map((m) => `- ${m.summary}${m.location ? ` (${m.location})` : ''}`)
  return lines.length ? `MEASUREMENTS:\n${lines.join('\n')}` : ''
}

/** What the server stores for a measurement: everything needed to redraw it, minus the image URL. */
export function serializeAnnotation(ann: {
  metadata: Record<string, unknown>
  data: { handles?: unknown; label?: unknown }
}): string {
  const { referencedImageId: _drop, ...metadata } = ann.metadata
  void _drop
  return JSON.stringify({ metadata, data: { handles: ann.data.handles, label: ann.data.label ?? '' } })
}

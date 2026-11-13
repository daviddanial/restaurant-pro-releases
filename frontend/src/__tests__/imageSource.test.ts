import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { saveAuth } from '../api'
import {
  _resetImageSources,
  cloudHasImages,
  imageAuthHeaders,
  imageBase,
  isHospitalSource,
  resolveImageSource,
} from '../imageSource'
import { imageLocationLabel } from '../components/ImageLocationBadge'
import { clearDraftBackup, loadDraftBackup, sameDraft, saveDraftBackup } from '../reportDraftBackup'

const EDGE = 'https://h1.edge.example.et:8090'
const study = {
  id: 'st-1',
  studyInstanceUid: '1.2.3',
  cloudStatus: 'EDGE_ONLY' as const,
  onHospitalServer: true,
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** Routes: image-access, edge health, edge-pass. */
function mockFetch({ lanUrl = EDGE, edgeUp = true, cloudStatus = 'EDGE_ONLY' } = {}) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/image-access')) {
      return json({ cloudStatus, onHospitalServer: true, gatewayId: 'gw', lanUrl, cloudInstances: 0, totalInstances: 3, cloudRequestedAt: null })
    }
    if (url === `${EDGE}/health`) {
      if (!edgeUp) throw new TypeError('Failed to fetch')
      return json({ ok: true, gatewayId: 'gw' })
    }
    if (url.endsWith('/edge-pass')) {
      return json({ gatewayId: 'gw', lanUrl: EDGE, studyInstanceUid: '1.2.3', token: 'pass-abc',
        expiresAt: new Date(Date.now() + 3600_000).toISOString() })
    }
    return json({}, 404)
  })
}

describe('image source', () => {
  beforeEach(() => {
    _resetImageSources()
    saveAuth({ accessToken: 'cloud-session', userId: 'u', email: 'e', fullName: 'f', role: 'RADIOLOGIST',
      tenantId: null, tenantCode: null, tenantName: null })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    _resetImageSources()
  })

  it('loads from the hospital server when it answers, with the image pass', async () => {
    vi.stubGlobal('fetch', mockFetch())
    const source = await resolveImageSource(study)
    expect(source).toEqual({ kind: 'hospital', base: EDGE })
    expect(isHospitalSource('1.2.3')).toBe(true)
    expect(imageBase('1.2.3')).toBe(`${EDGE}/dicom`)
    const url = `${EDGE}/dicom/studies/1.2.3/series/4/instances/5`
    expect(imageAuthHeaders(url)).toEqual({ Authorization: 'Bearer pass-abc' })
  })

  it('never sends the cloud session token to another origin', async () => {
    vi.stubGlobal('fetch', mockFetch())
    await resolveImageSource(study)
    // A pass is per study: another study on the same server gets nothing.
    expect(imageAuthHeaders(`${EDGE}/dicom/studies/9.9/series/4/instances/5`)).toEqual({})
    expect(imageAuthHeaders('https://elsewhere.example/x')).toEqual({})
    expect(imageAuthHeaders(`${window.location.origin}/api/dicom/studies/1.2.3/series/4/instances/5`))
      .toEqual({ Authorization: 'Bearer cloud-session' })
  })

  it('falls back to the cloud copy when the hospital server is unreachable', async () => {
    vi.stubGlobal('fetch', mockFetch({ edgeUp: false, cloudStatus: 'AVAILABLE' }))
    const source = await resolveImageSource({ ...study, cloudStatus: 'AVAILABLE' })
    expect(source).toEqual({ kind: 'cloud' })
    expect(imageBase('1.2.3')).toBe(`${window.location.origin}/api/dicom`)
  })

  it('reports images it cannot reach from here', async () => {
    vi.stubGlobal('fetch', mockFetch({ edgeUp: false }))
    const source = await resolveImageSource(study)
    expect(source.kind).toBe('unavailable')
  })

  it('cloud studies never touch the hospital server', async () => {
    const f = mockFetch()
    vi.stubGlobal('fetch', f)
    expect(await resolveImageSource({ ...study, onHospitalServer: false, cloudStatus: 'AVAILABLE' }))
      .toEqual({ kind: 'cloud' })
    expect(f).not.toHaveBeenCalled()
  })

  it('names where the images are', () => {
    expect(imageLocationLabel({ cloudStatus: 'EDGE_ONLY', onHospitalServer: true })).toBe('Hospital server')
    expect(imageLocationLabel({ cloudStatus: 'EDGE_ONLY', onHospitalServer: false })).toBe('Not stored')
    expect(imageLocationLabel({ cloudStatus: 'REQUESTED', onHospitalServer: true })).toBe('Sending to cloud')
    expect(imageLocationLabel({ cloudStatus: 'AVAILABLE', onHospitalServer: true })).toBe('Hospital + cloud')
    expect(imageLocationLabel({ cloudStatus: 'AVAILABLE', onHospitalServer: false })).toBe('Cloud')
    expect(cloudHasImages('PENDING_DELETE')).toBe(true)
    expect(cloudHasImages('REQUESTED')).toBe(false)
  })
})

describe('report draft backup', () => {
  it('keeps the text in this browser until cleared', () => {
    saveDraftBackup('s1', { findings: 'F', impression: 'I', critical: true })
    const b = loadDraftBackup('s1')
    expect(b && sameDraft(b, { findings: 'F', impression: 'I', critical: true })).toBe(true)
    clearDraftBackup('s1')
    expect(loadDraftBackup('s1')).toBeNull()
  })

  it('ignores a corrupt backup', () => {
    localStorage.setItem('telerad_report_backup:s2', '{oops')
    expect(loadDraftBackup('s2')).toBeNull()
  })
})

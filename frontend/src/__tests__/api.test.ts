import { describe, it, expect, vi, beforeEach } from 'vitest'
import { loadAuth, saveAuth, clearAuth, api, apiBlob } from '../api'
import type { AuthUser } from '../types'

const sampleUser: AuthUser = {
  accessToken: 'tok-123',
  userId: 'u1',
  email: 'rad@telerad.local',
  fullName: 'Dr Rad',
  role: 'RADIOLOGIST',
  tenantId: null,
  tenantCode: null,
  tenantName: null,
}

describe('auth storage helpers', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('saveAuth and loadAuth round-trip', () => {
    saveAuth(sampleUser)
    expect(loadAuth()).toEqual(sampleUser)
  })

  it('loadAuth returns null when empty', () => {
    expect(loadAuth()).toBeNull()
  })

  it('loadAuth returns null for invalid JSON', () => {
    localStorage.setItem('telerad_auth', '{not-json')
    expect(loadAuth()).toBeNull()
  })

  it('clearAuth removes stored user', () => {
    saveAuth(sampleUser)
    clearAuth()
    expect(loadAuth()).toBeNull()
  })
})

describe('api', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('sets JSON content-type and authorization from storage', async () => {
    saveAuth(sampleUser)
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({ ok: true }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await api('/api/studies')

    expect(fetchMock).toHaveBeenCalledOnce()
    const [, options] = fetchMock.mock.calls[0]
    const headers = options.headers as Headers
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('Authorization')).toBe('Bearer tok-123')
  })

  it('does not set Content-Type for FormData bodies', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json' }),
      json: async () => ({}),
    })
    vi.stubGlobal('fetch', fetchMock)

    const form = new FormData()
    form.append('files', new Blob(['x']), 'a.dcm')
    await api('/api/upload', { method: 'POST', body: form }, 'explicit-token')

    const headers = fetchMock.mock.calls[0][1].headers as Headers
    expect(headers.has('Content-Type')).toBe(false)
    expect(headers.get('Authorization')).toBe('Bearer explicit-token')
  })

  it('throws error message from JSON body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        statusText: 'Unauthorized',
        json: async () => ({ error: 'Invalid credentials' }),
      }),
    )

    await expect(api('/api/auth/login', { method: 'POST' })).rejects.toThrow('Invalid credentials')
  })

  it('falls back to statusText when body is not JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 500,
        statusText: 'Server Error',
        json: async () => {
          throw new Error('not json')
        },
      }),
    )

    await expect(api('/api/x')).rejects.toThrow('Server Error')
  })

  it('returns undefined for 204', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 204,
        headers: new Headers(),
      }),
    )
    await expect(api('/api/noop')).resolves.toBeUndefined()
  })

  it('returns Response for non-JSON content', async () => {
    const response = {
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'image/jpeg' }),
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    await expect(api('/api/frame')).resolves.toBe(response)
  })
})

describe('apiBlob', () => {
  it('returns blob on success', async () => {
    saveAuth(sampleUser)
    const blob = new Blob(['pdf'])
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        blob: async () => blob,
      }),
    )
    await expect(apiBlob('/api/reports/1/pdf')).resolves.toBe(blob)
  })

  it('throws on failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false }))
    await expect(apiBlob('/api/reports/1/pdf')).rejects.toThrow('Download failed')
  })
})

import { FormEvent, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  clearShareToken,
  loadShareToken,
  saveShareToken,
  shareApi,
} from '../shareApi'

type Meta = {
  facilityName: string
  expired: boolean
  revoked: boolean
  locked: boolean
  expiresAt: string
}

type UnlockResult = {
  accessToken: string
  expiresAt: string
  facilityName: string
  includeReports: boolean
  allowDicomDownload: boolean
  mainStudyId: string
  priorCount: number
  studyCount: number
}

export default function PatientSharePublicPage() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const [meta, setMeta] = useState<Meta | null>(null)
  const [metaError, setMetaError] = useState('')
  const [code, setCode] = useState('')
  const [unlockError, setUnlockError] = useState('')
  const [unlocking, setUnlocking] = useState(false)

  useEffect(() => {
    let el = document.querySelector('meta[name="robots"]') as HTMLMetaElement | null
    if (!el) {
      el = document.createElement('meta')
      el.name = 'robots'
      document.head.appendChild(el)
    }
    el.content = 'noindex, nofollow'
  }, [])

  useEffect(() => {
    if (!token) return
    shareApi<Meta>(`/api/public/shares/${encodeURIComponent(token)}`)
      .then(setMeta)
      .catch((e) => setMetaError(e instanceof Error ? e.message : 'Share not found'))
  }, [token])

  useEffect(() => {
    if (!token || !meta) return
    const existing = loadShareToken()
    if (!existing) return
    shareApi<{ mainStudyId: string }>('/api/public/shares/me')
      .then((session) => {
        if (!session.mainStudyId) return
        sessionStorage.setItem('telerad_share_main_study', session.mainStudyId)
        navigate(`/s/${token}/studies/${session.mainStudyId}`, { replace: true })
      })
      .catch(() => clearShareToken())
  }, [token, meta, navigate])

  async function onUnlock(e: FormEvent) {
    e.preventDefault()
    if (!token) return
    setUnlocking(true)
    setUnlockError('')
    try {
      const result = await shareApi<UnlockResult>(`/api/public/shares/${encodeURIComponent(token)}/unlock`, {
        method: 'POST',
        body: JSON.stringify({ accessCode: code.trim() }),
      })
      saveShareToken(result.accessToken)
      sessionStorage.setItem('telerad_share_main_study', result.mainStudyId)
      navigate(`/s/${token}/studies/${result.mainStudyId}`, { replace: true })
    } catch (err) {
      setUnlockError(err instanceof Error ? err.message : 'Unlock failed')
    } finally {
      setUnlocking(false)
    }
  }

  const unlockable = meta && !meta.expired && !meta.revoked && !meta.locked

  if (metaError) {
    return (
      <div className="page">
        <div className="panel" style={{ maxWidth: 480, margin: '2rem auto' }}>
          <h1>Imaging share</h1>
          <p className="error">{metaError}</p>
        </div>
      </div>
    )
  }

  if (!meta) {
    return (
      <div className="page">
        <div className="panel" style={{ maxWidth: 480, margin: '2rem auto' }}>
          <p>Loading…</p>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      <div className="panel" style={{ maxWidth: 480, margin: '2rem auto' }}>
        <h1 style={{ marginTop: 0 }}>{meta.facilityName}</h1>
        <p className="muted">Enter the access code from your printed slip to view this study.</p>
        {meta.expired && <p className="error">This share has expired.</p>}
        {meta.revoked && <p className="error">This share was revoked.</p>}
        {meta.locked && <p className="error">Too many failed attempts. Try again later.</p>}
        {unlockable ? (
          <form onSubmit={onUnlock}>
            <label>
              Access code
              <input
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                autoComplete="one-time-code"
                required
                style={{ display: 'block', width: '100%', marginTop: 4, letterSpacing: '0.15em' }}
              />
            </label>
            {unlockError && <p className="error">{unlockError}</p>}
            <button type="submit" className="btn" disabled={unlocking} style={{ marginTop: '1rem' }}>
              {unlocking ? 'Unlocking…' : 'Unlock'}
            </button>
          </form>
        ) : (
          <p>This link cannot be unlocked.</p>
        )}
      </div>
    </div>
  )
}

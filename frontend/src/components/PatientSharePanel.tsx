import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import type { ShareCreateResult, ShareListItem } from '../shareApi'

type Props = {
  /** Prefer studyId — resolves patient + facility on the server. */
  studyId?: string
  patientId?: string
  patientLabel?: string | null
  compact?: boolean
}

export function PatientSharePanel({ studyId, patientId, patientLabel, compact }: Props) {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [consent, setConsent] = useState(false)
  const [includeReports, setIncludeReports] = useState(true)
  const [allowDicom, setAllowDicom] = useState(false)
  const [expiryDays, setExpiryDays] = useState(60)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [shares, setShares] = useState<ShareListItem[]>([])
  const [regenCode, setRegenCode] = useState<string | null>(null)

  const loadShares = useCallback(async () => {
    if (!patientId && !studyId) return
    try {
      // List by patient when known; otherwise list facility shares and filter client-side after create.
      const q = patientId ? `?patientId=${encodeURIComponent(patientId)}` : ''
      const list = await api<ShareListItem[]>(`/api/shares${q}`)
      setShares(list)
    } catch {
      /* ignore list errors until first create */
    }
  }, [patientId, studyId])

  useEffect(() => {
    if (open) loadShares().catch(() => undefined)
  }, [open, loadShares])

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    if (!consent) {
      setError('Confirm patient consent before creating a share.')
      return
    }
    if (!studyId) {
      setError('A study is required to create a share.')
      return
    }
    setBusy(true)
    setError(null)
    setRegenCode(null)
    try {
      const result = await api<ShareCreateResult>('/api/shares', {
        method: 'POST',
        body: JSON.stringify({
          studyId,
          consentConfirmed: true,
          expiryDays,
          includeReports,
          allowDicomDownload: allowDicom,
        }),
      })
      setOpen(false)
      setConsent(false)
      navigate('/shares/slip', {
        state: {
          ...result,
          createdAt: new Date().toISOString(),
          patientLabel: result.patientLabel || patientLabel || null,
        },
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Create failed')
    } finally {
      setBusy(false)
    }
  }

  async function revoke(id: string) {
    setBusy(true)
    setError(null)
    try {
      await api(`/api/shares/${id}/revoke`, { method: 'POST' })
      await loadShares()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Revoke failed')
    } finally {
      setBusy(false)
    }
  }

  async function regenerate(id: string) {
    setBusy(true)
    setError(null)
    setRegenCode(null)
    try {
      const out = await api<{ accessCode: string }>(`/api/shares/${id}/regenerate-code`, { method: 'POST' })
      setRegenCode(out.accessCode)
      await loadShares()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Regenerate failed')
    } finally {
      setBusy(false)
    }
  }

  const form = (
    <div
      className="panel"
      style={{
        padding: '1rem',
        marginTop: compact ? 0 : '1rem',
        maxWidth: 480,
        border: '1px solid var(--border, #ddd)',
        background: 'var(--panel-bg, #fff)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h3 style={{ margin: 0, fontSize: '1.05rem' }}>Share with patient</h3>
        <button type="button" className="btn secondary" onClick={() => setOpen(false)}>
          Cancel
        </button>
      </div>
      <p className="muted" style={{ fontSize: '0.9rem' }}>
        Shares this study and freezes available priors for comparison. Print the slip after create.
      </p>
      {error && <div className="error">{error}</div>}
      {regenCode && (
        <div className="success">
          New access code (shown once): <strong style={{ letterSpacing: '0.15em' }}>{regenCode}</strong>
        </div>
      )}

      <form onSubmit={onCreate}>
        <label style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
          <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} required />
          <span>I confirm the patient (or authorized representative) consented to share imaging via this link.</span>
        </label>
        <label style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginBottom: '0.5rem' }}>
          <input type="checkbox" checked={includeReports} onChange={(e) => setIncludeReports(e.target.checked)} />
          Include signed report PDFs
        </label>
        <label style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', marginBottom: '0.75rem' }}>
          <input type="checkbox" checked={allowDicom} onChange={(e) => setAllowDicom(e.target.checked)} />
          Allow DICOM zip download
        </label>
        <label className="field" style={{ display: 'block', marginBottom: '0.75rem' }}>
          Expiry (days)
          <input
            type="number"
            min={1}
            max={365}
            value={expiryDays}
            onChange={(e) => setExpiryDays(Number(e.target.value) || 60)}
            style={{ display: 'block', width: '6rem', marginTop: 4 }}
          />
        </label>
        <button type="submit" className="btn" disabled={busy || !consent}>
          {busy ? 'Creating…' : 'Create share & print slip'}
        </button>
      </form>

      {shares.length > 0 && (
        <div style={{ marginTop: '1.25rem' }}>
          <h4 style={{ margin: '0 0 0.5rem', fontSize: '0.95rem' }}>Existing shares</h4>
          <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {shares.map((s) => (
              <li
                key={s.id}
                style={{
                  display: 'flex',
                  flexWrap: 'wrap',
                  gap: '0.5rem',
                  alignItems: 'center',
                  marginBottom: '0.5rem',
                  fontSize: '0.9rem',
                }}
              >
                <span>
                  Study share
                  {s.priorCount != null ? ` · ${s.priorCount} prior${s.priorCount === 1 ? '' : 's'}` : ''}
                  {' · '}exp {new Date(s.expiresAt).toLocaleDateString()}
                  {!s.active && <span className="muted"> · inactive</span>}
                </span>
                {s.active && (
                  <>
                    <button type="button" className="btn secondary" disabled={busy} onClick={() => regenerate(s.id)}>
                      New code
                    </button>
                    <button type="button" className="btn secondary" disabled={busy} onClick={() => revoke(s.id)}>
                      Revoke
                    </button>
                  </>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )

  if (!open) {
    return (
      <button
        type="button"
        className={compact ? 'btn secondary' : 'btn'}
        onClick={() => setOpen(true)}
        title="Share imaging with patient (CD replacement)"
      >
        Share with patient
      </button>
    )
  }

  if (compact) {
    return (
      <>
        <button type="button" className="btn secondary" onClick={() => setOpen(false)}>
          Share…
        </button>
        <div
          role="dialog"
          aria-modal="true"
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 80,
            background: 'rgba(0,0,0,0.45)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '1rem',
          }}
          onClick={() => setOpen(false)}
        >
          <div onClick={(e) => e.stopPropagation()}>{form}</div>
        </div>
      </>
    )
  }

  return form
}

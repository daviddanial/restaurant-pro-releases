import { FormEvent, useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import type { ShareCreateResult, ShareListItem } from '../shareApi'

type Props = {
  /** Required — share is study-scoped. */
  studyId: string
  patientId?: string
  patientLabel?: string | null
  /** Smaller secondary trigger (viewer / table). */
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

  const close = useCallback(() => {
    setOpen(false)
    setError(null)
    setRegenCode(null)
    setConsent(false)
  }, [])

  const loadShares = useCallback(async () => {
    try {
      const q = patientId ? `?patientId=${encodeURIComponent(patientId)}` : ''
      const list = await api<ShareListItem[]>(`/api/shares${q}`)
      setShares(list.filter((s) => !s.mainStudyId || s.mainStudyId === studyId))
    } catch {
      /* ignore list errors until first create */
    }
  }, [patientId, studyId])

  useEffect(() => {
    if (!open) return
    loadShares().catch(() => undefined)
  }, [open, loadShares])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, close])

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    if (!consent) {
      setError('Confirm patient consent before creating a share.')
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
      close()
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

  return (
    <>
      <button
        type="button"
        className={compact ? 'btn secondary' : 'btn'}
        style={compact ? { fontSize: '0.85rem', padding: '0.25rem 0.6rem' } : undefined}
        onClick={() => setOpen(true)}
        title="Share imaging with patient (CD replacement)"
      >
        {compact ? 'Share' : 'Share with patient'}
      </button>

      {open && (
        <div
          className="share-dialog-backdrop"
          role="presentation"
          onClick={close}
        >
          <div
            className="share-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="share-dialog-title"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="share-dialog-head">
              <h3 id="share-dialog-title">Share with patient</h3>
              <button type="button" className="btn secondary" onClick={close} aria-label="Close">
                Cancel
              </button>
            </div>
            {patientLabel && (
              <p className="muted" style={{ marginTop: 0, fontSize: '0.9rem' }}>
                Patient: {patientLabel}
              </p>
            )}
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
              <label className="share-dialog-check">
                <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} required />
                <span>
                  I confirm the patient (or authorized representative) consented to share imaging via this link.
                </span>
              </label>
              <label className="share-dialog-check">
                <input
                  type="checkbox"
                  checked={includeReports}
                  onChange={(e) => setIncludeReports(e.target.checked)}
                />
                <span>Include signed report PDFs</span>
              </label>
              <label className="share-dialog-check">
                <input type="checkbox" checked={allowDicom} onChange={(e) => setAllowDicom(e.target.checked)} />
                <span>Allow DICOM zip download</span>
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
              <div className="share-dialog-existing">
                <h4>Existing shares</h4>
                <ul>
                  {shares.map((s) => (
                    <li key={s.id}>
                      <span>
                        Study share
                        {s.priorCount != null ? ` · ${s.priorCount} prior${s.priorCount === 1 ? '' : 's'}` : ''}
                        {' · '}exp {new Date(s.expiresAt).toLocaleDateString()}
                        {!s.active && <span className="muted"> · inactive</span>}
                      </span>
                      {s.active && (
                        <span className="share-dialog-existing-actions">
                          <button
                            type="button"
                            className="btn secondary"
                            disabled={busy}
                            onClick={() => regenerate(s.id)}
                          >
                            New code
                          </button>
                          <button
                            type="button"
                            className="btn secondary"
                            disabled={busy}
                            onClick={() => revoke(s.id)}
                          >
                            Revoke
                          </button>
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  )
}

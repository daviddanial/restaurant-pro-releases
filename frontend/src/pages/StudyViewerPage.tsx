import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { DicomViewport } from '../components/DicomViewport'
import type { StudyDetail } from '../types'

export function StudyViewerPage() {
  const { id } = useParams()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [seriesIdx, setSeriesIdx] = useState(0)
  const [instanceIdx, setInstanceIdx] = useState(0)
  const [findings, setFindings] = useState('')
  const [impression, setImpression] = useState('')
  const [critical, setCritical] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null)

  async function load() {
    const data = await api<StudyDetail>(`/api/studies/${id}`)
    setDetail(data)
    setFindings(data.report?.findings || '')
    setImpression(data.report?.impression || '')
    setCritical(!!data.report?.critical)
  }

  useEffect(() => {
    load().catch((err) => setError(err.message))
  }, [id])

  useEffect(() => {
    if (!detail || detail.report?.status === 'SIGNED') return
    const t = window.setInterval(() => {
      api(`/api/studies/${id}/report`, {
        method: 'PUT',
        body: JSON.stringify({ findings, impression, critical }),
      })
        .then(() => setLastSavedAt(new Date()))
        .catch(() => undefined)
    }, 8000)
    return () => window.clearInterval(t)
  }, [id, detail, findings, impression, critical])

  const series = detail?.series?.[seriesIdx]
  const instance = series?.instances?.[instanceIdx]
  const viewportInstances = useMemo(
    () => (series?.instances || []).map((i) => ({
      sopUid: i.sopInstanceUid,
      numberOfFrames: i.numberOfFrames,
    })),
    [series],
  )

  async function saveDraft() {
    setSaving(true)
    setError(null)
    try {
      await api(`/api/studies/${id}/report`, {
        method: 'PUT',
        body: JSON.stringify({ findings, impression, critical }),
      })
      setLastSavedAt(new Date())
      setMessage('Draft saved')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  async function sign() {
    if (!confirm('Sign and finalize this report? This cannot be undone without an addendum.')) return
    setSaving(true)
    setError(null)
    try {
      await api(`/api/studies/${id}/report`, {
        method: 'PUT',
        body: JSON.stringify({ findings, impression, critical }),
      })
      await api(`/api/studies/${id}/report/sign`, { method: 'POST' })
      setMessage('Report signed and delivered to hospital portal')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign failed')
    } finally {
      setSaving(false)
    }
  }

  if (!detail) {
    return (
      <div className="page">
        {error ? <div className="error">{error}</div> : <p className="muted">Loading study…</p>}
      </div>
    )
  }

  const signed = detail.report?.status === 'SIGNED'
  const study = detail.study

  return (
    <div className="viewer-layout">
      <aside className="viewer-context" aria-label="Patient and order context">
        <div className="viewer-context-inner">
          <div>
            <p className="context-section-label">Patient</p>
            <h2 className="context-patient">{study.patientName || 'Unknown patient'}</h2>
            <p className="muted" style={{ margin: '0.2rem 0 0', fontSize: '0.8125rem' }}>
              MRN {study.patientMrn || '—'}
            </p>
          </div>

          <dl className="context-meta">
            <div>
              <dt>Hospital</dt>
              <dd>{study.hospitalName || '—'}</dd>
            </div>
            <div>
              <dt>Modality</dt>
              <dd>{study.modality || '—'}</dd>
            </div>
            <div>
              <dt>Accession</dt>
              <dd style={{ fontFamily: 'ui-monospace, monospace' }}>{study.accessionNumber || '—'}</dd>
            </div>
            <div>
              <dt>Priority</dt>
              <dd>
                <span className={`badge ${study.priority === 'STAT' ? 'stat' : ''}`}>{study.priority}</span>
              </dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd><span className="badge">{study.status}</span></dd>
            </div>
            {study.slaDueAt && (
              <div>
                <dt>SLA</dt>
                <dd>
                  <span className={`badge ${study.slaOverdue ? 'breached' : ''}`}>
                    {study.slaOverdue ? 'SLA Breached' : `Due ${new Date(study.slaDueAt).toLocaleString()}`}
                  </span>
                </dd>
              </div>
            )}
          </dl>

          <div>
            <p className="context-section-label">Series</p>
            <div className="stack">
              {detail.series.map((s, idx) => (
                <button
                  key={s.id}
                  type="button"
                  className={`series-item ${idx === seriesIdx ? 'active' : ''}`}
                  onClick={() => { setSeriesIdx(idx); setInstanceIdx(0) }}
                >
                  <div style={{ fontWeight: 500 }}>{s.seriesDescription || `Series ${s.seriesNumber ?? idx + 1}`}</div>
                  <div className="muted" style={{ fontSize: '0.75rem', marginTop: 2 }}>
                    {s.modality} · {s.instanceCount} img
                    {s.instances.some((i) => i.numberOfFrames > 1)
                      ? ` · ${s.instances.reduce((n, i) => n + (i.numberOfFrames || 1), 0)} frames`
                      : ''}
                  </div>
                </button>
              ))}
              {detail.series.length === 0 && <div className="muted">No series metadata yet.</div>}
            </div>
          </div>

          <Link className="btn secondary" to="/worklist">Back to worklist</Link>
        </div>
      </aside>

      {series && instance ? (
        <DicomViewport
          studyUid={study.studyInstanceUid}
          seriesUid={series.seriesInstanceUid}
          instances={viewportInstances}
          instanceIndex={instanceIdx}
          cineRate={instance.cineRate}
          frameTime={instance.frameTime}
          modality={series.modality || study.modality}
          onInstanceChange={setInstanceIdx}
        />
      ) : (
        <section className="viewport">
          <div className="muted" style={{ color: '#94a3b8' }}>No frame available</div>
        </section>
      )}

      <aside className="viewer-report" aria-label="Report editor">
        <div className="report-toolbar">
          <strong style={{ fontSize: '0.875rem' }}>Report editor</strong>
          <span className="muted" style={{ fontSize: '0.75rem', marginLeft: 'auto' }}>
            {signed
              ? 'Signed'
              : lastSavedAt
                ? `Saved ${lastSavedAt.toLocaleTimeString()}`
                : 'Autosave on'}
          </span>
        </div>
        <div className="viewer-report-inner">
          {message && <div className="success">{message}</div>}
          {error && <div className="error">{error}</div>}
          <div className="field">
            <label>Findings</label>
            <textarea value={findings} disabled={signed} onChange={(e) => setFindings(e.target.value)} />
          </div>
          <div className="field">
            <label>Impression</label>
            <textarea value={impression} disabled={signed} onChange={(e) => setImpression(e.target.value)} />
          </div>
          <label className="critical-toggle">
            <input type="checkbox" checked={critical} disabled={signed} onChange={(e) => setCritical(e.target.checked)} />
            Critical Finding
          </label>
          {!signed && (
            <div className="report-actions">
              <button className="btn secondary" type="button" disabled={saving} onClick={saveDraft}>
                Save draft
              </button>
              <button className="btn" type="button" disabled={saving} onClick={sign}>
                Sign &amp; Finalize
              </button>
            </div>
          )}
          {signed && (
            <div className="success">
              Signed {detail.report?.signedAt ? new Date(detail.report.signedAt).toLocaleString() : ''}
            </div>
          )}
          <p className="muted" style={{ fontSize: '0.7rem', lineHeight: 1.45, margin: 0 }}>
            Viewer: left-drag = tool · middle = pan · right = zoom · wheel = scroll ·
            L = length · I = invert · R = reset · Space = cine.
          </p>
        </div>
      </aside>
    </div>
  )
}

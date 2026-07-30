import { useEffect, useState } from 'react'
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

  // Autosave draft every 8s while editing
  useEffect(() => {
    if (!detail || detail.report?.status === 'SIGNED') return
    const t = window.setInterval(() => {
      api(`/api/studies/${id}/report`, {
        method: 'PUT',
        body: JSON.stringify({ findings, impression, critical }),
      }).catch(() => undefined)
    }, 8000)
    return () => window.clearInterval(t)
  }, [id, detail, findings, impression, critical])

  const series = detail?.series?.[seriesIdx]
  const instance = series?.instances?.[instanceIdx]

  async function saveDraft() {
    setSaving(true)
    setError(null)
    try {
      await api(`/api/studies/${id}/report`, {
        method: 'PUT',
        body: JSON.stringify({ findings, impression, critical }),
      })
      setMessage('Draft saved')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    } finally {
      setSaving(false)
    }
  }

  async function sign() {
    if (!confirm('Sign and lock this report? This cannot be undone without an addendum.')) return
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
    return <div className="page">{error ? <div className="error">{error}</div> : <p className="muted">Loading study…</p>}</div>
  }

  const signed = detail.report?.status === 'SIGNED'
  const study = detail.study

  return (
    <div className="viewer-layout">
      <aside className="panel series-list">
        <h3>Series</h3>
        <p className="muted" style={{ marginTop: 0 }}>
          {study.patientName || 'Patient'} · {study.modality} · {study.status}
        </p>
        {study.slaDueAt && (
          <div className={`badge ${study.slaOverdue ? 'stat' : ''}`} style={{ marginBottom: '0.5rem' }}>
            SLA {study.slaOverdue ? 'OVERDUE' : 'due'} {new Date(study.slaDueAt).toLocaleString()}
          </div>
        )}
        {detail.series.map((s, idx) => (
          <button
            key={s.id}
            type="button"
            className={`series-item ${idx === seriesIdx ? 'active' : ''}`}
            onClick={() => { setSeriesIdx(idx); setInstanceIdx(0) }}
          >
            <div>{s.seriesDescription || `Series ${s.seriesNumber ?? idx + 1}`}</div>
            <div className="muted" style={{ fontSize: '0.8rem' }}>
              {s.modality} · {s.instanceCount} img
              {s.instances.some((i) => i.numberOfFrames > 1)
                ? ` · ${s.instances.reduce((n, i) => n + (i.numberOfFrames || 1), 0)} frames`
                : ''}
            </div>
          </button>
        ))}
        {detail.series.length === 0 && <div className="muted">No series metadata yet.</div>}
        <Link className="btn secondary" to="/worklist" style={{ marginTop: '0.5rem' }}>Back to worklist</Link>
      </aside>

      {series && instance ? (
        <DicomViewport
          studyUid={study.studyInstanceUid}
          seriesUid={series.seriesInstanceUid}
          sopUid={instance.sopInstanceUid}
          instanceIndex={instanceIdx}
          instanceCount={series.instances.length}
          numberOfFrames={instance.numberOfFrames}
          cineRate={instance.cineRate}
          frameTime={instance.frameTime}
          modality={series.modality || study.modality}
          onInstanceChange={setInstanceIdx}
        />
      ) : (
        <section className="viewport">
          <div className="muted">No frame available</div>
        </section>
      )}

      <aside className="panel stack" style={{ overflow: 'auto' }}>
        <h3>Interpretation</h3>
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
        <label className="row">
          <input type="checkbox" checked={critical} disabled={signed} onChange={(e) => setCritical(e.target.checked)} />
          Critical result
        </label>
        {!signed && (
          <div className="row">
            <button className="btn secondary" type="button" disabled={saving} onClick={saveDraft}>Save draft</button>
            <button className="btn" type="button" disabled={saving} onClick={sign}>Sign & deliver</button>
          </div>
        )}
        {signed && (
          <div className="success">Signed {detail.report?.signedAt ? new Date(detail.report.signedAt).toLocaleString() : ''}</div>
        )}
        <p className="muted" style={{ fontSize: '0.8rem' }}>
          Tools: drag = W/L · Shift+drag = pan · Alt+drag = zoom · wheel = scroll frames ·
          Ctrl+wheel = zoom · I = invert · Space = play/pause cine
        </p>
      </aside>
    </div>
  )
}

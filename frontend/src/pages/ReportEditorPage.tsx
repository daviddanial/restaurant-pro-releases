import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { InsertTemplateModal } from '../components/InsertTemplateModal'
import { type KeyImage, fetchMeasurements, keyImageBlob, listKeyImages, measurementsReportText } from '../studyAnnotations'
import type { StudyDetail } from '../types'

export function ReportEditorPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [findings, setFindings] = useState('')
  const [impression, setImpression] = useState('')
  const [critical, setCritical] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null)
  const [templateOpen, setTemplateOpen] = useState(false)
  const [keyImages, setKeyImages] = useState<KeyImage[]>([])
  const [keyThumbs, setKeyThumbs] = useState<Record<string, string>>({})

  // Key images saved in the viewer: shown here and added to the PDF when the report is signed.
  useEffect(() => {
    if (!id) return
    let cancelled = false
    const urls: string[] = []
    listKeyImages(id)
      .then(async (rows) => {
        if (cancelled) return
        setKeyImages(rows)
        for (const k of rows.filter((r) => r.hasSnapshot)) {
          const blob = await keyImageBlob(id, k.id).catch(() => null)
          if (!blob || cancelled) continue
          const url = URL.createObjectURL(blob)
          urls.push(url)
          setKeyThumbs((prev) => ({ ...prev, [k.id]: url }))
        }
      })
      .catch(() => undefined)
    return () => { cancelled = true; urls.forEach((u) => URL.revokeObjectURL(u)) }
  }, [id])

  async function insertMeasurements() {
    if (!id) return
    setError(null)
    try {
      const text = measurementsReportText(await fetchMeasurements(id))
      if (!text) {
        setMessage('No saved measurements on this study yet. Draw them in the viewer first.')
        return
      }
      setFindings((prev) => (prev.trim() ? `${prev.trimEnd()}\n\n${text}` : text))
      setMessage('Measurements inserted into Findings.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not load measurements')
    }
  }

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
        {error ? <div className="error">{error}</div> : <p className="muted">Loading report…</p>}
      </div>
    )
  }

  const signed = detail.report?.status === 'SIGNED'
  const study = detail.study

  return (
    <div className="page report-page">
      <div className="page-header">
        <div>
          <h1>{study.patientName || 'Unknown patient'}</h1>
          <p className="subtitle">
            MRN {study.patientMrn || '—'} · {study.modality || '—'} ·{' '}
            {study.accessionNumber || 'No accession'} · {study.hospitalName || '—'}
          </p>
        </div>
        <div className="report-page-meta">
          <span className={`badge ${study.priority === 'STAT' ? 'stat' : ''}`}>{study.priority}</span>
          <Link className="btn secondary" to="/worklist">Back to worklist</Link>
        </div>
      </div>

      <div className="panel">
        <div className="report-toolbar">
          <strong style={{ fontSize: '0.875rem' }}>Report editor</strong>
          {!signed && (
            <button className="btn-insert-tpl" type="button" onClick={() => setTemplateOpen(true)}>
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path fill="currentColor" d="M6 2h6l4 4v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2Zm6 1.5V7h3.5L12 3.5ZM7 10h6v1.25H7V10Zm0 2.5h6V14H7v-1.5Zm0 2.5h4V16H7v-1Z" />
              </svg>
              Insert Template
            </button>
          )}
          {!signed && (
            <button className="btn-insert-tpl" type="button" onClick={() => { void insertMeasurements() }}>
              Insert Measurements
            </button>
          )}
          <span className="muted" style={{ fontSize: '0.75rem', marginLeft: 'auto' }}>
            {signed
              ? 'Signed'
              : lastSavedAt
                ? `Saved ${lastSavedAt.toLocaleTimeString()}`
                : 'Autosave on'}
          </span>
        </div>
        <div className="report-page-inner">
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
          {keyImages.length > 0 && (
            <div className="field">
              <label>Key images · {keyImages.length}</label>
              <p className="muted" style={{ fontSize: '0.75rem', margin: '0 0 0.5rem' }}>
                {signed ? 'Included in the signed PDF.' : 'Added to the PDF when you sign. Mark or unmark them in the viewer (K).'}
              </p>
              <div className="report-key-images">
                {keyImages.map((k) => (
                  <figure key={k.id}>
                    {keyThumbs[k.id] ? <img src={keyThumbs[k.id]} alt={k.location ?? 'Key image'} /> : <div className="key-image-placeholder">★</div>}
                    <figcaption>{k.location ?? 'Key image'}</figcaption>
                  </figure>
                ))}
              </div>
            </div>
          )}
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
        </div>
      </div>

      <InsertTemplateModal
        open={templateOpen}
        onClose={() => setTemplateOpen(false)}
        order={{
          modality: study.modality,
          patientName: study.patientName,
          mrn: study.patientMrn,
          accession: study.accessionNumber,
          hospital: study.hospitalName,
          studyDate: study.studyDate,
          studyDescription: study.studyDescription,
          radiologistName: user?.fullName,
        }}
        onInsert={({ findings: nextFindings, impression: nextImpression }) => {
          setFindings(nextFindings)
          setImpression(nextImpression)
          setMessage('Template inserted. Complete the remaining fields, then save or sign.')
        }}
      />
    </div>
  )
}

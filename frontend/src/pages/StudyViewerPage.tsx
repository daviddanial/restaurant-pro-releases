import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, apiBlob } from '../api'
import { DicomViewport } from '../components/DicomViewport'
import type { StudyDetail } from '../types'

export function StudyViewerPage() {
  const { id } = useParams()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [seriesIdx, setSeriesIdx] = useState(0)
  const [instanceIdx, setInstanceIdx] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [thumbs, setThumbs] = useState<Record<string, string>>({})

  useEffect(() => {
    let cancelled = false
    api<StudyDetail>(`/api/studies/${id}`)
      .then((data) => {
        if (!cancelled) setDetail(data)
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load study')
      })
    return () => {
      cancelled = true
    }
  }, [id])

  useEffect(() => {
    if (!detail) return
    let cancelled = false
    const objectUrls: string[] = []

    async function loadThumbs(data: StudyDetail) {
      for (const series of data.series) {
        const inst = series.instances[0]
        if (!inst || cancelled) continue
        try {
          const blob = await apiBlob(
            `/api/dicom/studies/${data.study.studyInstanceUid}/series/${series.seriesInstanceUid}/instances/${inst.sopInstanceUid}/frames/1`,
          )
          if (cancelled) return
          const src = URL.createObjectURL(blob)
          objectUrls.push(src)
          setThumbs((prev) => ({ ...prev, [series.id]: src }))
        } catch {
          /* series preview is optional */
        }
      }
    }

    loadThumbs(detail)
    return () => {
      cancelled = true
      objectUrls.forEach((u) => URL.revokeObjectURL(u))
    }
  }, [detail])

  const series = detail?.series?.[seriesIdx]
  const instance = series?.instances?.[instanceIdx]
  const viewportInstances = useMemo(
    () => (series?.instances || []).map((i) => ({
      sopUid: i.sopInstanceUid,
      numberOfFrames: i.numberOfFrames,
    })),
    [series],
  )

  if (!detail) {
    return (
      <div className="page">
        {error ? <div className="error">{error}</div> : <p className="muted">Loading study…</p>}
      </div>
    )
  }

  const study = detail.study

  return (
    <div className="viewer-shell">
      <div className="viewer-topstrip">
        <div className="viewer-topstrip-id">
          <strong>{study.patientName || 'Unknown patient'}</strong>
          <span className="muted">MRN {study.patientMrn || '—'}</span>
          <span className="mono muted">{study.accessionNumber || '—'}</span>
          <span className="muted">{study.modality || '—'}</span>
        </div>
        <div className="viewer-topstrip-meta">
          <span className={`badge ${study.priority === 'STAT' ? 'stat' : ''}`}>{study.priority}</span>
          <span className="badge">{study.status}</span>
          {study.slaDueAt && (
            <span className={`badge ${study.slaOverdue ? 'breached' : ''}`}>
              {study.slaOverdue ? 'SLA Breached' : `Due ${new Date(study.slaDueAt).toLocaleString()}`}
            </span>
          )}
          {detail.report?.status === 'SIGNED' && <span className="badge">Report signed</span>}
        </div>
        <div className="viewer-topstrip-actions">
          <Link className="btn secondary" to="/worklist">Back to worklist</Link>
          <a
            className="btn"
            href={`/studies/${id}/report`}
            target="_blank"
            rel="noopener noreferrer"
          >
            Report
          </a>
        </div>
      </div>

      <div className="viewer-layout">
        <aside className="viewer-series" aria-label="Series">
          <p className="viewer-series-label">Series</p>
          {detail.series.map((s, idx) => {
            const frames = s.instances.reduce((n, i) => n + (i.numberOfFrames || 1), 0)
            const thumb = thumbs[s.id]
            return (
              <button
                key={s.id}
                type="button"
                className={`series-item ${idx === seriesIdx ? 'active' : ''}`}
                onClick={() => { setSeriesIdx(idx); setInstanceIdx(0) }}
              >
                <span className="series-thumb">
                  {thumb ? <img src={thumb} alt="" /> : <span className="series-thumb-empty" />}
                </span>
                <span className="series-text">
                  <span className="series-title">
                    {s.seriesDescription || `Series ${s.seriesNumber ?? idx + 1}`}
                  </span>
                  <span className="series-sub">
                    {s.modality} · {s.instanceCount} img
                    {frames > s.instanceCount ? ` · ${frames} frames` : ''}
                  </span>
                </span>
              </button>
            )
          })}
          {detail.series.length === 0 && <div className="muted">No series metadata yet.</div>}
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
      </div>
    </div>
  )
}

import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { DicomViewport } from '../components/DicomViewport'
import { SeriesThumbnail } from '../components/SeriesThumbnail'
import type { StudyDetail } from '../types'

export function StudyViewerPage() {
  const { id } = useParams()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [seriesIdx, setSeriesIdx] = useState(0)
  const [instanceIdx, setInstanceIdx] = useState(0)
  const [error, setError] = useState<string | null>(null)

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
            const firstInstance = s.instances[0]
            return (
              <button
                key={s.id}
                type="button"
                className={`series-item ${idx === seriesIdx ? 'active' : ''}`}
                onClick={() => { setSeriesIdx(idx); setInstanceIdx(0) }}
              >
                {firstInstance ? (
                  <SeriesThumbnail
                    studyUid={study.studyInstanceUid}
                    seriesUid={s.seriesInstanceUid}
                    sopUid={firstInstance.sopInstanceUid}
                  />
                ) : (
                  <span className="series-thumb"><span className="series-thumb-empty" /></span>
                )}
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

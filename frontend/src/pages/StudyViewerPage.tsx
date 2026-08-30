import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { DicomViewport, type DicomViewportHandle } from '../components/DicomViewport'
import { SeriesThumbnail } from '../components/SeriesThumbnail'
import { defaultPrior, formatInterval, formatStudyDate, matchPriorSeries } from '../priors'
import type { SeriesDto, StudyDetail, StudyListItem } from '../types'

type SeriesRailProps = {
  studyUid: string
  series: SeriesDto[]
  activeIdx: number
  onSelect: (idx: number) => void
}

function SeriesRail({ studyUid, series, activeIdx, onSelect }: SeriesRailProps) {
  if (series.length === 0) {
    return <div className="muted">No series metadata yet.</div>
  }
  return (
    <>
      {series.map((s, idx) => {
        const frames = s.instances.reduce((n, i) => n + (i.numberOfFrames || 1), 0)
        const firstInstance = s.instances[0]
        return (
          <button
            key={s.id}
            type="button"
            className={`series-item ${idx === activeIdx ? 'active' : ''}`}
            onClick={() => onSelect(idx)}
          >
            {firstInstance ? (
              <SeriesThumbnail
                studyUid={studyUid}
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
    </>
  )
}

export function StudyViewerPage() {
  const { id } = useParams()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [seriesIdx, setSeriesIdx] = useState(0)
  const [instanceIdx, setInstanceIdx] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const [priors, setPriors] = useState<StudyListItem[]>([])
  const [priorId, setPriorId] = useState<string | null>(null)
  const [priorDetail, setPriorDetail] = useState<StudyDetail | null>(null)
  const [priorSeriesIdx, setPriorSeriesIdx] = useState(0)
  const [priorInstanceIdx, setPriorInstanceIdx] = useState(0)
  const [syncScroll, setSyncScroll] = useState(true)

  const currentViewport = useRef<DicomViewportHandle>(null)
  const priorViewport = useRef<DicomViewportHandle>(null)

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

  // Hanging protocol: offer the patient's earlier studies and open the closest one.
  useEffect(() => {
    let cancelled = false
    setPriors([])
    setPriorId(null)
    api<StudyListItem[]>(`/api/studies/${id}/priors`)
      .then((data) => {
        if (cancelled) return
        setPriors(data)
        const auto = defaultPrior(data, detail?.study.modality ?? null)
        if (auto) setPriorId(auto.id)
      })
      .catch(() => {
        /* comparison is optional; never block the read */
      })
    return () => {
      cancelled = true
    }
  }, [id, detail?.study.modality])

  useEffect(() => {
    if (!priorId) {
      setPriorDetail(null)
      return
    }
    let cancelled = false
    setPriorSeriesIdx(0)
    setPriorInstanceIdx(0)
    api<StudyDetail>(`/api/studies/${priorId}`)
      .then((data) => {
        if (!cancelled) setPriorDetail(data)
      })
      .catch(() => {
        if (!cancelled) setPriorDetail(null)
      })
    return () => {
      cancelled = true
    }
  }, [priorId])

  const series = detail?.series?.[seriesIdx]
  const instance = series?.instances?.[instanceIdx]
  const viewportInstances = useMemo(
    () => (series?.instances || []).map((i) => ({
      sopUid: i.sopInstanceUid,
      numberOfFrames: i.numberOfFrames,
    })),
    [series],
  )

  // Pair the panes by sequence, not just by scroll position: choosing a series on the
  // current study pulls the equivalent one on the prior. Picking a prior series by hand
  // sticks, because this only reruns when the current selection or the prior study changes.
  useEffect(() => {
    if (!priorDetail || !series) return
    const matched = matchPriorSeries(series, priorDetail.series)
    if (matched < 0) return
    setPriorSeriesIdx(matched)
    setPriorInstanceIdx(0)
  }, [series, priorDetail])

  const priorSeries = priorDetail?.series?.[priorSeriesIdx]
  const priorInstance = priorSeries?.instances?.[priorInstanceIdx]
  const priorViewportInstances = useMemo(
    () => (priorSeries?.instances || []).map((i) => ({
      sopUid: i.sopInstanceUid,
      numberOfFrames: i.numberOfFrames,
    })),
    [priorSeries],
  )

  // goToFraction suppresses the target's own progress event, so these cannot ping-pong.
  const onCurrentProgress = useCallback((fraction: number) => {
    if (syncScroll) priorViewport.current?.goToFraction(fraction)
  }, [syncScroll])

  const onPriorProgress = useCallback((fraction: number) => {
    if (syncScroll) currentViewport.current?.goToFraction(fraction)
  }, [syncScroll])

  if (!detail) {
    return (
      <div className="page">
        {error ? <div className="error">{error}</div> : <p className="muted">Loading study…</p>}
      </div>
    )
  }

  const study = detail.study
  const priorStudy = priorDetail?.study
  const comparing = Boolean(priorStudy)
  const interval = priorStudy ? formatInterval(priorStudy.studyDate, study.studyDate) : null

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
        {priors.length > 0 && (
          <div className="viewer-compare">
            <label className="viewer-compare-pick">
              <span>Compare</span>
              <select
                value={priorId ?? ''}
                onChange={(e) => setPriorId(e.target.value || null)}
              >
                <option value="">Off</option>
                {priors.map((p) => (
                  <option key={p.id} value={p.id}>
                    {formatStudyDate(p.studyDate)} · {p.modality || '—'}
                    {p.studyDescription ? ` · ${p.studyDescription}` : ''}
                  </option>
                ))}
              </select>
            </label>
            {comparing && (
              <label className="viewer-compare-sync">
                <input
                  type="checkbox"
                  checked={syncScroll}
                  onChange={(e) => setSyncScroll(e.target.checked)}
                />
                Sync scroll
              </label>
            )}
          </div>
        )}
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
          <p className="viewer-series-label">
            {comparing ? `Current · ${formatStudyDate(study.studyDate)}` : 'Series'}
          </p>
          <SeriesRail
            studyUid={study.studyInstanceUid}
            series={detail.series}
            activeIdx={seriesIdx}
            onSelect={(idx) => { setSeriesIdx(idx); setInstanceIdx(0) }}
          />
          {comparing && priorDetail && priorStudy && (
            <>
              <p className="viewer-series-label prior">
                Prior · {formatStudyDate(priorStudy.studyDate)}
              </p>
              <SeriesRail
                studyUid={priorStudy.studyInstanceUid}
                series={priorDetail.series}
                activeIdx={priorSeriesIdx}
                onSelect={(idx) => { setPriorSeriesIdx(idx); setPriorInstanceIdx(0) }}
              />
            </>
          )}
        </aside>

        <div className={`viewer-panes ${comparing ? 'compare' : ''}`}>
          <section className="viewer-pane">
            {comparing && (
              <header className="viewer-pane-label">
                <span className="viewer-pane-tag current">Current</span>
                {formatStudyDate(study.studyDate)}
                {series?.seriesDescription ? ` · ${series.seriesDescription}` : ''}
              </header>
            )}
            {series && instance ? (
              <DicomViewport
                ref={currentViewport}
                studyUid={study.studyInstanceUid}
                seriesUid={series.seriesInstanceUid}
                instances={viewportInstances}
                instanceIndex={instanceIdx}
                cineRate={instance.cineRate}
                frameTime={instance.frameTime}
                modality={series.modality || study.modality}
                onInstanceChange={setInstanceIdx}
                onProgress={onCurrentProgress}
              />
            ) : (
              <div className="viewport">
                <div className="muted" style={{ color: '#94a3b8' }}>No frame available</div>
              </div>
            )}
          </section>

          {comparing && priorStudy && (
            <section className="viewer-pane">
              <header className="viewer-pane-label">
                <span className="viewer-pane-tag prior">Prior</span>
                {formatStudyDate(priorStudy.studyDate)}
                {interval ? ` · ${interval}` : ''}
                {priorSeries?.seriesDescription ? ` · ${priorSeries.seriesDescription}` : ''}
              </header>
              {priorSeries && priorInstance ? (
                <DicomViewport
                  ref={priorViewport}
                  studyUid={priorStudy.studyInstanceUid}
                  seriesUid={priorSeries.seriesInstanceUid}
                  instances={priorViewportInstances}
                  instanceIndex={priorInstanceIdx}
                  cineRate={priorInstance.cineRate}
                  frameTime={priorInstance.frameTime}
                  modality={priorSeries.modality || priorStudy.modality}
                  onInstanceChange={setPriorInstanceIdx}
                  onProgress={onPriorProgress}
                />
              ) : (
                <div className="viewport">
                  <div className="muted" style={{ color: '#94a3b8' }}>Loading prior…</div>
                </div>
              )}
            </section>
          )}
        </div>
      </div>
    </div>
  )
}

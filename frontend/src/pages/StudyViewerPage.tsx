import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type Ref } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { DicomViewport, type DicomViewportHandle } from '../components/DicomViewport'
import { MprViewer } from '../components/MprViewer'
import { SeriesThumbnail } from '../components/SeriesThumbnail'
import { pickDefaultMprSeriesIdx, seriesSupportsMpr } from '../mprEligibility'
import { defaultPrior, formatInterval, formatStudyDate, matchPriorSeries } from '../priors'
import type { SeriesDto, StudyDetail, StudyListItem } from '../types'
import {
  LAYOUT_OPTIONS,
  buildSlots,
  clampActiveId,
  layoutClassName,
  type LayoutMode,
  type ViewportSlot,
} from '../viewportLayout'

type SeriesRailProps = {
  studyUid: string
  series: SeriesDto[]
  activeIdx: number | null
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
  const [error, setError] = useState<string | null>(null)

  const [layout, setLayout] = useState<LayoutMode>('1x1')
  const [slots, setSlots] = useState<ViewportSlot[]>(() => buildSlots('1x1', 1))
  const [activeSlotId, setActiveSlotId] = useState('slot-0')
  const [syncScroll, setSyncScroll] = useState(true)
  const [mprMode, setMprMode] = useState(false)
  const [linkViews, setLinkViews] = useState(true)
  const [mprSeriesIdx, setMprSeriesIdx] = useState(0)

  const [priors, setPriors] = useState<StudyListItem[]>([])
  const [priorId, setPriorId] = useState<string | null>(null)
  const [priorDetail, setPriorDetail] = useState<StudyDetail | null>(null)
  const [priorSeriesIdx, setPriorSeriesIdx] = useState(0)
  const [priorInstanceIdx, setPriorInstanceIdx] = useState(0)

  const viewportRefs = useRef<Record<string, DicomViewportHandle | null>>({})
  const priorViewport = useRef<DicomViewportHandle>(null)

  useEffect(() => {
    let cancelled = false
    api<StudyDetail>(`/api/studies/${id}`)
      .then((data) => {
        if (cancelled) return
        setDetail(data)
        const next = buildSlots('1x1', data.series.length)
        setLayout('1x1')
        setSlots(next)
        setActiveSlotId(next[0]?.id ?? 'slot-0')
        setMprMode(false)
        setMprSeriesIdx(pickDefaultMprSeriesIdx(data.series, data.study.modality))
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

  const comparing = Boolean(priorId && priorDetail) && !mprMode
  // Prior compare always uses a dedicated 1×2 (current | prior). Same-study multi
  // layouts apply only when Compare is Off. MPR replaces the stack grid entirely.
  const effectiveLayout: LayoutMode = comparing ? '1x2' : layout

  const enterMpr = useCallback(() => {
    if (!detail) return
    const preferred = slots.find((s) => s.id === activeSlotId)?.seriesIdx
    const idx = pickDefaultMprSeriesIdx(detail.series, detail.study.modality, preferred)
    if (idx < 0) return
    setPriorId(null)
    setMprSeriesIdx(idx)
    setMprMode(true)
  }, [detail, slots, activeSlotId])

  const exitMpr = useCallback(() => {
    setMprMode(false)
  }, [])

  const applyLayout = useCallback((next: LayoutMode) => {
    if (!detail || mprMode) return
    setLayout(next)
    setSlots((prev) => {
      const built = buildSlots(next, detail.series.length, prev)
      setActiveSlotId((id) => clampActiveId(id, built))
      return built
    })
  }, [detail, mprMode])

  // Seed / clamp slots when series metadata arrives or shrinks.
  useEffect(() => {
    if (!detail || comparing || mprMode) return
    setSlots((prev) => buildSlots(layout, detail.series.length, prev))
  }, [detail?.series.length, layout, comparing, detail, mprMode])

  const activeSlot = slots.find((s) => s.id === activeSlotId) ?? slots[0]
  const activeSeriesIdx = activeSlot?.seriesIdx ?? 0
  const mprSeries = detail?.series?.[mprSeriesIdx]
  const mprEligible = Boolean(
    detail && pickDefaultMprSeriesIdx(detail.series, detail.study.modality) >= 0,
  )

  // Pair prior series to the series shown in the left (first) slot while comparing.
  const compareSeriesIdx = comparing ? (slots[0]?.seriesIdx ?? 0) : activeSeriesIdx
  const compareSeries = detail?.series?.[compareSeriesIdx]

  useEffect(() => {
    if (!priorDetail || !compareSeries) return
    const matched = matchPriorSeries(compareSeries, priorDetail.series)
    if (matched < 0) return
    setPriorSeriesIdx(matched)
    setPriorInstanceIdx(0)
  }, [compareSeries, priorDetail])

  const setSlotSeries = useCallback((slotId: string, seriesIdx: number) => {
    setSlots((prev) => prev.map((s) => (
      s.id === slotId ? { ...s, seriesIdx, instanceIdx: 0 } : s
    )))
  }, [])

  const setSlotInstance = useCallback((slotId: string, instanceIdx: number) => {
    setSlots((prev) => prev.map((s) => (
      s.id === slotId ? { ...s, instanceIdx } : s
    )))
  }, [])

  const onSeriesSelect = useCallback((seriesIdx: number) => {
    if (mprMode) {
      if (!detail || !seriesSupportsMpr(detail.series[seriesIdx], detail.study.modality)) return
      setMprSeriesIdx(seriesIdx)
      return
    }
    if (comparing) {
      const leftId = slots[0]?.id ?? 'slot-0'
      setSlotSeries(leftId, seriesIdx)
      setActiveSlotId(leftId)
      return
    }
    const targetId = activeSlot?.id ?? slots[0]?.id
    if (!targetId) return
    setSlotSeries(targetId, seriesIdx)
    setActiveSlotId(targetId)
  }, [mprMode, detail, comparing, activeSlot?.id, slots, setSlotSeries])

  const onSlotProgress = useCallback((sourceId: string, progress: { fraction: number; positionMm: number | null }) => {
    if (!syncScroll) return
    const drive = (handle: DicomViewportHandle | null | undefined) => {
      if (!handle) return
      if (progress.positionMm != null && handle.goToPosition(progress.positionMm)) return
      handle.goToFraction(progress.fraction)
    }
    if (comparing) {
      if (sourceId === 'current') {
        drive(priorViewport.current)
      } else {
        const leftId = slots[0]?.id
        if (leftId) drive(viewportRefs.current[leftId])
      }
      return
    }
    for (const slot of slots) {
      if (slot.id === sourceId) continue
      drive(viewportRefs.current[slot.id])
    }
  }, [syncScroll, comparing, slots])

  if (!detail) {
    return (
      <div className="page">
        {error ? <div className="error">{error}</div> : <p className="muted">Loading study…</p>}
      </div>
    )
  }

  const study = detail.study
  const priorStudy = priorDetail?.study
  const interval = priorStudy ? formatInterval(priorStudy.studyDate, study.studyDate) : null
  const priorSeries = priorDetail?.series?.[priorSeriesIdx]
  const priorInstance = priorSeries?.instances?.[priorInstanceIdx]
  const priorViewportInstances = (priorSeries?.instances || []).map((i) => ({
    sopUid: i.sopInstanceUid,
    numberOfFrames: i.numberOfFrames,
    imagePositionPatient: i.imagePositionPatient,
    imageOrientationPatient: i.imageOrientationPatient,
  }))

  const showSync = comparing || slots.length > 1

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

        <div className="viewer-layout-toggle" role="group" aria-label="Viewport layout">
          {LAYOUT_OPTIONS.map((opt) => (
            <button
              key={opt.id}
              type="button"
              className={effectiveLayout === opt.id && !comparing && !mprMode ? 'on' : undefined}
              title={
                mprMode
                  ? 'Exit MPR to change layout'
                  : comparing
                    ? 'Turn Compare off to change layout'
                    : opt.title
              }
              disabled={mprMode || (comparing && opt.id !== '1x2')}
              aria-pressed={effectiveLayout === opt.id && !mprMode}
              onClick={() => {
                if (comparing || mprMode) return
                applyLayout(opt.id)
              }}
            >
              {opt.label}
            </button>
          ))}
          {mprEligible && (
            <button
              type="button"
              className={mprMode ? 'on' : undefined}
              title="Multi-planar reconstruction (axial / sagittal / coronal)"
              aria-pressed={mprMode}
              onClick={() => (mprMode ? exitMpr() : enterMpr())}
            >
              MPR
            </button>
          )}
        </div>

        {priors.length > 0 && !mprMode && (
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
          </div>
        )}

        {mprMode ? (
          <label className="viewer-compare-sync">
            <input
              type="checkbox"
              checked={linkViews}
              onChange={(e) => setLinkViews(e.target.checked)}
            />
            Link views
          </label>
        ) : showSync ? (
          <label className="viewer-compare-sync">
            <input
              type="checkbox"
              checked={syncScroll}
              onChange={(e) => setSyncScroll(e.target.checked)}
            />
            Sync scroll
          </label>
        ) : null}

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
            {mprMode
              ? 'Series → MPR volume'
              : comparing
                ? `Current · ${formatStudyDate(study.studyDate)}`
                : slots.length > 1
                  ? 'Series → active pane'
                  : 'Series'}
          </p>
          <SeriesRail
            studyUid={study.studyInstanceUid}
            series={detail.series}
            activeIdx={mprMode ? mprSeriesIdx : comparing ? compareSeriesIdx : activeSeriesIdx}
            onSelect={onSeriesSelect}
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

        {mprMode && mprSeries ? (
          <MprViewer
            key={`${study.studyInstanceUid}:${mprSeries.seriesInstanceUid}`}
            studyUid={study.studyInstanceUid}
            seriesUid={mprSeries.seriesInstanceUid}
            instances={mprSeries.instances.map((i) => ({
              sopUid: i.sopInstanceUid,
              numberOfFrames: i.numberOfFrames,
              imagePositionPatient: i.imagePositionPatient,
              imageOrientationPatient: i.imageOrientationPatient,
            }))}
            modality={mprSeries.modality || study.modality}
            linkViews={linkViews}
          />
        ) : comparing && priorStudy ? (
          <div className={`viewer-panes ${layoutClassName('1x2')}`}>
            <ViewportPane
              label={(
                <>
                  <span className="viewer-pane-tag current">Current</span>
                  {formatStudyDate(study.studyDate)}
                  {compareSeries?.seriesDescription ? ` · ${compareSeries.seriesDescription}` : ''}
                </>
              )}
              active={activeSlotId === (slots[0]?.id ?? 'slot-0')}
              onActivate={() => setActiveSlotId(slots[0]?.id ?? 'slot-0')}
            >
              <SlotViewport
                detail={detail}
                slot={slots[0] ?? { id: 'slot-0', seriesIdx: 0, instanceIdx: 0 }}
                active={activeSlotId === (slots[0]?.id ?? 'slot-0')}
                showToolbar={activeSlotId === (slots[0]?.id ?? 'slot-0')}
                viewportRef={(handle) => {
                  const sid = slots[0]?.id ?? 'slot-0'
                  viewportRefs.current[sid] = handle
                }}
                onInstanceChange={(idx) => setSlotInstance(slots[0]?.id ?? 'slot-0', idx)}
                onProgress={(progress) => onSlotProgress('current', progress)}
              />
            </ViewportPane>
            <ViewportPane
              label={(
                <>
                  <span className="viewer-pane-tag prior">Prior</span>
                  {formatStudyDate(priorStudy.studyDate)}
                  {interval ? ` · ${interval}` : ''}
                  {priorSeries?.seriesDescription ? ` · ${priorSeries.seriesDescription}` : ''}
                </>
              )}
              active={activeSlotId === 'prior'}
              onActivate={() => setActiveSlotId('prior')}
            >
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
                  onProgress={(progress) => onSlotProgress('prior', progress)}
                  active={activeSlotId === 'prior'}
                  showToolbar={activeSlotId === 'prior'}
                />
              ) : (
                <div className="viewport">
                  <div className="muted" style={{ color: '#94a3b8' }}>Loading prior…</div>
                </div>
              )}
            </ViewportPane>
          </div>
        ) : (
          <div className={`viewer-panes ${layoutClassName(effectiveLayout)}`}>
            {slots.map((slot, i) => {
              const series = detail.series[slot.seriesIdx]
              const isActive = slot.id === activeSlotId
              return (
                <ViewportPane
                  key={slot.id}
                  label={(
                    <>
                      <span className="viewer-pane-tag current">View {i + 1}</span>
                      {series?.seriesDescription
                        || (series ? `Series ${series.seriesNumber ?? slot.seriesIdx + 1}` : 'Empty')}
                    </>
                  )}
                  active={isActive}
                  onActivate={() => setActiveSlotId(slot.id)}
                >
                  <SlotViewport
                    detail={detail}
                    slot={slot}
                    active={isActive}
                    showToolbar={isActive}
                    viewportRef={(handle) => { viewportRefs.current[slot.id] = handle }}
                    onInstanceChange={(idx) => setSlotInstance(slot.id, idx)}
                    onProgress={(progress) => onSlotProgress(slot.id, progress)}
                  />
                </ViewportPane>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

function ViewportPane({
  label,
  active,
  onActivate,
  children,
}: {
  label: ReactNode
  active: boolean
  onActivate: () => void
  children: ReactNode
}) {
  return (
    <section
      className={`viewer-pane ${active ? 'active' : ''}`}
      onMouseDown={onActivate}
    >
      <header className="viewer-pane-label">{label}</header>
      {children}
    </section>
  )
}

function SlotViewport({
  detail,
  slot,
  active,
  showToolbar,
  viewportRef,
  onInstanceChange,
  onProgress,
}: {
  detail: StudyDetail
  slot: ViewportSlot
  active: boolean
  showToolbar: boolean
  viewportRef: Ref<DicomViewportHandle>
  onInstanceChange: (idx: number) => void
  onProgress: (progress: { fraction: number; positionMm: number | null }) => void
}) {
  const study = detail.study
  const series = detail.series[slot.seriesIdx]
  const instance = series?.instances?.[slot.instanceIdx]
  const instances = useMemo(
    () => (series?.instances || []).map((i) => ({
      sopUid: i.sopInstanceUid,
      numberOfFrames: i.numberOfFrames,
      imagePositionPatient: i.imagePositionPatient,
      imageOrientationPatient: i.imageOrientationPatient,
    })),
    [series],
  )

  if (!series || !instance) {
    return (
      <div className="viewport">
        <div className="muted" style={{ color: '#94a3b8' }}>No frame available</div>
      </div>
    )
  }

  return (
    <DicomViewport
      ref={viewportRef}
      studyUid={study.studyInstanceUid}
      seriesUid={series.seriesInstanceUid}
      instances={instances}
      instanceIndex={slot.instanceIdx}
      cineRate={instance.cineRate}
      frameTime={instance.frameTime}
      modality={series.modality || study.modality}
      onInstanceChange={onInstanceChange}
      onProgress={onProgress}
      active={active}
      showToolbar={showToolbar}
    />
  )
}

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode, type Ref } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api'
import { DicomViewport, type DicomViewportHandle, type ViewportPersistence } from '../components/DicomViewport'
import { useAuth } from '../auth'
import {
  type KeyImage,
  type KeyImageRequest,
  addKeyImage,
  deleteKeyImage,
  keyImageBlob,
  listKeyImages,
} from '../studyAnnotations'
import { MprViewer } from '../components/MprViewer'
import { PatientSharePanel } from '../components/PatientSharePanel'
import { PrintPreviewModal } from '../components/PrintPreviewModal'
import { SeriesThumbnail } from '../components/SeriesThumbnail'
import { ViewerExportMenu } from '../components/ViewerExportMenu'
import { clearShareToken, shareApi, shareBlob } from '../shareApi'
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
import { ViewerToolsSlotContext } from '../viewerToolsSlot'

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

type StudyViewerProps = {
  /** Public patient share: same viewer, staff chrome hidden; data via share JWT. */
  shareMode?: boolean
}

export function StudyViewerPage({ shareMode = false }: StudyViewerProps) {
  const { id, token: shareLinkToken } = useParams()
  const navigate = useNavigate()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  const [layout, setLayout] = useState<LayoutMode>('1x1')
  const [slots, setSlots] = useState<ViewportSlot[]>(() => buildSlots('1x1', 1))
  const [activeSlotId, setActiveSlotId] = useState('slot-0')
  const [syncScroll, setSyncScroll] = useState(true)
  const [mprMode, setMprMode] = useState(false)
  const [linkViews, setLinkViews] = useState(true)
  const [mprSeriesIdx, setMprSeriesIdx] = useState(0)
  const [printOpen, setPrintOpen] = useState(false)

  const [priors, setPriors] = useState<StudyListItem[]>([])
  const [priorId, setPriorId] = useState<string | null>(null)
  const [priorDetail, setPriorDetail] = useState<StudyDetail | null>(null)
  const [priorSeriesIdx, setPriorSeriesIdx] = useState(0)
  const [priorInstanceIdx, setPriorInstanceIdx] = useState(0)

  const viewportRefs = useRef<Record<string, DicomViewportHandle | null>>({})

  // ---- saved measurements / key images (current study only; never in patient share mode)
  const { user } = useAuth()
  const studyId = detail?.study.id
  const canEdit = !shareMode && (user?.role === 'RADIOLOGIST' || user?.role === 'ADMIN')
  const [keyImages, setKeyImages] = useState<KeyImage[]>([])
  const [keyThumbs, setKeyThumbs] = useState<Record<string, string>>({})

  const reloadKeyImages = useCallback(async () => {
    if (!studyId || shareMode) return
    setKeyImages(await listKeyImages(studyId))
  }, [studyId, shareMode])

  useEffect(() => {
    setKeyImages([])
    reloadKeyImages().catch(() => undefined)
  }, [reloadKeyImages])

  // Thumbnails (object URLs) for the sidebar; fetched once per key image.
  useEffect(() => {
    if (!studyId) return
    let cancelled = false
    for (const k of keyImages) {
      if (!k.hasSnapshot || keyThumbs[k.id]) continue
      keyImageBlob(studyId, k.id)
        .then((b) => {
          if (cancelled) return
          const url = URL.createObjectURL(b)
          setKeyThumbs((prev) => ({ ...prev, [k.id]: url }))
        })
        .catch(() => undefined)
    }
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- keyThumbs only grows
  }, [keyImages, studyId])
  useEffect(() => () => { Object.values(keyThumbs).forEach((u) => URL.revokeObjectURL(u)) }, [])  // eslint-disable-line react-hooks/exhaustive-deps

  const persistBase = useMemo<Omit<ViewportPersistence, 'seriesDescription'> | null>(() => {
    if (!studyId || shareMode) return null
    return {
      studyId,
      canEdit,
      canEditOthers: user?.role === 'ADMIN',
      keyImages,
      onAddKeyImage: async (req: KeyImageRequest) => { await addKeyImage(studyId, req); await reloadKeyImages() },
      onRemoveKeyImage: async (id: string) => { await deleteKeyImage(studyId, id); await reloadKeyImages() },
    }
  }, [studyId, shareMode, canEdit, user?.role, keyImages, reloadKeyImages])
  const priorViewport = useRef<DicomViewportHandle>(null)
  const [toolsSlot, setToolsSlot] = useState<HTMLElement | null>(null)

  // Viewer is a fixed workspace: never let the document scroll (Cornerstone
  // thumbnail temp nodes and other offscreen hosts must not reveal page bg).
  useEffect(() => {
    const html = document.documentElement
    const prevHtml = html.style.overflow
    const prevBody = document.body.style.overflow
    html.style.overflow = 'hidden'
    document.body.style.overflow = 'hidden'
    return () => {
      html.style.overflow = prevHtml
      document.body.style.overflow = prevBody
    }
  }, [])

  const studyPath = useCallback(
    (studyId: string) =>
      shareMode ? `/api/public/shares/me/studies/${studyId}` : `/api/studies/${studyId}`,
    [shareMode],
  )
  const priorsPath = useCallback(
    (studyId: string) =>
      shareMode
        ? `/api/public/shares/me/studies/${studyId}/priors`
        : `/api/studies/${studyId}/priors`,
    [shareMode],
  )
  const loadStudy = useCallback(
    <T,>(path: string) => (shareMode ? shareApi<T>(path) : api<T>(path)),
    [shareMode],
  )

  useEffect(() => {
    if (!id) return
    let cancelled = false
    loadStudy<StudyDetail>(studyPath(id))
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
  }, [id, loadStudy, studyPath])

  // Hanging protocol: frozen priors in share mode; live priors for staff.
  useEffect(() => {
    if (!id) return
    let cancelled = false
    setPriors([])
    setPriorId(null)
    loadStudy<StudyListItem[]>(priorsPath(id))
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
  }, [id, detail?.study.modality, loadStudy, priorsPath])

  useEffect(() => {
    if (!priorId) {
      setPriorDetail(null)
      return
    }
    let cancelled = false
    setPriorSeriesIdx(0)
    setPriorInstanceIdx(0)
    loadStudy<StudyDetail>(studyPath(priorId))
      .then((data) => {
        if (!cancelled) setPriorDetail(data)
      })
      .catch(() => {
        if (!cancelled) setPriorDetail(null)
      })
    return () => {
      cancelled = true
    }
  }, [priorId, loadStudy, studyPath])

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

  /** Show a key image in the active pane (left pane when comparing), with its window/level. */
  const jumpToKeyImage = useCallback((k: KeyImage) => {
    if (!detail) return
    const seriesIdx = detail.series.findIndex((s) => s.seriesInstanceUid === k.seriesInstanceUid)
    if (seriesIdx < 0) return
    const instanceIdx = Math.max(0, detail.series[seriesIdx].instances.findIndex((i) => i.sopInstanceUid === k.sopInstanceUid))
    if (mprMode) setMprMode(false)
    const targetId = comparing ? (slots[0]?.id ?? 'slot-0') : (activeSlot?.id ?? slots[0]?.id ?? 'slot-0')
    const wl = k.windowWidth && k.windowWidth > 0 && k.windowCenter != null
      ? { center: k.windowCenter, width: k.windowWidth, key: `${k.id}:${Date.now()}` }
      : undefined
    setSlots((prev) => prev.map((s) => (s.id === targetId ? { ...s, seriesIdx, instanceIdx, wl } : s)))
    setActiveSlotId(targetId)
  }, [detail, mprMode, comparing, slots, activeSlot])

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
    rows: i.rows,
    columns: i.columns,
    transferSyntaxUid: i.transferSyntaxUid,
    imagePositionPatient: i.imagePositionPatient,
    imageOrientationPatient: i.imageOrientationPatient,
  }))

  const showSync = comparing || slots.length > 1

  const exportSeries = mprMode
    ? mprSeries
    : comparing
      ? (activeSlotId === 'prior' ? priorSeries : compareSeries)
      : detail.series[activeSeriesIdx]
  const exportInstance = exportSeries?.instances?.[
    mprMode
      ? 0
      : comparing && activeSlotId === 'prior'
        ? priorInstanceIdx
        : (activeSlot?.instanceIdx ?? 0)
  ] ?? exportSeries?.instances?.[0]

  return (
    <ViewerToolsSlotContext.Provider value={toolsSlot}>
      <div className="viewer-shell">
        <div className="viewer-topstrip">
          <div className="viewer-topstrip-row">
            <div className="viewer-topstrip-id">
              <strong>{study.patientName || 'Unknown patient'}</strong>
              <span className="muted">MRN {study.patientMrn || '—'}</span>
              <span className="mono muted">{study.accessionNumber || '—'}</span>
              <span className="muted">{study.modality || '—'}</span>
            </div>
            <div className="viewer-topstrip-meta">
              {!shareMode && (
                <span className={`badge ${study.priority === 'STAT' ? 'stat' : ''}`}>{study.priority}</span>
              )}
              {!shareMode && <span className="badge">{study.status}</span>}
              {!shareMode && study.slaDueAt && (
                <span className={`badge ${study.slaOverdue ? 'breached' : ''}`}>
                  {study.slaOverdue ? 'SLA Breached' : `Due ${new Date(study.slaDueAt).toLocaleString()}`}
                </span>
              )}
              {detail.report?.status === 'SIGNED' && <span className="badge">Report signed</span>}
              {shareMode && <span className="badge">Shared study</span>}
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
              <ViewerExportMenu
                studyId={study.id}
                patientLabel={study.patientName || study.accessionNumber || study.id}
                seriesInstanceUid={exportSeries?.seriesInstanceUid ?? null}
                sopInstanceUid={exportInstance?.sopInstanceUid ?? null}
                allowDicom={!shareMode}
              />
              {!shareMode && (
                <PatientSharePanel
                  studyId={study.id}
                  patientLabel={study.patientName || study.accessionNumber || study.id}
                  compact
                />
              )}
              <button type="button" className="btn secondary" onClick={() => setPrintOpen(true)}>
                Print
              </button>
              {shareMode && detail.report?.hasPdf && (
                <button
                  type="button"
                  className="btn secondary"
                  onClick={async () => {
                    try {
                      const blob = await shareBlob(`/api/public/shares/me/studies/${study.id}/report.pdf`)
                      const a = document.createElement('a')
                      a.href = URL.createObjectURL(blob)
                      a.download = `report-${study.id}.pdf`
                      a.click()
                    } catch {
                      /* ignore */
                    }
                  }}
                >
                  Report PDF
                </button>
              )}
              {shareMode ? (
                <button
                  type="button"
                  className="btn secondary"
                  onClick={() => {
                    clearShareToken()
                    navigate(shareLinkToken ? `/s/${shareLinkToken}` : '/')
                  }}
                >
                  Lock again
                </button>
              ) : (
                <>
                  <Link className="btn secondary" to="/worklist">
                    Back to worklist
                  </Link>
                  <a
                    className="btn"
                    href={`/studies/${id}/report`}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Report
                  </a>
                </>
              )}
            </div>
          </div>
          {/* Active stack viewport portals its tools here — never over the image. */}
          <div ref={setToolsSlot} className="viewer-chrome-tools" />
        </div>

        <div className="viewer-layout">
          <aside className="viewer-series" aria-label="Series">
            {!shareMode && keyImages.length > 0 && (
              <>
                <p className="viewer-series-label">Key images · {keyImages.length}</p>
                <div className="key-image-rail">
                  {keyImages.map((k) => (
                    <button
                      key={k.id}
                      type="button"
                      className="key-image-card"
                      onClick={() => jumpToKeyImage(k)}
                      title={`${k.location ?? 'Key image'}${k.createdByName ? ` · ${k.createdByName}` : ''}`}
                    >
                      {keyThumbs[k.id]
                        ? <img src={keyThumbs[k.id]} alt="" />
                        : <span className="key-image-placeholder">★</span>}
                      <span className="key-image-caption">{k.location ?? 'Key image'}</span>
                    </button>
                  ))}
                </div>
              </>
            )}
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
                  persist={persistBase}
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
                      persist={persistBase}
                    />
                  </ViewportPane>
                )
              })}
            </div>
          )}
        </div>
      </div>
      <PrintPreviewModal
        open={printOpen}
        onClose={() => setPrintOpen(false)}
        study={study}
      />
    </ViewerToolsSlotContext.Provider>
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
  persist,
}: {
  detail: StudyDetail
  slot: ViewportSlot
  persist: Omit<ViewportPersistence, 'seriesDescription'> | null
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
      rows: i.rows,
      columns: i.columns,
      transferSyntaxUid: i.transferSyntaxUid,
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
      persist={persist ? { ...persist, seriesDescription: series.seriesDescription } : null}
      windowOverride={slot.wl ?? null}
    />
  )
}

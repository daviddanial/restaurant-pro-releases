import { useCallback, useEffect, useRef, useState } from 'react'
import { loadAuth } from '../api'

export type WlPreset = { label: string; center: number; width: number }

export const WL_PRESETS: Record<string, WlPreset[]> = {
  CT: [
    { label: 'Soft tissue', center: 40, width: 400 },
    { label: 'Lung', center: -600, width: 1500 },
    { label: 'Bone', center: 400, width: 1800 },
    { label: 'Brain', center: 40, width: 80 },
  ],
  XR: [
    { label: 'Default', center: 2048, width: 4096 },
    { label: 'High contrast', center: 2000, width: 2000 },
  ],
  MR: [
    { label: 'Wide', center: 1024, width: 2048 },
    { label: 'Narrow', center: 512, width: 512 },
  ],
  DEFAULT: [
    { label: 'Wide', center: 128, width: 512 },
    { label: 'Default', center: 128, width: 256 },
  ],
}

type WlState = { center: number; width: number }

type Props = {
  studyUid: string
  seriesUid: string
  sopUid: string
  instanceIndex: number
  instanceCount: number
  numberOfFrames?: number
  cineRate?: number | null
  frameTime?: number | null
  modality?: string | null
  onInstanceChange: (idx: number) => void
}

type Tool = 'wwwc' | 'zoom' | 'pan'

type InstanceMeta = {
  WindowCenter?: string | null
  WindowWidth?: string | null
  CineRate?: number
  FrameTime?: number
  Modality?: string | null
}

/** First value of a DICOM multi-valued numeric string (e.g. "1951\\40"). */
function parseDicomNumber(value?: string | null): number | null {
  if (value == null || value === '') return null
  const first = String(value).split('\\')[0].trim()
  const n = Number(first)
  return Number.isFinite(n) ? n : null
}

/**
 * Modality-specific fallback when DICOM WindowCenter/Width are absent.
 * Never apply CT soft-tissue (40/400) to MR or other modalities.
 * Returns null → omit W/L query params so the server uses DICOM VOI / auto-window.
 */
function modalityFallbackWl(modality?: string | null): WlState | null {
  const m = (modality || '').toUpperCase()
  if (m === 'CT') return { center: 40, width: 400 }
  if (m === 'CR' || m === 'DX' || m === 'XR') return { center: 2048, width: 4096 }
  return null
}

function presetsForModality(modality?: string | null): WlPreset[] {
  const m = (modality || '').toUpperCase()
  if (m === 'CT') return WL_PRESETS.CT
  if (m === 'CR' || m === 'DX' || m === 'XR') return WL_PRESETS.XR
  if (m === 'MR') return WL_PRESETS.MR
  return WL_PRESETS.DEFAULT
}

function resolveFps(opts: {
  cineRate?: number | null
  frameTime?: number | null
  modality?: string | null
}): number {
  if (opts.cineRate && opts.cineRate > 0) return Math.min(60, Math.max(1, opts.cineRate))
  if (opts.frameTime && opts.frameTime > 0) return Math.min(60, Math.max(1, 1000 / opts.frameTime))
  if (opts.modality === 'US') return 20
  return 15
}

/**
 * Cornerstone3D-oriented viewport: WADO-RS JPEG frames with WL / zoom / pan / invert / cine.
 * Server applies VOI via windowCenter/windowWidth query params on the frame endpoint.
 * When params are omitted, the server uses DICOM WindowCenter/Width or auto-windows.
 */
export function DicomViewport({
  studyUid,
  seriesUid,
  sopUid,
  instanceIndex,
  instanceCount,
  numberOfFrames = 1,
  cineRate,
  frameTime,
  modality,
  onInstanceChange,
}: Props) {
  const frameCount = Math.max(1, numberOfFrames)
  const multiFrame = frameCount > 1

  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const containerRef = useRef<HTMLDivElement | null>(null)
  const imgRef = useRef<HTMLImageElement | null>(null)
  const cacheRef = useRef<Map<number, string>>(new Map())
  const playingRef = useRef(false)
  const studyDefaultWlRef = useRef<WlState | null>(null)

  const [invert, setInvert] = useState(false)
  const [tool, setTool] = useState<Tool>('wwwc')
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  // Start unset — never force CT 40/400 onto MR. Resolved from DICOM metadata.
  const [wl, setWl] = useState<WlState>({ center: 0, width: 0 })
  const [committedWl, setCommittedWl] = useState<WlState>({ center: 0, width: 0 })
  /** When true, omit W/L query params so server applies DICOM VOI / auto-window. */
  const [serverAutoWl, setServerAutoWl] = useState(true)
  const [wlReady, setWlReady] = useState(false)
  const [studyDefaultWl, setStudyDefaultWl] = useState<WlState | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [frameIndex, setFrameIndex] = useState(1) // 1-based
  const [playing, setPlaying] = useState(false)
  const [fps, setFps] = useState(() => resolveFps({ cineRate, frameTime, modality }))
  const wlTimer = useRef<number | null>(null)

  const dragRef = useRef<{
    active: boolean
    originX: number
    originY: number
    startWl: WlState
    startPan: { x: number; y: number }
    startZoom: number
  } | null>(null)

  const presets = presetsForModality(modality)

  // Resolve W/L from DICOM metadata (prefer WindowCenter/Width) before loading frames
  useEffect(() => {
    let cancelled = false
    setWlReady(false)
    setServerAutoWl(true)
    setPlaying(false)
    playingRef.current = false
    setFrameIndex(1)
    setFps(resolveFps({ cineRate, frameTime, modality }))
    for (const url of cacheRef.current.values()) URL.revokeObjectURL(url)
    cacheRef.current.clear()

    async function resolveWl() {
      try {
        const token = loadAuth()?.accessToken
        const res = await fetch(`/api/dicom/instances/${sopUid}/metadata`, {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
        })
        if (!res.ok) throw new Error(`metadata ${res.status}`)
        const meta = await res.json() as InstanceMeta
        if (cancelled) return

        const wc = parseDicomNumber(meta.WindowCenter)
        const ww = parseDicomNumber(meta.WindowWidth)
        if (wc != null && ww != null && ww > 0) {
          const next = { center: wc, width: ww }
          studyDefaultWlRef.current = next
          setStudyDefaultWl(next)
          setWl(next)
          setCommittedWl(next)
          setServerAutoWl(false)
        } else {
          const fallback = modalityFallbackWl(meta.Modality || modality)
          studyDefaultWlRef.current = fallback
          setStudyDefaultWl(fallback)
          if (fallback) {
            setWl(fallback)
            setCommittedWl(fallback)
            setServerAutoWl(false)
          } else {
            // Server auto-window / DICOM VOI — no query params
            setWl({ center: 0, width: 0 })
            setCommittedWl({ center: 0, width: 0 })
            setServerAutoWl(true)
          }
        }

        if (multiFrame && !((cineRate && cineRate > 0) || (frameTime && frameTime > 0))) {
          setFps(resolveFps({
            cineRate: meta.CineRate,
            frameTime: meta.FrameTime,
            modality,
          }))
        }
      } catch {
        if (cancelled) return
        const fallback = modalityFallbackWl(modality)
        studyDefaultWlRef.current = fallback
        setStudyDefaultWl(fallback)
        if (fallback) {
          setWl(fallback)
          setCommittedWl(fallback)
          setServerAutoWl(false)
        } else {
          setWl({ center: 0, width: 0 })
          setCommittedWl({ center: 0, width: 0 })
          setServerAutoWl(true)
        }
      } finally {
        if (!cancelled) setWlReady(true)
      }
    }

    resolveWl()
    return () => { cancelled = true }
  }, [sopUid, modality, cineRate, frameTime, multiFrame])

  const redraw = useCallback(() => {
    const canvas = canvasRef.current
    const img = imgRef.current
    const container = containerRef.current
    if (!canvas || !img || !container || !img.complete) return
    const dpr = window.devicePixelRatio || 1
    const w = container.clientWidth
    const h = container.clientHeight
    canvas.width = Math.max(1, Math.floor(w * dpr))
    canvas.height = Math.max(1, Math.floor(h * dpr))
    canvas.style.width = `${w}px`
    canvas.style.height = `${h}px`
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.fillStyle = '#000'
    ctx.fillRect(0, 0, w, h)

    const scale = Math.min(w / img.naturalWidth, h / img.naturalHeight) * zoom
    const drawW = img.naturalWidth * scale
    const drawH = img.naturalHeight * scale
    const x = (w - drawW) / 2 + pan.x
    const y = (h - drawH) / 2 + pan.y

    ctx.save()
    if (invert) ctx.filter = 'invert(1)'
    ctx.imageSmoothingEnabled = true
    ctx.drawImage(img, x, y, drawW, drawH)
    ctx.restore()

    const counter = multiFrame
      ? `${frameIndex} / ${frameCount}`
      : `${instanceIndex + 1} / ${instanceCount}`

    const wlLabel = serverAutoWl && wl.width <= 0
      ? 'W/L: Auto'
      : `W: ${Math.round(wl.width)}  L: ${Math.round(wl.center)}`

    ctx.fillStyle = 'rgba(232,238,242,0.85)'
    ctx.font = '12px IBM Plex Sans, sans-serif'
    ctx.fillText(wlLabel, 12, 20)
    ctx.fillText(`Zoom: ${zoom.toFixed(2)}x`, 12, 38)
    if (playing) ctx.fillText(`Cine ${fps.toFixed(0)} fps`, 12, 56)
    ctx.fillText(counter, 12, h - 14)
  }, [invert, zoom, pan, wl, serverAutoWl, instanceIndex, instanceCount, frameIndex, frameCount, multiFrame, playing, fps])

  const frameUrl = useCallback((frame: number) => {
    const base = `/api/dicom/studies/${studyUid}/series/${seriesUid}/instances/${sopUid}/frames/${frame}`
    if (serverAutoWl || committedWl.width <= 0) {
      return base
    }
    const qs = new URLSearchParams({
      windowCenter: String(committedWl.center),
      windowWidth: String(committedWl.width),
    })
    return `${base}?${qs}`
  }, [studyUid, seriesUid, sopUid, committedWl.center, committedWl.width, serverAutoWl])

  const fetchFrameBlobUrl = useCallback(async (frame: number): Promise<string> => {
    const cached = cacheRef.current.get(frame)
    if (cached) return cached
    const token = loadAuth()?.accessToken
    const res = await fetch(frameUrl(frame), {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    })
    if (!res.ok) throw new Error(`Frame HTTP ${res.status}`)
    const blob = await res.blob()
    const objectUrl = URL.createObjectURL(blob)
    if (cacheRef.current.size > 48) {
      const first = cacheRef.current.keys().next().value
      if (first != null) {
        const old = cacheRef.current.get(first)
        if (old) URL.revokeObjectURL(old)
        cacheRef.current.delete(first)
      }
    }
    cacheRef.current.set(frame, objectUrl)
    return objectUrl
  }, [frameUrl])

  const prefetchAround = useCallback((center: number) => {
    if (!multiFrame) return
    const neighbors = [center + 1, center - 1, center + 2, center + 3]
      .map((n) => ((n - 1 + frameCount) % frameCount) + 1)
    for (const n of neighbors) {
      if (cacheRef.current.has(n)) continue
      fetchFrameBlobUrl(n).catch(() => undefined)
    }
  }, [multiFrame, frameCount, fetchFrameBlobUrl])

  // Clear cache when WL changes (frames re-rendered server-side)
  useEffect(() => {
    for (const url of cacheRef.current.values()) URL.revokeObjectURL(url)
    cacheRef.current.clear()
  }, [committedWl.center, committedWl.width, serverAutoWl])

  useEffect(() => {
    if (!wlReady) return
    let cancelled = false
    async function load() {
      if (!playingRef.current) setLoading(true)
      setError(null)
      try {
        const objectUrl = await fetchFrameBlobUrl(frameIndex)
        if (cancelled) return
        const img = new Image()
        img.onload = () => {
          if (cancelled) return
          imgRef.current = img
          setLoading(false)
          redraw()
          prefetchAround(frameIndex)
        }
        img.onerror = () => {
          if (cancelled) return
          setError('Failed to decode frame')
          setLoading(false)
        }
        img.src = objectUrl
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Frame load failed')
          setLoading(false)
        }
      }
    }
    load()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- redraw after image load
  }, [wlReady, studyUid, seriesUid, sopUid, frameIndex, committedWl.center, committedWl.width, serverAutoWl, fetchFrameBlobUrl, prefetchAround])

  function scheduleCommitWl(next: WlState) {
    setServerAutoWl(false)
    setWl(next)
    if (wlTimer.current) window.clearTimeout(wlTimer.current)
    wlTimer.current = window.setTimeout(() => setCommittedWl(next), 180)
  }

  useEffect(() => {
    redraw()
  }, [redraw])

  useEffect(() => {
    const onResize = () => redraw()
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [redraw])

  const stepFrame = useCallback((delta: number) => {
    if (multiFrame) {
      setFrameIndex((cur) => {
        const next = cur + delta
        if (next < 1) return frameCount
        if (next > frameCount) return 1
        return next
      })
      return
    }
    if (delta > 0) onInstanceChange(Math.min(instanceIndex + 1, instanceCount - 1))
    else onInstanceChange(Math.max(instanceIndex - 1, 0))
  }, [multiFrame, frameCount, instanceIndex, instanceCount, onInstanceChange])

  useEffect(() => {
    playingRef.current = playing
    if (!playing || !multiFrame) return
    const intervalMs = Math.max(16, Math.round(1000 / fps))
    const id = window.setInterval(() => {
      setFrameIndex((cur) => (cur >= frameCount ? 1 : cur + 1))
    }, intervalMs)
    return () => window.clearInterval(id)
  }, [playing, multiFrame, fps, frameCount])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement | null)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (e.target as HTMLElement)?.isContentEditable) {
        return
      }
      if (e.key === 'ArrowDown' || e.key === 'PageDown' || e.key === 'ArrowRight') {
        stepFrame(1)
      }
      if (e.key === 'ArrowUp' || e.key === 'PageUp' || e.key === 'ArrowLeft') {
        stepFrame(-1)
      }
      if (e.key === ' ' && multiFrame) {
        e.preventDefault()
        setPlaying((p) => !p)
      }
      if (e.key === 'i' || e.key === 'I') setInvert((v) => !v)
      if (e.key === 'r' || e.key === 'R') {
        setZoom(1)
        setPan({ x: 0, y: 0 })
        const def = studyDefaultWlRef.current
        if (def) {
          setServerAutoWl(false)
          setWl(def)
          setCommittedWl(def)
        } else {
          setServerAutoWl(true)
          setWl({ center: 0, width: 0 })
          setCommittedWl({ center: 0, width: 0 })
        }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [stepFrame, multiFrame])

  function onWheel(e: React.WheelEvent) {
    e.preventDefault()
    if (e.ctrlKey || e.metaKey) {
      setZoom((z) => Math.min(8, Math.max(0.25, z * (e.deltaY > 0 ? 0.9 : 1.1))))
      return
    }
    stepFrame(e.deltaY > 0 ? 1 : -1)
  }

  function onPointerDown(e: React.PointerEvent) {
    ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
    const startWl = serverAutoWl || wl.width <= 0
      ? (studyDefaultWl ?? modalityFallbackWl(modality) ?? { center: 128, width: 256 })
      : { ...wl }
    if (serverAutoWl || wl.width <= 0) {
      setServerAutoWl(false)
      setWl(startWl)
      setCommittedWl(startWl)
    }
    dragRef.current = {
      active: true,
      originX: e.clientX,
      originY: e.clientY,
      startWl,
      startPan: { ...pan },
      startZoom: zoom,
    }
  }

  function onPointerMove(e: React.PointerEvent) {
    const d = dragRef.current
    if (!d?.active) return
    const dx = e.clientX - d.originX
    const dy = e.clientY - d.originY
    const usePan = tool === 'pan' || e.shiftKey
    const useZoom = tool === 'zoom' || e.altKey
    if (usePan) {
      setPan({ x: d.startPan.x + dx, y: d.startPan.y + dy })
    } else if (useZoom) {
      setZoom(Math.min(8, Math.max(0.25, d.startZoom * (1 - dy / 200))))
    } else {
      scheduleCommitWl({
        width: Math.max(1, d.startWl.width + dx * 2),
        center: d.startWl.center - dy * 2,
      })
    }
  }

  function onPointerUp() {
    if (dragRef.current) dragRef.current.active = false
    if (!serverAutoWl && wl.width > 0) setCommittedWl(wl)
  }

  function resetView() {
    setZoom(1)
    setPan({ x: 0, y: 0 })
    setInvert(false)
    const def = studyDefaultWlRef.current
    if (def) {
      setServerAutoWl(false)
      setWl(def)
      setCommittedWl(def)
    } else {
      setServerAutoWl(true)
      setWl({ center: 0, width: 0 })
      setCommittedWl({ center: 0, width: 0 })
    }
  }

  function applyPreset(next: WlState) {
    setServerAutoWl(false)
    setWl(next)
    setCommittedWl(next)
  }

  function applyStudyDefault() {
    const def = studyDefaultWlRef.current
    if (def) {
      applyPreset(def)
    } else {
      setServerAutoWl(true)
      setWl({ center: 0, width: 0 })
      setCommittedWl({ center: 0, width: 0 })
    }
  }

  const counterLabel = multiFrame
    ? `${frameIndex} / ${frameCount}`
    : `${instanceIndex + 1} / ${instanceCount}`

  const selectValue = serverAutoWl || wl.width <= 0
    ? 'auto'
    : studyDefaultWl
      && Math.round(wl.center) === Math.round(studyDefaultWl.center)
      && Math.round(wl.width) === Math.round(studyDefaultWl.width)
      ? 'study'
      : `${wl.center}:${wl.width}`

  return (
    <div className="viewport" ref={containerRef} onWheel={onWheel}>
      <canvas
        ref={canvasRef}
        className="viewport-canvas"
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      />
      {(!wlReady || (loading && !playing)) && <div className="viewport-overlay muted">Loading frame…</div>}
      {error && <div className="viewport-overlay error">{error}</div>}
      <div className="viewport-tools">
        <button className={`btn secondary ${tool === 'wwwc' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('wwwc')}>W/L</button>
        <button className={`btn secondary ${tool === 'zoom' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('zoom')}>Zoom</button>
        <button className={`btn secondary ${tool === 'pan' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('pan')}>Pan</button>
        <button className="btn secondary" type="button" onClick={() => setInvert((v) => !v)}>Invert</button>
        <button className="btn secondary" type="button" onClick={() => stepFrame(-1)}>Prev</button>
        <button className="btn secondary" type="button" onClick={() => stepFrame(1)}>Next</button>
        {multiFrame && (
          <>
            <button
              className={`btn secondary ${playing ? 'active-tool' : ''}`}
              type="button"
              onClick={() => setPlaying((p) => !p)}
              title="Space"
            >
              {playing ? 'Pause' : 'Play'}
            </button>
            <label className="cine-fps" title="Frames per second">
              <span>{Math.round(fps)} fps</span>
              <input
                type="range"
                min={5}
                max={40}
                step={1}
                value={Math.round(fps)}
                onChange={(e) => setFps(Number(e.target.value))}
              />
            </label>
          </>
        )}
        <button className="btn secondary" type="button" onClick={resetView}>Reset</button>
        <select
          value={selectValue}
          onChange={(e) => {
            const v = e.target.value
            if (v === 'auto') {
              setServerAutoWl(true)
              setWl({ center: 0, width: 0 })
              setCommittedWl({ center: 0, width: 0 })
              return
            }
            if (v === 'study') {
              applyStudyDefault()
              return
            }
            const [c, w] = v.split(':').map(Number)
            applyPreset({ center: c, width: w })
          }}
        >
          <option value="auto">Auto (DICOM / server)</option>
          {studyDefaultWl && (
            <option value="study">
              Study default (W:{Math.round(studyDefaultWl.width)} L:{Math.round(studyDefaultWl.center)})
            </option>
          )}
          {presets.map((p) => (
            <option key={p.label} value={`${p.center}:${p.width}`}>{p.label}</option>
          ))}
          {!serverAutoWl && wl.width > 0 && selectValue !== 'study' && selectValue !== 'auto'
            && !presets.some((p) => p.center === wl.center && p.width === wl.width) && (
            <option value={`${wl.center}:${wl.width}`}>Custom</option>
          )}
        </select>
        <span className="badge">{counterLabel}</span>
      </div>
    </div>
  )
}

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Enums,
  RenderingEngine,
  type Types,
  utilities,
  getRenderingEngine,
} from '@cornerstonejs/core'
import {
  Enums as ToolsEnums,
  ToolGroupManager,
  WindowLevelTool,
  PanTool,
  ZoomTool,
  StackScrollTool,
  LengthTool,
} from '@cornerstonejs/tools'
import { buildWadoUriImageId, ensureCornerstoneInit } from '../cornerstone/initCornerstone'
import { presetsForModality } from '../wlPresets'

export type ViewportInstance = {
  sopUid: string
  numberOfFrames: number
}

type Props = {
  studyUid: string
  seriesUid: string
  instances: ViewportInstance[]
  instanceIndex: number
  cineRate?: number | null
  frameTime?: number | null
  modality?: string | null
  onInstanceChange: (idx: number) => void
}

type Tool = 'wwwc' | 'zoom' | 'pan' | 'length'

type StackEntry = {
  imageId: string
  instanceIndex: number
  frameIndex: number // 1-based within SOP when multi-frame; else 1
}

type WlState = { center: number; width: number }

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

function buildStack(
  studyUid: string,
  seriesUid: string,
  instances: ViewportInstance[],
): StackEntry[] {
  const stack: StackEntry[] = []
  instances.forEach((inst, instanceIndex) => {
    const frames = Math.max(1, inst.numberOfFrames || 1)
    if (frames > 1) {
      for (let f = 1; f <= frames; f++) {
        stack.push({
          imageId: buildWadoUriImageId(studyUid, seriesUid, inst.sopUid, f),
          instanceIndex,
          frameIndex: f,
        })
      }
    } else {
      stack.push({
        imageId: buildWadoUriImageId(studyUid, seriesUid, inst.sopUid),
        instanceIndex,
        frameIndex: 1,
      })
    }
  })
  return stack
}

function firstStackIndexForInstance(stack: StackEntry[], instanceIndex: number): number {
  const idx = stack.findIndex((e) => e.instanceIndex === instanceIndex)
  return idx >= 0 ? idx : 0
}

function applyWl(viewport: Types.IStackViewport, wl: WlState) {
  if (!(wl.width > 0)) return
  const voiRange = utilities.windowLevel.toLowHighRange(wl.width, wl.center)
  viewport.setProperties({ voiRange })
  viewport.render()
}

/**
 * Cornerstone3D stack viewport: WADO-URI DICOM bytes with W/L, zoom, pan, invert,
 * stack scroll, cine, and length measurement (mm when PixelSpacing is present).
 */
export function DicomViewport({
  studyUid,
  seriesUid,
  instances,
  instanceIndex,
  cineRate,
  frameTime,
  modality,
  onInstanceChange,
}: Props) {
  const reactId = useId().replace(/:/g, '')
  const renderingEngineId = `telerad-re-${reactId}`
  const viewportId = `telerad-vp-${reactId}`
  const toolGroupId = `telerad-tg-${reactId}`

  const elementRef = useRef<HTMLDivElement | null>(null)
  const engineRef = useRef<RenderingEngine | null>(null)
  const stackRef = useRef<StackEntry[]>([])
  const syncingRef = useRef(false)
  const playingRef = useRef(false)
  const invertRef = useRef(false)

  const [ready, setReady] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tool, setTool] = useState<Tool>('wwwc')
  const [invert, setInvert] = useState(false)
  const [wl, setWl] = useState<WlState>({ center: 0, width: 0 })
  const [stackIndex, setStackIndex] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [fps, setFps] = useState(() => resolveFps({ cineRate, frameTime, modality }))

  const stackKey = useMemo(
    () => instances.map((i) => `${i.sopUid}:${i.numberOfFrames}`).join('|'),
    [instances],
  )
  const stack = useMemo(
    () => buildStack(studyUid, seriesUid, instances),
    // stackKey captures SOP/frame identity; ignore new array refs with same content
    // eslint-disable-next-line react-hooks/exhaustive-deps -- instances covered by stackKey
    [studyUid, seriesUid, stackKey],
  )
  stackRef.current = stack

  const hasMultiFrame = instances.some((i) => (i.numberOfFrames || 1) > 1)
  const presets = presetsForModality(modality)
  const current = stack[stackIndex]
  const counterLabel = current
    ? hasMultiFrame
      ? `${stackIndex + 1} / ${stack.length}`
      : `${current.instanceIndex + 1} / ${instances.length}`
    : '—'

  const getViewport = useCallback((): Types.IStackViewport | null => {
    const engine = engineRef.current ?? getRenderingEngine(renderingEngineId)
    if (!engine) return null
    return engine.getViewport(viewportId) as Types.IStackViewport
  }, [renderingEngineId, viewportId])

  const setActiveTool = useCallback((next: Tool) => {
    const tg = ToolGroupManager.getToolGroup(toolGroupId)
    if (!tg) return
    const primary = ToolsEnums.MouseBindings.Primary
    const names = [
      WindowLevelTool.toolName,
      ZoomTool.toolName,
      PanTool.toolName,
      LengthTool.toolName,
    ]
    for (const name of names) {
      try {
        tg.setToolPassive(name)
      } catch {
        /* tool may not be added yet */
      }
    }
    const activeName =
      next === 'wwwc' ? WindowLevelTool.toolName
        : next === 'zoom' ? ZoomTool.toolName
          : next === 'pan' ? PanTool.toolName
            : LengthTool.toolName
    tg.setToolActive(activeName, {
      bindings: [{ mouseButton: primary }],
    })
    // Keep length visible after drawing when switching away
    if (next !== 'length') {
      try {
        tg.setToolEnabled(LengthTool.toolName)
      } catch {
        /* ignore */
      }
    }
  }, [toolGroupId])

  // Bootstrap engine + tool group once
  useEffect(() => {
    let cancelled = false
    let resizeObs: ResizeObserver | null = null

    async function setup() {
      const el = elementRef.current
      if (!el) return
      try {
        setLoading(true)
        setError(null)
        await ensureCornerstoneInit()
        if (cancelled) return

        // Destroy leftovers from StrictMode remount
        const existing = getRenderingEngine(renderingEngineId)
        if (existing) existing.destroy()
        const existingTg = ToolGroupManager.getToolGroup(toolGroupId)
        if (existingTg) ToolGroupManager.destroyToolGroup(toolGroupId)

        const engine = new RenderingEngine(renderingEngineId)
        engineRef.current = engine

        engine.enableElement({
          viewportId,
          element: el,
          type: Enums.ViewportType.STACK,
        })

        const tg = ToolGroupManager.createToolGroup(toolGroupId)!
        tg.addTool(WindowLevelTool.toolName)
        tg.addTool(PanTool.toolName)
        tg.addTool(ZoomTool.toolName)
        tg.addTool(StackScrollTool.toolName)
        tg.addTool(LengthTool.toolName)
        tg.addViewport(viewportId, renderingEngineId)

        tg.setToolActive(StackScrollTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Wheel }],
        })
        tg.setToolActive(WindowLevelTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
        })
        tg.setToolActive(PanTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Auxiliary }],
        })
        tg.setToolActive(ZoomTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Secondary }],
        })

        const onNewImage = (evt: Event) => {
          const detail = (evt as CustomEvent).detail as {
            imageIdIndex?: number
          }
          const idx = detail?.imageIdIndex
          if (idx == null || syncingRef.current) return
          const entry = stackRef.current[idx]
          if (!entry) return
          setStackIndex(idx)
          onInstanceChange(entry.instanceIndex)

          const vp = getViewport()
          if (vp) {
            const props = vp.getProperties()
            if (props.voiRange) {
              const { windowWidth, windowCenter } = utilities.windowLevel.toWindowLevel(
                props.voiRange.lower,
                props.voiRange.upper,
              )
              setWl({ center: windowCenter, width: windowWidth })
            }
          }
        }

        const onVoi = (evt: Event) => {
          const detail = (evt as CustomEvent).detail as {
            range?: { lower: number; upper: number }
          }
          if (!detail?.range) return
          const { windowWidth, windowCenter } = utilities.windowLevel.toWindowLevel(
            detail.range.lower,
            detail.range.upper,
          )
          setWl({ center: windowCenter, width: windowWidth })
        }

        el.addEventListener(Enums.Events.STACK_NEW_IMAGE, onNewImage)
        el.addEventListener(Enums.Events.VOI_MODIFIED, onVoi)

        resizeObs = new ResizeObserver(() => {
          engine.resize(true, false)
        })
        resizeObs.observe(el)

        if (!cancelled) setReady(true)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Viewer init failed')
          setLoading(false)
        }
      }
    }

    setup()

    return () => {
      cancelled = true
      resizeObs?.disconnect()
      try {
        ToolGroupManager.destroyToolGroup(toolGroupId)
      } catch {
        /* ignore */
      }
      try {
        engineRef.current?.destroy()
      } catch {
        /* ignore */
      }
      engineRef.current = null
      setReady(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- mount once per viewport ids
  }, [renderingEngineId, viewportId, toolGroupId])

  // Load / reload stack when series UIDs or instance list change
  useEffect(() => {
    if (!ready) return
    let cancelled = false

    async function loadStack() {
      const viewport = getViewport()
      if (!viewport || stack.length === 0) {
        setError('No images in series')
        setLoading(false)
        return
      }
      setLoading(true)
      setError(null)
      setPlaying(false)
      playingRef.current = false
      setFps(resolveFps({ cineRate, frameTime, modality }))

      try {
        const startIdx = firstStackIndexForInstance(stack, instanceIndex)
        syncingRef.current = true
        await viewport.setStack(stack.map((s) => s.imageId), startIdx)
        viewport.resetCamera()
        if (invertRef.current) {
          viewport.setProperties({ invert: true })
        }
        viewport.render()
        if (cancelled) return
        setStackIndex(startIdx)
        const props = viewport.getProperties()
        if (props.voiRange) {
          const { windowWidth, windowCenter } = utilities.windowLevel.toWindowLevel(
            props.voiRange.lower,
            props.voiRange.upper,
          )
          setWl({ center: windowCenter, width: windowWidth })
        }
        setLoading(false)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load DICOM stack')
          setLoading(false)
        }
      } finally {
        syncingRef.current = false
      }
    }

    loadStack()
    return () => { cancelled = true }
  }, [ready, studyUid, seriesUid, stack, cineRate, frameTime, modality, getViewport])
  // intentionally omit instanceIndex — jump handled below

  // Jump when parent selects a different instance (series click / external)
  useEffect(() => {
    if (!ready) return
    const entry = stack[stackIndex]
    if (entry && entry.instanceIndex === instanceIndex) return
    const target = firstStackIndexForInstance(stack, instanceIndex)
    const viewport = getViewport()
    if (!viewport || stack.length === 0) return
    syncingRef.current = true
    viewport.setImageIdIndex(target).finally(() => {
      syncingRef.current = false
      setStackIndex(target)
    })
  }, [instanceIndex, ready, stack, stackIndex, getViewport])

  useEffect(() => {
    if (!ready) return
    setActiveTool(tool)
  }, [tool, ready, setActiveTool])

  useEffect(() => {
    invertRef.current = invert
    const viewport = getViewport()
    if (!viewport) return
    viewport.setProperties({ invert })
    viewport.render()
  }, [invert, getViewport])

  // Cine playback across the stack
  useEffect(() => {
    playingRef.current = playing
    if (!playing || stack.length < 2) return
    const intervalMs = Math.max(16, Math.round(1000 / fps))
    const id = window.setInterval(() => {
      const viewport = getViewport()
      if (!viewport) return
      const cur = viewport.getCurrentImageIdIndex()
      const next = cur >= stack.length - 1 ? 0 : cur + 1
      viewport.setImageIdIndex(next)
    }, intervalMs)
    return () => window.clearInterval(id)
  }, [playing, fps, stack.length, getViewport])

  const stepFrame = useCallback((delta: number) => {
    const viewport = getViewport()
    if (!viewport || stack.length === 0) return
    const cur = viewport.getCurrentImageIdIndex()
    let next = cur + delta
    if (next < 0) next = stack.length - 1
    if (next >= stack.length) next = 0
    viewport.setImageIdIndex(next)
  }, [getViewport, stack.length])

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const tag = (e.target as HTMLElement | null)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (e.target as HTMLElement)?.isContentEditable) {
        return
      }
      if (e.key === 'ArrowDown' || e.key === 'PageDown' || e.key === 'ArrowRight') stepFrame(1)
      if (e.key === 'ArrowUp' || e.key === 'PageUp' || e.key === 'ArrowLeft') stepFrame(-1)
      if (e.key === ' ' && hasMultiFrame) {
        e.preventDefault()
        setPlaying((p) => !p)
      }
      if (e.key === 'i' || e.key === 'I') setInvert((v) => !v)
      if (e.key === 'r' || e.key === 'R') {
        const viewport = getViewport()
        if (!viewport) return
        viewport.resetCamera()
        viewport.resetProperties()
        viewport.render()
        setInvert(false)
        const props = viewport.getProperties()
        if (props.voiRange) {
          const { windowWidth, windowCenter } = utilities.windowLevel.toWindowLevel(
            props.voiRange.lower,
            props.voiRange.upper,
          )
          setWl({ center: windowCenter, width: windowWidth })
        }
      }
      if (e.key === 'l' || e.key === 'L') setTool('length')
      if (e.key === 'w' || e.key === 'W') setTool('wwwc')
      if (e.key === 'z' || e.key === 'Z') setTool('zoom')
      if (e.key === 'p' || e.key === 'P') setTool('pan')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [stepFrame, hasMultiFrame, getViewport])

  function resetView() {
    const viewport = getViewport()
    if (!viewport) return
    viewport.resetCamera()
    viewport.resetProperties()
    setInvert(false)
    viewport.render()
    const props = viewport.getProperties()
    if (props.voiRange) {
      const { windowWidth, windowCenter } = utilities.windowLevel.toWindowLevel(
        props.voiRange.lower,
        props.voiRange.upper,
      )
      setWl({ center: windowCenter, width: windowWidth })
    }
  }

  function applyPreset(next: WlState) {
    const viewport = getViewport()
    if (!viewport) return
    applyWl(viewport, next)
    setWl(next)
  }

  const activePreset = presets.find((p) => p.center === wl.center && p.width === wl.width)

  return (
    <div className="viewport">
      <div
        ref={elementRef}
        className="viewport-canvas cs-viewport"
        onContextMenu={(e) => e.preventDefault()}
      />
      {loading && <div className="viewport-overlay muted">Loading DICOM…</div>}
      {error && <div className="viewport-overlay error">{error}</div>}
      <div className="viewport-hud">
        <div>{wl.width > 0 ? `W: ${Math.round(wl.width)}  L: ${Math.round(wl.center)}` : 'W/L: DICOM'}</div>
        <div>{counterLabel}</div>
        {playing && <div>Cine {Math.round(fps)} fps</div>}
      </div>
      <div className="viewport-tools">
        <button className={`btn secondary ${tool === 'wwwc' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('wwwc')}>W/L</button>
        <button className={`btn secondary ${tool === 'zoom' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('zoom')}>Zoom</button>
        <button className={`btn secondary ${tool === 'pan' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('pan')}>Pan</button>
        <button className={`btn secondary ${tool === 'length' ? 'active-tool' : ''}`} type="button" onClick={() => setTool('length')}>Length</button>
        <button className={`btn secondary ${invert ? 'active-tool' : ''}`} type="button" onClick={() => setInvert((v) => !v)}>Invert</button>
        <button className="btn secondary" type="button" onClick={() => stepFrame(-1)}>Prev</button>
        <button className="btn secondary" type="button" onClick={() => stepFrame(1)}>Next</button>
        {hasMultiFrame && (
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
        <div className="wl-presets" role="group" aria-label="Window/level presets">
          {presets.map((p) => {
            const on = activePreset?.label === p.label
            return (
              <button
                key={p.label}
                type="button"
                className={`wl-preset ${on ? 'on' : ''}`}
                aria-pressed={on}
                onClick={() => applyPreset({ center: p.center, width: p.width })}
              >
                {p.label}
              </button>
            )
          })}
        </div>
        <span className="badge">{counterLabel}</span>
      </div>
    </div>
  )
}

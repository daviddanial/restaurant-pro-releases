import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  Enums,
  RenderingEngine,
  setVolumesForViewports,
  volumeLoader,
  cache,
  utilities,
  type Types,
} from '@cornerstonejs/core'
import {
  Enums as ToolsEnums,
  ToolGroupManager,
  WindowLevelTool,
  PanTool,
  ZoomTool,
  StackScrollTool,
  CrosshairsTool,
  TrackballRotateTool,
} from '@cornerstonejs/tools'
import { buildWadoUriImageId, ensureCornerstoneInit } from '../cornerstone/initCornerstone'
import {
  positionAlongNormal,
  sliceNormalFromOrientation,
} from '../spatialSync'
import { presetsForModality } from '../wlPresets'

export type MprInstance = {
  sopUid: string
  numberOfFrames: number
  imagePositionPatient?: number[] | null
  imageOrientationPatient?: number[] | null
}

export type FourthMode = 'mip' | 'vr'

type Props = {
  studyUid: string
  seriesUid: string
  instances: MprInstance[]
  modality?: string | null
  /** When true, CrosshairsTool links the three planes; 4th pane follows that center. */
  linkViews?: boolean
}

const PLANE_COLORS: Record<string, string> = {
  axial: '#f97316',
  sagittal: '#22c55e',
  coronal: '#38bdf8',
  fourth: '#a78bfa',
}

const DEFAULT_SLAB_MM = 10
const VR_PRESETS = ['CT-Bone', 'CT-Soft-Tissue', 'CT-Lung'] as const
const SLAB_OPTIONS_MM = [5, 10, 20] as const

/**
 * Build wadouri imageIds for single-frame instances, sorted along the stack
 * normal when ImagePositionPatient is present.
 */
export function buildVolumeImageIds(
  studyUid: string,
  seriesUid: string,
  instances: MprInstance[],
): string[] {
  const singles = instances.filter((i) => (i.numberOfFrames || 1) <= 1)
  let normal: [number, number, number] | null = null
  for (const inst of singles) {
    if (inst.imageOrientationPatient && inst.imageOrientationPatient.length >= 6) {
      normal = sliceNormalFromOrientation(inst.imageOrientationPatient)
      if (normal) break
    }
  }

  const ranked = singles.map((inst, order) => {
    let pos = order
    if (normal && inst.imagePositionPatient && inst.imagePositionPatient.length >= 3) {
      const p = positionAlongNormal(inst.imagePositionPatient, normal)
      if (p != null) pos = p
    }
    return { inst, pos, order }
  })
  ranked.sort((a, b) => a.pos - b.pos || a.order - b.order)
  return ranked.map(({ inst }) => buildWadoUriImageId(studyUid, seriesUid, inst.sopUid))
}

function cameraDistance(cam: Types.ICamera): number {
  const p = cam.position
  const f = cam.focalPoint
  if (!p || !f) return 400
  return Math.hypot(p[0] - f[0], p[1] - f[1], p[2] - f[2]) || 400
}

/** Move an orthographic/volume camera so its focal point matches `world`, keeping view direction. */
function aimCameraAt(
  viewport: Types.IVolumeViewport,
  world: Types.Point3,
) {
  const cam = viewport.getCamera()
  const normal = cam.viewPlaneNormal
  if (!normal || !cam.position) {
    viewport.setCamera({ ...cam, focalPoint: world })
    viewport.render()
    return
  }
  const dist = cameraDistance(cam)
  const position: Types.Point3 = [
    world[0] - normal[0] * dist,
    world[1] - normal[1] * dist,
    world[2] - normal[2] * dist,
  ]
  viewport.setCamera({ ...cam, focalPoint: world, position })
  viewport.render()
}

/**
 * RadiAnt-style 2×2 MPR: axial / sagittal / coronal + 4th pane (MIP or VR),
 * sharing one streaming volume. Crosshairs link the three planes; the 4th
 * follows that center (MIP slab / VR orbit target).
 */
export function MprViewer({
  studyUid,
  seriesUid,
  instances,
  modality,
  linkViews = true,
}: Props) {
  const reactId = useId().replace(/:/g, '')
  const renderingEngineId = `telerad-mpr-re-${reactId}`
  const planeToolGroupId = `telerad-mpr-planes-${reactId}`
  const fourthToolGroupId = `telerad-mpr-fourth-${reactId}`
  const volumeId = `cornerstoneStreamingImageVolume:mpr-${reactId}`

  const viewportIds = useMemo(() => ({
    axial: `mpr-axial-${reactId}`,
    sagittal: `mpr-sagittal-${reactId}`,
    coronal: `mpr-coronal-${reactId}`,
    fourth: `mpr-fourth-${reactId}`,
  }), [reactId])

  const axialRef = useRef<HTMLDivElement | null>(null)
  const sagittalRef = useRef<HTMLDivElement | null>(null)
  const coronalRef = useRef<HTMLDivElement | null>(null)
  const fourthRef = useRef<HTMLDivElement | null>(null)
  const engineRef = useRef<RenderingEngine | null>(null)
  const fourthModeRef = useRef<FourthMode>('mip')
  const linkViewsRef = useRef(linkViews)
  linkViewsRef.current = linkViews
  const slabMmRef = useRef(DEFAULT_SLAB_MM)
  const syncingFourthRef = useRef(false)

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [ready, setReady] = useState(false)
  const [volumeReady, setVolumeReady] = useState(false)
  const [wlLabel, setWlLabel] = useState<string | null>(null)
  const [fourthMode, setFourthMode] = useState<FourthMode>('mip')
  const [slabMm, setSlabMm] = useState(DEFAULT_SLAB_MM)
  const [vrPreset, setVrPreset] = useState<string>('CT-Bone')

  fourthModeRef.current = fourthMode
  slabMmRef.current = slabMm

  const imageIds = useMemo(
    () => buildVolumeImageIds(studyUid, seriesUid, instances),
    [studyUid, seriesUid, instances],
  )
  const imageKey = imageIds.join('|')
  const presets = presetsForModality(modality)
  const showVrPresets = (modality || '').toUpperCase() === 'CT' || fourthMode === 'vr'

  const planeIds = useMemo(
    () => [viewportIds.axial, viewportIds.sagittal, viewportIds.coronal],
    [viewportIds],
  )

  const syncFourthToPlanes = useCallback(() => {
    if (!linkViewsRef.current || syncingFourthRef.current) return
    const engine = engineRef.current
    if (!engine) return
    const axial = engine.getViewport(viewportIds.axial) as Types.IVolumeViewport | undefined
    const fourth = engine.getViewport(viewportIds.fourth) as Types.IVolumeViewport | undefined
    if (!axial || !fourth) return
    const world = axial.getCamera().focalPoint
    if (!world) return
    syncingFourthRef.current = true
    try {
      aimCameraAt(fourth, world as Types.Point3)
    } finally {
      syncingFourthRef.current = false
    }
  }, [viewportIds])

  // Mount rendering engine + plane tool group (3 planes). Fourth configured after volume load / mode change.
  useEffect(() => {
    let cancelled = false
    let resizeObs: ResizeObserver | null = null
    let onCamera: EventListener | null = null

    async function setup() {
      try {
        await ensureCornerstoneInit()
        if (cancelled) return
        const axialEl = axialRef.current
        const sagEl = sagittalRef.current
        const corEl = coronalRef.current
        const fourthEl = fourthRef.current
        if (!axialEl || !sagEl || !corEl || !fourthEl) return

        const engine = new RenderingEngine(renderingEngineId)
        engineRef.current = engine

        engine.setViewports([
          {
            viewportId: viewportIds.axial,
            type: Enums.ViewportType.ORTHOGRAPHIC,
            element: axialEl,
            defaultOptions: {
              orientation: Enums.OrientationAxis.AXIAL,
              background: [0.05, 0.07, 0.1] as Types.Point3,
            },
          },
          {
            viewportId: viewportIds.sagittal,
            type: Enums.ViewportType.ORTHOGRAPHIC,
            element: sagEl,
            defaultOptions: {
              orientation: Enums.OrientationAxis.SAGITTAL,
              background: [0.05, 0.07, 0.1] as Types.Point3,
            },
          },
          {
            viewportId: viewportIds.coronal,
            type: Enums.ViewportType.ORTHOGRAPHIC,
            element: corEl,
            defaultOptions: {
              orientation: Enums.OrientationAxis.CORONAL,
              background: [0.05, 0.07, 0.1] as Types.Point3,
            },
          },
          {
            // Start as MIP orthographic; VR swaps this element to VOLUME_3D.
            viewportId: viewportIds.fourth,
            type: Enums.ViewportType.ORTHOGRAPHIC,
            element: fourthEl,
            defaultOptions: {
              orientation: Enums.OrientationAxis.AXIAL,
              background: [0.05, 0.07, 0.1] as Types.Point3,
            },
          },
        ])

        const planeTg = ToolGroupManager.createToolGroup(planeToolGroupId)
        if (!planeTg) throw new Error('Failed to create MPR plane tool group')
        planeTg.addTool(WindowLevelTool.toolName)
        planeTg.addTool(PanTool.toolName)
        planeTg.addTool(ZoomTool.toolName)
        planeTg.addTool(StackScrollTool.toolName)
        planeTg.addTool(CrosshairsTool.toolName, {
          getReferenceLineColor: (viewportId: string) => {
            if (viewportId.includes('axial')) return PLANE_COLORS.axial
            if (viewportId.includes('sagittal')) return PLANE_COLORS.sagittal
            return PLANE_COLORS.coronal
          },
          getReferenceLineControllable: () => true,
          getReferenceLineDraggableRotatable: () => true,
          getReferenceLineSlabThicknessControlsOn: () => false,
        })
        for (const id of planeIds) {
          planeTg.addViewport(id, renderingEngineId)
        }
        planeTg.setToolActive(StackScrollTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Wheel }],
        })
        planeTg.setToolActive(WindowLevelTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
        })
        planeTg.setToolActive(PanTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Auxiliary }],
        })
        planeTg.setToolActive(ZoomTool.toolName, {
          bindings: [{ mouseButton: ToolsEnums.MouseBindings.Secondary }],
        })

        const fourthTg = ToolGroupManager.createToolGroup(fourthToolGroupId)
        if (!fourthTg) throw new Error('Failed to create MPR fourth tool group')
        fourthTg.addTool(WindowLevelTool.toolName)
        fourthTg.addTool(PanTool.toolName)
        fourthTg.addTool(ZoomTool.toolName)
        fourthTg.addTool(StackScrollTool.toolName)
        fourthTg.addTool(TrackballRotateTool.toolName)
        fourthTg.addViewport(viewportIds.fourth, renderingEngineId)

        onCamera = () => {
          if (!linkViewsRef.current) return
          syncFourthToPlanes()
        }
        axialEl.addEventListener(Enums.Events.CAMERA_MODIFIED, onCamera)
        sagEl.addEventListener(Enums.Events.CAMERA_MODIFIED, onCamera)
        corEl.addEventListener(Enums.Events.CAMERA_MODIFIED, onCamera)

        resizeObs = new ResizeObserver(() => {
          engine.resize(true, false)
        })
        resizeObs.observe(axialEl)
        resizeObs.observe(sagEl)
        resizeObs.observe(corEl)
        resizeObs.observe(fourthEl)

        if (!cancelled) setReady(true)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'MPR init failed')
          setLoading(false)
        }
      }
    }

    setup()
    return () => {
      cancelled = true
      resizeObs?.disconnect()
      const axialEl = axialRef.current
      const sagEl = sagittalRef.current
      const corEl = coronalRef.current
      if (onCamera && axialEl) axialEl.removeEventListener(Enums.Events.CAMERA_MODIFIED, onCamera)
      if (onCamera && sagEl) sagEl.removeEventListener(Enums.Events.CAMERA_MODIFIED, onCamera)
      if (onCamera && corEl) corEl.removeEventListener(Enums.Events.CAMERA_MODIFIED, onCamera)
      try { ToolGroupManager.destroyToolGroup(planeToolGroupId) } catch { /* ignore */ }
      try { ToolGroupManager.destroyToolGroup(fourthToolGroupId) } catch { /* ignore */ }
      try { engineRef.current?.destroy() } catch { /* ignore */ }
      engineRef.current = null
      try { cache.removeVolumeLoadObject(volumeId) } catch { /* ignore */ }
      setReady(false)
      setVolumeReady(false)
    }
  }, [renderingEngineId, planeToolGroupId, fourthToolGroupId, volumeId, viewportIds, planeIds, syncFourthToPlanes])

  const applyFourthMode = useCallback(async (mode: FourthMode, slab: number, preset: string) => {
    const engine = engineRef.current
    const fourthEl = fourthRef.current
    if (!engine || !fourthEl) return

    const fourthTg = ToolGroupManager.getToolGroup(fourthToolGroupId)
    try {
      fourthTg?.removeViewports(renderingEngineId, viewportIds.fourth)
    } catch { /* ignore */ }

    try {
      engine.disableElement(viewportIds.fourth)
    } catch { /* ignore */ }

    if (mode === 'mip') {
      engine.enableElement({
        viewportId: viewportIds.fourth,
        type: Enums.ViewportType.ORTHOGRAPHIC,
        element: fourthEl,
        defaultOptions: {
          orientation: Enums.OrientationAxis.AXIAL,
          background: [0.05, 0.07, 0.1] as Types.Point3,
        },
      })
      await setVolumesForViewports(
        engine,
        [{
          volumeId,
          blendMode: Enums.BlendModes.MAXIMUM_INTENSITY_BLEND,
          slabThickness: slab,
        }],
        [viewportIds.fourth],
      )
      const vp = engine.getViewport(viewportIds.fourth) as Types.IVolumeViewport & {
        setBlendMode?: (mode: typeof Enums.BlendModes.MAXIMUM_INTENSITY_BLEND, uids?: string[], immediate?: boolean) => void
        setSlabThickness?: (thickness: number, uids?: string[]) => void
      }
      vp.setBlendMode?.(Enums.BlendModes.MAXIMUM_INTENSITY_BLEND)
      vp.setSlabThickness?.(slab)
      vp.resetCamera()
      fourthTg?.addViewport(viewportIds.fourth, renderingEngineId)
      fourthTg?.setToolActive(StackScrollTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Wheel }],
      })
      fourthTg?.setToolActive(WindowLevelTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
      })
      fourthTg?.setToolActive(PanTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Auxiliary }],
      })
      fourthTg?.setToolActive(ZoomTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Secondary }],
      })
      try { fourthTg?.setToolDisabled(TrackballRotateTool.toolName) } catch { /* ignore */ }
      vp.render()
    } else {
      engine.enableElement({
        viewportId: viewportIds.fourth,
        type: Enums.ViewportType.VOLUME_3D,
        element: fourthEl,
        defaultOptions: {
          background: [0.05, 0.07, 0.1] as Types.Point3,
        },
      })
      await setVolumesForViewports(engine, [{ volumeId }], [viewportIds.fourth])
      const vp = engine.getViewport(viewportIds.fourth) as Types.IVolumeViewport
      vp.setProperties({ preset })
      vp.resetCamera()
      fourthTg?.addViewport(viewportIds.fourth, renderingEngineId)
      fourthTg?.setToolActive(TrackballRotateTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
      })
      fourthTg?.setToolActive(ZoomTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Secondary }],
      })
      fourthTg?.setToolActive(PanTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Auxiliary }],
      })
      try { fourthTg?.setToolDisabled(StackScrollTool.toolName) } catch { /* ignore */ }
      try { fourthTg?.setToolDisabled(WindowLevelTool.toolName) } catch { /* ignore */ }
      vp.render()
    }

    if (linkViewsRef.current) syncFourthToPlanes()
  }, [fourthToolGroupId, renderingEngineId, viewportIds, volumeId, syncFourthToPlanes])

  // Load volume onto the three planes, then configure the 4th pane.
  useEffect(() => {
    if (!ready) return
    let cancelled = false

    async function loadVolume() {
      const engine = engineRef.current
      if (!engine) return
      if (imageIds.length < 2) {
        setError('Not enough slices to build an MPR volume')
        setLoading(false)
        return
      }

      setLoading(true)
      setError(null)
      setWlLabel(null)
      setVolumeReady(false)

      try {
        try { cache.removeVolumeLoadObject(volumeId) } catch { /* ignore */ }

        const volume = await volumeLoader.createAndCacheVolume(volumeId, { imageIds })
        if ('load' in volume && typeof volume.load === 'function') {
          ;(volume as { load: () => void }).load()
        }

        await setVolumesForViewports(engine, [{ volumeId }], planeIds)
        for (const id of planeIds) {
          const vp = engine.getViewport(id) as Types.IVolumeViewport
          vp.resetCamera()
          vp.render()
        }

        if (cancelled) return
        setVolumeReady(true)
        setLoading(false)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load MPR volume')
          setLoading(false)
        }
      }
    }

    loadVolume()
    return () => { cancelled = true }
  }, [ready, imageKey, imageIds, volumeId, planeIds])

  // Live switch MIP ↔ VR / slab / VR preset after volume is up
  useEffect(() => {
    if (!volumeReady) return
    let cancelled = false
    ;(async () => {
      try {
        await applyFourthMode(fourthMode, slabMm, vrPreset)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to update 4th pane')
        }
      }
    })()
    return () => { cancelled = true }
  }, [volumeReady, fourthMode, slabMm, vrPreset, applyFourthMode])

  // Toggle crosshair linking on the three planes
  useEffect(() => {
    if (!ready) return
    const tg = ToolGroupManager.getToolGroup(planeToolGroupId)
    if (!tg) return
    if (linkViews) {
      tg.setToolActive(CrosshairsTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
      })
      tg.setToolPassive(WindowLevelTool.toolName)
      syncFourthToPlanes()
    } else {
      tg.setToolDisabled(CrosshairsTool.toolName)
      tg.setToolActive(WindowLevelTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
      })
    }
  }, [linkViews, ready, planeToolGroupId, syncFourthToPlanes])

  const applyWlPreset = (center: number, width: number, label: string) => {
    const engine = engineRef.current
    if (!engine || !(width > 0)) return
    const voiRange = utilities.windowLevel.toLowHighRange(width, center)
    for (const id of planeIds) {
      const vp = engine.getViewport(id) as Types.IVolumeViewport | undefined
      if (!vp) continue
      vp.setProperties({ voiRange })
      vp.render()
    }
    if (fourthMode === 'mip') {
      const fourth = engine.getViewport(viewportIds.fourth) as Types.IVolumeViewport | undefined
      if (fourth) {
        fourth.setProperties({ voiRange })
        fourth.render()
      }
    }
    setWlLabel(label)
  }

  const fourthLabel = fourthMode === 'mip' ? `MIP ${slabMm} mm` : `VR · ${vrPreset}`

  return (
    <div className="mpr-root">
      <div className="mpr-toolbar">
        <span className="mpr-toolbar-title">MPR</span>
        <span className="muted">{imageIds.length} slices</span>

        <div className="mpr-presets" role="group" aria-label="4th pane mode">
          <button
            type="button"
            className={fourthMode === 'mip' ? 'on' : undefined}
            onClick={() => setFourthMode('mip')}
          >
            MIP
          </button>
          <button
            type="button"
            className={fourthMode === 'vr' ? 'on' : undefined}
            onClick={() => setFourthMode('vr')}
          >
            VR
          </button>
        </div>

        {fourthMode === 'mip' && (
          <label className="mpr-slab">
            Slab
            <select
              value={slabMm}
              onChange={(e) => setSlabMm(Number(e.target.value))}
            >
              {SLAB_OPTIONS_MM.map((mm) => (
                <option key={mm} value={mm}>{mm} mm</option>
              ))}
            </select>
          </label>
        )}

        {fourthMode === 'vr' && showVrPresets && (
          <div className="mpr-presets" role="group" aria-label="VR presets">
            {VR_PRESETS.map((name) => (
              <button
                key={name}
                type="button"
                className={vrPreset === name ? 'on' : undefined}
                onClick={() => setVrPreset(name)}
              >
                {name.replace('CT-', '')}
              </button>
            ))}
          </div>
        )}

        {presets.length > 0 && fourthMode === 'mip' && (
          <div className="mpr-presets" role="group" aria-label="W/L presets">
            {presets.map((p) => (
              <button
                key={p.label}
                type="button"
                className={wlLabel === p.label ? 'on' : undefined}
                onClick={() => applyWlPreset(p.center, p.width, p.label)}
              >
                {p.label}
              </button>
            ))}
          </div>
        )}

        {loading && <span className="muted">Loading volume…</span>}
        {error && <span className="error">{error}</span>}
      </div>

      <div className="viewer-panes layout-mpr">
        <section className="viewer-pane mpr-axial">
          <header className="viewer-pane-label">
            <span className="viewer-pane-tag" style={{ background: PLANE_COLORS.axial }}>Axial</span>
          </header>
          <div className="viewport mpr-canvas" ref={axialRef} onContextMenu={(e) => e.preventDefault()} />
        </section>
        <section className="viewer-pane mpr-sagittal">
          <header className="viewer-pane-label">
            <span className="viewer-pane-tag" style={{ background: PLANE_COLORS.sagittal }}>Sagittal</span>
          </header>
          <div className="viewport mpr-canvas" ref={sagittalRef} onContextMenu={(e) => e.preventDefault()} />
        </section>
        <section className="viewer-pane mpr-coronal">
          <header className="viewer-pane-label">
            <span className="viewer-pane-tag" style={{ background: PLANE_COLORS.coronal }}>Coronal</span>
          </header>
          <div className="viewport mpr-canvas" ref={coronalRef} onContextMenu={(e) => e.preventDefault()} />
        </section>
        <section className="viewer-pane mpr-fourth">
          <header className="viewer-pane-label">
            <span className="viewer-pane-tag" style={{ background: PLANE_COLORS.fourth }}>{fourthLabel}</span>
          </header>
          <div className="viewport mpr-canvas" ref={fourthRef} onContextMenu={(e) => e.preventDefault()} />
        </section>
      </div>
    </div>
  )
}

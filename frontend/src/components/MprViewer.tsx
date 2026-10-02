import { useEffect, useId, useMemo, useRef, useState } from 'react'
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

type Props = {
  studyUid: string
  seriesUid: string
  instances: MprInstance[]
  modality?: string | null
  /** When true, CrosshairsTool links the three planes. */
  linkViews?: boolean
}

const PLANE_COLORS: Record<string, string> = {
  axial: '#f97316',
  sagittal: '#22c55e',
  coronal: '#38bdf8',
}

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

/**
 * Three linked orthographic volume viewports (axial / sagittal / coronal)
 * sharing one streaming image volume, with optional crosshair linking.
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
  const toolGroupId = `telerad-mpr-tg-${reactId}`
  const volumeId = `cornerstoneStreamingImageVolume:mpr-${reactId}`

  const viewportIds = useMemo(() => ({
    axial: `mpr-axial-${reactId}`,
    sagittal: `mpr-sagittal-${reactId}`,
    coronal: `mpr-coronal-${reactId}`,
  }), [reactId])

  const axialRef = useRef<HTMLDivElement | null>(null)
  const sagittalRef = useRef<HTMLDivElement | null>(null)
  const coronalRef = useRef<HTMLDivElement | null>(null)
  const engineRef = useRef<RenderingEngine | null>(null)

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [ready, setReady] = useState(false)
  const [wlLabel, setWlLabel] = useState<string | null>(null)

  const imageIds = useMemo(
    () => buildVolumeImageIds(studyUid, seriesUid, instances),
    [studyUid, seriesUid, instances],
  )
  const imageKey = imageIds.join('|')
  const presets = presetsForModality(modality)

  // Mount rendering engine + tool group once
  useEffect(() => {
    let cancelled = false
    let resizeObs: ResizeObserver | null = null

    async function setup() {
      try {
        await ensureCornerstoneInit()
        if (cancelled) return
        const axialEl = axialRef.current
        const sagEl = sagittalRef.current
        const corEl = coronalRef.current
        if (!axialEl || !sagEl || !corEl) return

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
        ])

        const tg = ToolGroupManager.createToolGroup(toolGroupId)
        if (!tg) throw new Error('Failed to create MPR tool group')

        tg.addTool(WindowLevelTool.toolName)
        tg.addTool(PanTool.toolName)
        tg.addTool(ZoomTool.toolName)
        tg.addTool(StackScrollTool.toolName)
        tg.addTool(CrosshairsTool.toolName, {
          getReferenceLineColor: (viewportId: string) => {
            if (viewportId.includes('axial')) return PLANE_COLORS.axial
            if (viewportId.includes('sagittal')) return PLANE_COLORS.sagittal
            return PLANE_COLORS.coronal
          },
          getReferenceLineControllable: () => true,
          getReferenceLineDraggableRotatable: () => true,
          getReferenceLineSlabThicknessControlsOn: () => false,
        })

        for (const id of Object.values(viewportIds)) {
          tg.addViewport(id, renderingEngineId)
        }

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

        resizeObs = new ResizeObserver(() => {
          engine.resize(true, false)
        })
        resizeObs.observe(axialEl)
        resizeObs.observe(sagEl)
        resizeObs.observe(corEl)

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
      try {
        ToolGroupManager.destroyToolGroup(toolGroupId)
      } catch { /* ignore */ }
      try {
        engineRef.current?.destroy()
      } catch { /* ignore */ }
      engineRef.current = null
      try {
        cache.removeVolumeLoadObject(volumeId)
      } catch { /* ignore */ }
      setReady(false)
    }
  }, [renderingEngineId, toolGroupId, volumeId, viewportIds])

  // Load / reload volume when series changes
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

      try {
        // Drop a previous volume with the same id before recreating.
        try {
          cache.removeVolumeLoadObject(volumeId)
        } catch { /* ignore */ }

        const volume = await volumeLoader.createAndCacheVolume(volumeId, { imageIds })
        if ('load' in volume && typeof volume.load === 'function') {
          ;(volume as { load: () => void }).load()
        }

        const ids = [viewportIds.axial, viewportIds.sagittal, viewportIds.coronal]
        await setVolumesForViewports(
          engine,
          [{ volumeId }],
          ids,
        )

        for (const id of ids) {
          const vp = engine.getViewport(id) as Types.IVolumeViewport
          vp.resetCamera()
          vp.render()
        }

        if (!cancelled) setLoading(false)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Failed to load MPR volume')
          setLoading(false)
        }
      }
    }

    loadVolume()
    return () => { cancelled = true }
  }, [ready, imageKey, imageIds, volumeId, viewportIds])

  // Toggle crosshair linking
  useEffect(() => {
    if (!ready) return
    const tg = ToolGroupManager.getToolGroup(toolGroupId)
    if (!tg) return
    if (linkViews) {
      tg.setToolActive(CrosshairsTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
      })
      // Keep W/L on primary when link is off; when link is on, crosshairs own primary —
      // shift+drag still does W/L via passive, but set W/L as passive and use middle for pan.
      tg.setToolPassive(WindowLevelTool.toolName)
    } else {
      tg.setToolDisabled(CrosshairsTool.toolName)
      tg.setToolActive(WindowLevelTool.toolName, {
        bindings: [{ mouseButton: ToolsEnums.MouseBindings.Primary }],
      })
    }
  }, [linkViews, ready, toolGroupId])

  const applyPreset = (center: number, width: number, label: string) => {
    const engine = engineRef.current
    if (!engine || !(width > 0)) return
    const voiRange = utilities.windowLevel.toLowHighRange(width, center)
    for (const id of Object.values(viewportIds)) {
      const vp = engine.getViewport(id) as Types.IVolumeViewport | undefined
      if (!vp) continue
      vp.setProperties({ voiRange })
      vp.render()
    }
    setWlLabel(label)
  }

  return (
    <div className="mpr-root">
      <div className="mpr-toolbar">
        <span className="mpr-toolbar-title">MPR</span>
        <span className="muted">{imageIds.length} slices</span>
        {presets.length > 0 && (
          <div className="mpr-presets" role="group" aria-label="W/L presets">
            {presets.map((p) => (
              <button
                key={p.label}
                type="button"
                className={wlLabel === p.label ? 'on' : undefined}
                onClick={() => applyPreset(p.center, p.width, p.label)}
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
      </div>
    </div>
  )
}

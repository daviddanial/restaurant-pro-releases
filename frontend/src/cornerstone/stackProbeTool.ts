import { utilities as csUtils, type Types } from '@cornerstonejs/core'
import { ProbeTool, utilities as toolUtilities } from '@cornerstonejs/tools'

type ProbeAnnotation = {
  invalidated?: boolean
  metadata: { referencedImageId?: string }
  data: {
    handles: { points: Types.Point3[] }
    cachedStats: Record<string, { index?: Types.Point3; value?: number | null; Modality?: string; modalityUnit?: string }>
  }
}

/**
 * Density probe for stack viewports ("HU" on CT).
 *
 * Cornerstone's ProbeTool (5.6) reads the pixel with the slice number as depth index into the
 * displayed 2D image, so on every slice except the first it finds no value and shows no text.
 * Here the value is read from the slice on screen (depth 0), already rescaled to HU.
 */
export class StackProbeTool extends ProbeTool {
  static toolName = 'StackProbe'

  constructor(toolProps = {}, defaultToolProps?: Record<string, unknown>) {
    super(toolProps, defaultToolProps as never)
    // Value and unit only; the default also prints the pixel index.
    this.configuration.getTextLines = (data: ProbeAnnotation['data'], targetId: string) => {
      const s = data.cachedStats[targetId]
      if (s?.value == null) return undefined
      return [`${csUtils.roundNumber(s.value)} ${s.modalityUnit ?? ''}`.trim()]
    }
  }

  _calculateCachedStats(annotation: ProbeAnnotation, _renderingEngine: unknown, enabledElement: { viewport: Types.IStackViewport }) {
    const { viewport } = enabledElement
    const { data } = annotation
    const image = viewport.getImageData?.()
    if (!image?.imageData) return data.cachedStats
    const ijk = csUtils.transformWorldToIndex(image.imageData, data.handles.points[0])
    const [i, j] = [Math.round(ijk[0]), Math.round(ijk[1])]
    const [w, h] = image.dimensions
    const inside = i >= 0 && j >= 0 && i < w && j < h
    const modality = (image.metadata as { Modality?: string })?.Modality
    const value = inside ? (image.voxelManager?.getAtIJKPoint([i, j, 0]) as number | undefined) : undefined
    const unit = toolUtilities.getPixelValueUnits(modality ?? '', annotation.metadata.referencedImageId ?? '', {
      isPreScaled: true,
      isSuvScaled: false,
    })
    for (const targetId of Object.keys(data.cachedStats)) {
      data.cachedStats[targetId] = {
        index: [i, j, 0] as Types.Point3,
        value: value ?? null,
        Modality: modality,
        modalityUnit: unit,
      }
    }
    annotation.invalidated = false
    return data.cachedStats
  }
}

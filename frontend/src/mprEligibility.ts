import type { InstanceDto, SeriesDto } from './types'

/** Minimum single-frame slices required before we offer MPR. */
export const MPR_MIN_SLICES = 8

const VOLUME_MODALITIES = new Set(['CT', 'MR', 'PT', 'NM'])

/**
 * True when a series looks like a reconstructible volume (enough single-frame
 * slices, volumetric modality). Plane tags improve quality but are not required
 * up front — the volume loader reads them from DICOM / WADO metadata.
 */
export function seriesSupportsMpr(
  series: SeriesDto | null | undefined,
  studyModality?: string | null,
): boolean {
  if (!series) return false
  const modality = (series.modality || studyModality || '').toUpperCase()
  if (!VOLUME_MODALITIES.has(modality)) return false

  const singleFrame = series.instances.filter((i) => (i.numberOfFrames || 1) <= 1)
  if (singleFrame.length < MPR_MIN_SLICES) return false

  // Reject series that are mostly multi-frame cine
  const multiFrame = series.instances.filter((i) => (i.numberOfFrames || 1) > 1)
  if (multiFrame.length > 0 && multiFrame.length >= singleFrame.length) return false

  return true
}

/** Prefer a series with plane tags and the most slices when auto-picking for MPR. */
export function pickDefaultMprSeriesIdx(
  seriesList: SeriesDto[],
  studyModality?: string | null,
  preferredIdx?: number,
): number {
  if (preferredIdx != null && seriesSupportsMpr(seriesList[preferredIdx], studyModality)) {
    return preferredIdx
  }
  let best = -1
  let bestScore = -1
  seriesList.forEach((s, idx) => {
    if (!seriesSupportsMpr(s, studyModality)) return
    const single = s.instances.filter((i) => (i.numberOfFrames || 1) <= 1)
    const withPlane = single.filter((i) => hasPlane(i)).length
    const score = single.length * 10 + withPlane
    if (score > bestScore) {
      bestScore = score
      best = idx
    }
  })
  return best
}

function hasPlane(i: InstanceDto): boolean {
  return Boolean(
    (i.imagePositionPatient && i.imagePositionPatient.length >= 3)
    || (i.imageOrientationPatient && i.imageOrientationPatient.length >= 6),
  )
}

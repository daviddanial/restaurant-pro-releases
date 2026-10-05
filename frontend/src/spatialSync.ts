/**
 * Spatial sync scroll helpers (FR-VW-010).
 * Linked panes prefer ImagePositionPatient along the stack normal; fraction is the fallback.
 */

export type PlaneInstance = {
  numberOfFrames: number
  imagePositionPatient?: number[] | null
  imageOrientationPatient?: number[] | null
  /** Optional DICOM SliceLocation when IPP is missing. */
  sliceLocation?: number | null
}

/** Unit normal = row cosine × column cosine from ImageOrientationPatient. */
export function sliceNormalFromOrientation(iop: number[]): [number, number, number] | null {
  if (iop.length < 6) return null
  const [r0, r1, r2, c0, c1, c2] = iop
  const n0 = r1 * c2 - r2 * c1
  const n1 = r2 * c0 - r0 * c2
  const n2 = r0 * c1 - r1 * c0
  const len = Math.hypot(n0, n1, n2)
  if (!(len > 1e-10)) return null
  return [n0 / len, n1 / len, n2 / len]
}

export function positionAlongNormal(
  ipp: number[],
  normal: [number, number, number],
): number | null {
  if (ipp.length < 3) return null
  return ipp[0] * normal[0] + ipp[1] * normal[1] + ipp[2] * normal[2]
}

/**
 * One position per stack entry (same layout as DicomViewport.buildStack).
 * Uses the first valid IOP in the series as the stack normal.
 */
export function buildStackPositions(instances: PlaneInstance[]): (number | null)[] {
  let normal: [number, number, number] | null = null
  for (const inst of instances) {
    const iop = inst.imageOrientationPatient
    if (iop && iop.length >= 6) {
      normal = sliceNormalFromOrientation(iop)
      if (normal) break
    }
  }

  const positions: (number | null)[] = []
  for (const inst of instances) {
    const frames = Math.max(1, inst.numberOfFrames || 1)
    let pos: number | null = null
    if (normal && inst.imagePositionPatient && inst.imagePositionPatient.length >= 3) {
      pos = positionAlongNormal(inst.imagePositionPatient, normal)
    } else if (inst.sliceLocation != null && Number.isFinite(inst.sliceLocation)) {
      pos = inst.sliceLocation
    }
    for (let f = 0; f < frames; f++) {
      positions.push(pos)
    }
  }
  return positions
}

/** Index of the slice whose position is nearest to {@code targetMm}, or null if none known. */
export function nearestIndexByPosition(
  positions: (number | null)[],
  targetMm: number,
): number | null {
  let best = -1
  let bestDist = Infinity
  for (let i = 0; i < positions.length; i++) {
    const p = positions[i]
    if (p == null || !Number.isFinite(p)) continue
    const d = Math.abs(p - targetMm)
    if (d < bestDist) {
      bestDist = d
      best = i
    }
  }
  return best >= 0 ? best : null
}

export function fractionToIndex(fraction: number, length: number): number {
  if (length <= 1) return 0
  const clamped = Math.min(1, Math.max(0, fraction))
  return Math.round(clamped * (length - 1))
}

/**
 * Prefer nearest anatomical position; fall back to proportional index when plane data is missing.
 */
export function syncTargetIndex(opts: {
  sourceFraction: number
  sourcePositionMm: number | null
  targetPositions: (number | null)[]
}): number {
  const total = opts.targetPositions.length
  if (total === 0) return 0
  if (opts.sourcePositionMm != null && Number.isFinite(opts.sourcePositionMm)) {
    const idx = nearestIndexByPosition(opts.targetPositions, opts.sourcePositionMm)
    if (idx != null) return idx
  }
  return fractionToIndex(opts.sourceFraction, total)
}

export function hasSpatialPositions(positions: (number | null)[]): boolean {
  return positions.some((p) => p != null && Number.isFinite(p))
}

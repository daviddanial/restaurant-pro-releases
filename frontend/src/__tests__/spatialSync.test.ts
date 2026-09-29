import { describe, it, expect } from 'vitest'
import {
  buildStackPositions,
  fractionToIndex,
  hasSpatialPositions,
  nearestIndexByPosition,
  positionAlongNormal,
  sliceNormalFromOrientation,
  syncTargetIndex,
} from '../spatialSync'

describe('spatialSync', () => {
  it('computes a unit normal from row × column cosines', () => {
    // Axial: row = +X, col = +Y → normal ≈ +Z
    const n = sliceNormalFromOrientation([1, 0, 0, 0, 1, 0])
    expect(n).not.toBeNull()
    expect(n![0]).toBeCloseTo(0)
    expect(n![1]).toBeCloseTo(0)
    expect(n![2]).toBeCloseTo(1)
  })

  it('projects ImagePositionPatient onto the stack normal', () => {
    const n: [number, number, number] = [0, 0, 1]
    expect(positionAlongNormal([10, 20, -30], n)).toBeCloseTo(-30)
  })

  it('builds one position per stack entry including multi-frame SOPs', () => {
    const positions = buildStackPositions([
      { numberOfFrames: 1, imagePositionPatient: [0, 0, 0], imageOrientationPatient: [1, 0, 0, 0, 1, 0] },
      { numberOfFrames: 2, imagePositionPatient: [0, 0, 5], imageOrientationPatient: [1, 0, 0, 0, 1, 0] },
      { numberOfFrames: 1, imagePositionPatient: [0, 0, 10], imageOrientationPatient: [1, 0, 0, 0, 1, 0] },
    ])
    expect(positions).toEqual([0, 5, 5, 10])
  })

  it('falls back to SliceLocation when IPP is absent', () => {
    const positions = buildStackPositions([
      { numberOfFrames: 1, sliceLocation: 12.5 },
      { numberOfFrames: 1, sliceLocation: 15 },
    ])
    expect(positions).toEqual([12.5, 15])
  })

  it('finds the nearest slice by position', () => {
    expect(nearestIndexByPosition([0, 5, 10, 15], 11)).toBe(2)
    expect(nearestIndexByPosition([0, null, 10], 9)).toBe(2)
    expect(nearestIndexByPosition([null, null], 1)).toBeNull()
  })

  it('syncs by position when both stacks have plane data', () => {
    // Source at z=10 of a fine stack; target is coarser → nearest is index 1 (z=12)
    const idx = syncTargetIndex({
      sourceFraction: 0.5,
      sourcePositionMm: 10,
      targetPositions: [0, 12, 24],
    })
    expect(idx).toBe(1)
  })

  it('falls back to fraction when the target has no positions', () => {
    expect(syncTargetIndex({
      sourceFraction: 0.5,
      sourcePositionMm: 10,
      targetPositions: [null, null, null, null, null],
    })).toBe(2)
    expect(fractionToIndex(0, 10)).toBe(0)
    expect(fractionToIndex(1, 10)).toBe(9)
  })

  it('detects whether any spatial positions exist', () => {
    expect(hasSpatialPositions([null, 1, null])).toBe(true)
    expect(hasSpatialPositions([null, null])).toBe(false)
  })
})

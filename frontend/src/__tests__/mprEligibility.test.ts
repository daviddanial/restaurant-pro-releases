import { describe, it, expect } from 'vitest'
import { pickDefaultMprSeriesIdx, seriesSupportsMpr, MPR_MIN_SLICES } from '../mprEligibility'
import type { SeriesDto } from '../types'

function series(partial: Partial<SeriesDto> & { n?: number; multi?: boolean }): SeriesDto {
  const n = partial.n ?? 20
  const multi = partial.multi ?? false
  return {
    id: 's1',
    seriesInstanceUid: '1.2.3',
    modality: partial.modality ?? 'CT',
    seriesNumber: 1,
    seriesDescription: 'Axial',
    instanceCount: n,
    instances: Array.from({ length: n }, (_, i) => ({
      id: `i${i}`,
      sopInstanceUid: `sop.${i}`,
      instanceNumber: i + 1,
      rows: 512,
      columns: 512,
      numberOfFrames: multi ? 30 : 1,
      imagePositionPatient: [0, 0, i],
      imageOrientationPatient: [1, 0, 0, 0, 1, 0],
    })),
    ...partial,
  }
}

describe('mprEligibility', () => {
  it('accepts CT/MR stacks with enough single-frame slices', () => {
    expect(seriesSupportsMpr(series({ modality: 'CT', n: MPR_MIN_SLICES }))).toBe(true)
    expect(seriesSupportsMpr(series({ modality: 'MR', n: 27 }))).toBe(true)
  })

  it('rejects US, XA, and short stacks', () => {
    expect(seriesSupportsMpr(series({ modality: 'US', n: 40 }))).toBe(false)
    expect(seriesSupportsMpr(series({ modality: 'XA', n: 40 }))).toBe(false)
    expect(seriesSupportsMpr(series({ modality: 'CT', n: MPR_MIN_SLICES - 1 }))).toBe(false)
  })

  it('rejects multi-frame-dominated series', () => {
    expect(seriesSupportsMpr(series({ modality: 'CT', n: 12, multi: true }))).toBe(false)
  })

  it('picks the densest eligible series, preferring the active one when valid', () => {
    const list = [
      series({ modality: 'US', n: 40, id: 'us', seriesInstanceUid: 'u' }),
      series({ modality: 'CT', n: 12, id: 'ct-short', seriesInstanceUid: 'c1' }),
      series({ modality: 'CT', n: 40, id: 'ct-long', seriesInstanceUid: 'c2' }),
    ]
    expect(pickDefaultMprSeriesIdx(list, 'CT')).toBe(2)
    expect(pickDefaultMprSeriesIdx(list, 'CT', 1)).toBe(1)
  })
})

import { describe, it, expect } from 'vitest'
import { defaultPrior, formatInterval, formatStudyDate, matchPriorSeries } from '../priors'

describe('prior comparison helpers', () => {
  it('describes the gap to the prior in the units a radiologist would use', () => {
    expect(formatInterval('2026-05-07', '2026-08-05')).toBe('3 months earlier')
    expect(formatInterval('2026-08-04', '2026-08-05')).toBe('1 day earlier')
    expect(formatInterval('2026-07-16', '2026-08-05')).toBe('20 days earlier')
    expect(formatInterval('2021-08-05', '2026-08-05')).toBe('5 years earlier')
  })

  it('has no interval to report when either date is missing or the prior is not older', () => {
    expect(formatInterval(null, '2026-08-05')).toBeNull()
    expect(formatInterval('2026-08-05', null)).toBeNull()
    expect(formatInterval('2026-08-05', '2026-08-05')).toBeNull()
  })

  it('auto-selects the newest same-modality prior and nothing otherwise', () => {
    const priors = [
      { id: 'mr-recent', modality: 'MR' },
      { id: 'mr-old', modality: 'MR' },
      { id: 'ct', modality: 'CT' },
    ]
    expect(defaultPrior(priors, 'MR')?.id).toBe('mr-recent')
    expect(defaultPrior(priors, 'CT')?.id).toBe('ct')
    expect(defaultPrior(priors, 'US')).toBeNull()
    expect(defaultPrior([], 'MR')).toBeNull()
  })

  it('pairs the prior series by description, ignoring case and punctuation drift', () => {
    const prior = [
      { seriesDescription: '3-Plane Localizer', seriesNumber: 1 },
      { seriesDescription: 'ax t2 FLAIR-FS', seriesNumber: 3 },
      { seriesDescription: 'Ax T1 FSE', seriesNumber: 4 },
    ]
    expect(matchPriorSeries({ seriesDescription: 'Ax T2 FLAIR FS', seriesNumber: 9 }, prior)).toBe(1)
  })

  it('falls back to series number when descriptions do not line up', () => {
    const prior = [
      { seriesDescription: 'Localizer', seriesNumber: 1 },
      { seriesDescription: 'T2 axial', seriesNumber: 4 },
    ]
    expect(matchPriorSeries({ seriesDescription: 'Ax T1 FSE', seriesNumber: 4 }, prior)).toBe(1)
  })

  it('leaves the prior pane alone rather than hanging an unrelated sequence', () => {
    const prior = [{ seriesDescription: 'Localizer', seriesNumber: 1 }]
    expect(matchPriorSeries({ seriesDescription: 'Ax DWI B-1000', seriesNumber: 7 }, prior)).toBe(-1)
    expect(matchPriorSeries(undefined, prior)).toBe(-1)
    expect(matchPriorSeries({ seriesDescription: 'Localizer', seriesNumber: 1 }, [])).toBe(-1)
  })

  it('labels undated studies rather than rendering an invalid date', () => {
    expect(formatStudyDate(null)).toBe('undated')
    expect(formatStudyDate('2026-05-07')).toContain('2026')
  })
})

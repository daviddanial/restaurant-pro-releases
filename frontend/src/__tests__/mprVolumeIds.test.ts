import { describe, it, expect } from 'vitest'
import { buildVolumeImageIds } from '../components/MprViewer'

describe('buildVolumeImageIds', () => {
  it('orders single-frame instances along the stack normal', () => {
    const ids = buildVolumeImageIds('study', 'series', [
      {
        sopUid: 'a',
        numberOfFrames: 1,
        imagePositionPatient: [0, 0, 10],
        imageOrientationPatient: [1, 0, 0, 0, 1, 0],
      },
      {
        sopUid: 'b',
        numberOfFrames: 1,
        imagePositionPatient: [0, 0, 0],
        imageOrientationPatient: [1, 0, 0, 0, 1, 0],
      },
      {
        sopUid: 'c',
        numberOfFrames: 1,
        imagePositionPatient: [0, 0, 5],
        imageOrientationPatient: [1, 0, 0, 0, 1, 0],
      },
      { sopUid: 'multi', numberOfFrames: 12 },
    ])
    expect(ids.map((id) => id.match(/instances\/([^/?]+)/)?.[1])).toEqual(['b', 'c', 'a'])
  })
})

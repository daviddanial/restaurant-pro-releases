import { describe, it, expect } from 'vitest'
import { presetsForModality, WL_PRESETS } from '../wlPresets'

describe('W/L presets', () => {
  it('offers one-click CT presets for soft tissue, lung, and bone', () => {
    const labels = presetsForModality('CT').map((p) => p.label)
    expect(labels).toEqual(['Soft tissue', 'Lung', 'Bone', 'Brain'])
    expect(WL_PRESETS.CT.every((p) => p.width > 0)).toBe(true)
  })

  it('maps CR/DX/MG to XR presets and unknown modality to defaults', () => {
    expect(presetsForModality('DX')).toBe(WL_PRESETS.XR)
    expect(presetsForModality('MG')).toBe(WL_PRESETS.XR)
    expect(presetsForModality('MR')).toBe(WL_PRESETS.MR)
    expect(presetsForModality(null)).toBe(WL_PRESETS.DEFAULT)
  })
})

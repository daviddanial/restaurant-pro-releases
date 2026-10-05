import { describe, it, expect } from 'vitest'
import { buildSlots, clampActiveId, slotCount } from '../viewportLayout'

describe('viewportLayout', () => {
  it('counts panes for each layout', () => {
    expect(slotCount('1x1')).toBe(1)
    expect(slotCount('1x2')).toBe(2)
    expect(slotCount('2x2')).toBe(4)
  })

  it('seeds each slot with consecutive series when opening a layout', () => {
    const slots = buildSlots('2x2', 6)
    expect(slots.map((s) => s.seriesIdx)).toEqual([0, 1, 2, 3])
  })

  it('reuses previous slot series when expanding the grid', () => {
    const one = buildSlots('1x1', 4)
    one[0].seriesIdx = 2
    const two = buildSlots('1x2', 4, one)
    expect(two[0].seriesIdx).toBe(2)
    expect(two[1].seriesIdx).toBe(1)
  })

  it('clamps series indices when the study has fewer series than panes', () => {
    const slots = buildSlots('2x2', 2)
    expect(slots.map((s) => s.seriesIdx)).toEqual([0, 1, 1, 1])
  })

  it('keeps the active id when the slot still exists', () => {
    const slots = buildSlots('1x2', 3)
    expect(clampActiveId(slots[1].id, slots)).toBe(slots[1].id)
    expect(clampActiveId('gone', slots)).toBe(slots[0].id)
  })
})

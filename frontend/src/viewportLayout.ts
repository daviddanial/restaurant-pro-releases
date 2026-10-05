export type LayoutMode = '1x1' | '1x2' | '2x2'

export type ViewportSlot = {
  id: string
  /** Index into the current study's series list (ignored when role is prior). */
  seriesIdx: number
  instanceIdx: number
}

export const LAYOUT_OPTIONS: { id: LayoutMode; label: string; title: string }[] = [
  { id: '1x1', label: '1×1', title: 'Single viewport' },
  { id: '1x2', label: '1×2', title: 'Two viewports side by side' },
  { id: '2x2', label: '2×2', title: 'Four viewports' },
]

export function slotCount(layout: LayoutMode): number {
  if (layout === '1x2') return 2
  if (layout === '2x2') return 4
  return 1
}

export function layoutClassName(layout: LayoutMode): string {
  if (layout === '1x2') return 'layout-1x2'
  if (layout === '2x2') return 'layout-2x2'
  return 'layout-1x1'
}

/**
 * Build slots for a same-study multi-series layout. Reuses prior slot series
 * where possible so switching 1×2 → 2×2 does not reset what the user already loaded.
 */
export function buildSlots(
  layout: LayoutMode,
  seriesCount: number,
  previous: ViewportSlot[] = [],
): ViewportSlot[] {
  const n = slotCount(layout)
  const maxSeries = Math.max(0, seriesCount - 1)
  return Array.from({ length: n }, (_, i) => {
    const prev = previous[i]
    const fallbackSeries = seriesCount === 0 ? 0 : Math.min(i, maxSeries)
    return {
      id: prev?.id ?? `slot-${i}`,
      seriesIdx: prev ? Math.min(prev.seriesIdx, maxSeries) : fallbackSeries,
      instanceIdx: prev?.instanceIdx ?? 0,
    }
  })
}

/** Ensure activeId still points at a live slot after a layout change. */
export function clampActiveId(activeId: string, slots: ViewportSlot[]): string {
  if (slots.some((s) => s.id === activeId)) return activeId
  return slots[0]?.id ?? 'slot-0'
}

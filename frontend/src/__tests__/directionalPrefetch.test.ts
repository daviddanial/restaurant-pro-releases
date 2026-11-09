import { describe, expect, it } from 'vitest'
import { DirectionalPrefetcher, prefetchOrder } from '../cornerstone/directionalPrefetch'

describe('prefetchOrder', () => {
  it('takes three ahead for every one behind, nearest first', () => {
    expect(prefetchOrder(20, 10, 1).slice(0, 8)).toEqual([11, 12, 13, 9, 14, 15, 16, 8])
  })
  it('follows the scroll direction', () => {
    expect(prefetchOrder(20, 10, -1).slice(0, 4)).toEqual([9, 8, 7, 11])
  })
  it('covers every other slice exactly once, also at the edges', () => {
    for (const cur of [0, 5, 19]) {
      for (const dir of [1, -1] as const) {
        const order = prefetchOrder(20, cur, dir)
        expect(order).toHaveLength(19)
        expect(new Set(order).size).toBe(19)
        expect(order).not.toContain(cur)
      }
    }
  })
  it('handles a single-image stack', () => {
    expect(prefetchOrder(1, 0, 1)).toEqual([])
  })
})

describe('DirectionalPrefetcher', () => {
  const ids = Array.from({ length: 10 }, (_, i) => `img${i}`)
  const flush = () => new Promise((r) => setTimeout(r, 0))

  it('never runs more than the concurrency limit and loads everything once', async () => {
    let running = 0, peak = 0
    const loaded: string[] = []
    const load = async (id: string) => {
      running++; peak = Math.max(peak, running)
      await flush()
      running--; loaded.push(id)
    }
    const p = new DirectionalPrefetcher(3, load, (id) => loaded.includes(id))
    p.start(ids, 0)
    for (let i = 0; i < 20; i++) await flush()
    expect(peak).toBe(3)
    expect([...loaded].sort()).toEqual(ids.filter((i) => i !== 'img0').sort())
  })

  it('re-prioritises around the new position and skips known slices', async () => {
    const started: string[] = []
    const pending: Array<() => void> = []
    const load = (id: string) => { started.push(id); return new Promise<void>((r) => pending.push(r)) }
    const p = new DirectionalPrefetcher(1, load, (id) => id === 'img6')
    p.start(ids, 0)
    expect(started).toEqual(['img1'])
    p.setCurrent(5) // moved down: next useful are 6 (known, skipped), 7, 8, then 4
    pending.shift()!(); await flush()
    expect(started).toEqual(['img1', 'img7'])
  })

  it('stops starting downloads after stop()', async () => {
    const started: string[] = []
    const pending: Array<() => void> = []
    const load = (id: string) => { started.push(id); return new Promise<void>((r) => pending.push(r)) }
    const p = new DirectionalPrefetcher(1, load, () => false)
    p.start(ids, 0)
    p.stop()
    pending.shift()!(); await flush()
    expect(started).toEqual(['img1'])
  })
})

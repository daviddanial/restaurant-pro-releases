import { cache, imageLoader } from '@cornerstonejs/core'

/**
 * Order in which to fetch the other slices of a stack, given the slice on screen and the
 * scroll direction: `aheadPerBehind` slices ahead for every one behind, nearest first.
 * The current slice itself is not included.
 */
export function prefetchOrder(total: number, current: number, direction: 1 | -1, aheadPerBehind = 3): number[] {
  const out: number[] = []
  let a = 1
  let b = 1
  for (;;) {
    let added = false
    for (let i = 0; i < aheadPerBehind; i++) {
      const x = current + direction * a
      if (x < 0 || x >= total) break
      out.push(x); a++; added = true
    }
    const y = current - direction * b
    if (y >= 0 && y < total) { out.push(y); b++; added = true }
    if (!added) return out
  }
}

type LoadFn = (imageId: string) => Promise<unknown>
type IsKnownFn = (imageId: string) => boolean

const defaultLoad: LoadFn = (imageId) =>
  imageLoader.loadAndCacheImage(imageId, { priority: 5, requestType: 'prefetch' } as never)
// Loaded, or already being loaded (e.g. the slice the user just asked for).
const defaultIsKnown: IsKnownFn = (imageId) => cache.getImageLoadObject(imageId) != null

/**
 * Background loader for one viewport. Keeps at most `concurrency` downloads running and,
 * each time one finishes, picks the most useful missing slice for where the user is now.
 * Nothing is queued ahead of time, so a change of position or direction takes effect
 * with the very next download, and other viewports are not affected.
 */
export class DirectionalPrefetcher {
  private imageIds: string[] = []
  private current = 0
  private direction: 1 | -1 = 1
  private readonly inflight = new Set<string>()
  private readonly failed = new Set<string>()
  private active = 0
  private generation = 0

  constructor(
    private readonly concurrency = 6,
    private readonly load: LoadFn = defaultLoad,
    private readonly isKnown: IsKnownFn = defaultIsKnown,
  ) {}

  start(imageIds: string[], current: number) {
    this.generation++
    this.imageIds = imageIds
    this.current = current
    this.direction = 1
    this.inflight.clear()
    this.failed.clear()
    this.pump()
  }

  /** Call whenever the displayed slice changes. */
  setCurrent(index: number) {
    if (index === this.current) return
    this.direction = index > this.current ? 1 : -1
    this.current = index
    this.pump()
  }

  stop() {
    this.generation++
    this.imageIds = []
  }

  private next(): string | null {
    for (const i of prefetchOrder(this.imageIds.length, this.current, this.direction)) {
      const id = this.imageIds[i]
      if (!this.inflight.has(id) && !this.failed.has(id) && !this.isKnown(id)) return id
    }
    return null
  }

  private pump() {
    const gen = this.generation
    while (this.active < this.concurrency) {
      const id = this.next()
      if (!id) return
      this.active++
      this.inflight.add(id)
      this.load(id)
        .catch(() => { if (gen === this.generation) this.failed.add(id) })
        .finally(() => {
          this.active--
          this.inflight.delete(id)
          if (gen === this.generation) this.pump()
        })
    }
  }
}

/** How many of the stack's images are decoded and in memory. */
export function countLoaded(imageIds: string[]): number {
  let n = 0
  for (const id of imageIds) if (cache.isLoaded(id)) n++
  return n
}

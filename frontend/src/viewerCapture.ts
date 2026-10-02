/** Download helpers and canvas → image capture for the viewer Export / Print flows. */

export type ImageFormat = 'png' | 'jpeg'

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export function canvasToBlob(canvas: HTMLCanvasElement, format: ImageFormat, quality = 0.92): Promise<Blob> {
  const type = format === 'jpeg' ? 'image/jpeg' : 'image/png'
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Failed to encode image'))),
      type,
      format === 'jpeg' ? quality : undefined,
    )
  })
}

/** Cornerstone (or any) canvas under a viewport host element. */
export function findViewportCanvas(host: Element | null | undefined): HTMLCanvasElement | null {
  if (!host) return null
  const canvas = host.querySelector('canvas')
  return canvas instanceof HTMLCanvasElement ? canvas : null
}

export function activeViewportCanvas(root: ParentNode = document): HTMLCanvasElement | null {
  const host =
    root.querySelector('.viewer-pane.active .viewport-canvas, .viewer-pane.active .viewport')
    ?? root.querySelector('.viewer-panes .viewport-canvas, .viewer-panes .viewport, .mpr-root .viewport')
  return findViewportCanvas(host)
}

export type LayoutTile = {
  label: string
  canvas: HTMLCanvasElement
}

/** Collect labeled panes in visual order for layout export / print. */
export function collectLayoutTiles(root: ParentNode = document): LayoutTile[] {
  const panes = [...root.querySelectorAll('.viewer-panes > .viewer-pane')]
  const tiles: LayoutTile[] = []
  for (const pane of panes) {
    const canvas = findViewportCanvas(
      pane.querySelector('.viewport-canvas, .viewport') ?? pane,
    )
    if (!canvas || canvas.width < 2 || canvas.height < 2) continue
    const label =
      pane.querySelector('.viewer-pane-label')?.textContent?.trim()
      || `View ${tiles.length + 1}`
    tiles.push({ label, canvas })
  }
  return tiles
}

/**
 * Compose tiles into a single PNG/JPEG canvas matching approximate grid:
 * 1 tile → 1×1, 2 → 1×2, 3–4 → 2×2.
 */
export async function composeLayoutCanvas(
  tiles: LayoutTile[],
  opts?: { gap?: number; background?: string; maxTile?: number },
): Promise<HTMLCanvasElement> {
  if (tiles.length === 0) throw new Error('No viewports to capture')
  const gap = opts?.gap ?? 8
  const bg = opts?.background ?? '#0b1220'
  const maxTile = opts?.maxTile ?? 1024

  const cols = tiles.length === 1 ? 1 : tiles.length === 2 ? 2 : 2
  const rows = tiles.length <= 2 ? 1 : 2

  const sizes = tiles.map((t) => {
    const scale = Math.min(1, maxTile / Math.max(t.canvas.width, t.canvas.height))
    return {
      w: Math.max(1, Math.round(t.canvas.width * scale)),
      h: Math.max(1, Math.round(t.canvas.height * scale)),
    }
  })
  const cellW = Math.max(...sizes.map((s) => s.w))
  const cellH = Math.max(...sizes.map((s) => s.h))
  const labelH = 28

  const out = document.createElement('canvas')
  out.width = cols * cellW + (cols + 1) * gap
  out.height = rows * (cellH + labelH) + (rows + 1) * gap
  const ctx = out.getContext('2d')
  if (!ctx) throw new Error('2D context unavailable')
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, out.width, out.height)
  ctx.font = '600 13px ui-sans-serif, system-ui, sans-serif'
  ctx.textBaseline = 'middle'

  tiles.forEach((tile, i) => {
    const col = i % cols
    const row = Math.floor(i / cols)
    const x0 = gap + col * (cellW + gap)
    const y0 = gap + row * (cellH + labelH + gap)
    ctx.fillStyle = '#e2e8f0'
    ctx.fillText(tile.label.replace(/\s+/g, ' ').slice(0, 48), x0 + 4, y0 + labelH / 2)
    const { w, h } = sizes[i]
    const dx = x0 + Math.floor((cellW - w) / 2)
    const dy = y0 + labelH + Math.floor((cellH - h) / 2)
    ctx.drawImage(tile.canvas, dx, dy, w, h)
  })
  return out
}

export async function captureViewportBlob(
  format: ImageFormat,
  root?: ParentNode,
): Promise<Blob> {
  const canvas = activeViewportCanvas(root)
  if (!canvas) throw new Error('No active viewport to export')
  return canvasToBlob(canvas, format)
}

export async function captureLayoutBlob(
  format: ImageFormat = 'png',
  root?: ParentNode,
): Promise<Blob> {
  const tiles = collectLayoutTiles(root)
  const composed = await composeLayoutCanvas(tiles)
  return canvasToBlob(composed, format)
}

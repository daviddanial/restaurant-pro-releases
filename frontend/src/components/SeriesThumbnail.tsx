import { useEffect, useRef, useState } from 'react'
import { utilities } from '@cornerstonejs/core'
import { buildWadoUriImageId, ensureCornerstoneInit } from '../cornerstone/initCornerstone'

type Props = {
  studyUid: string
  seriesUid: string
  sopUid: string
}

/**
 * Series preview drawn through the Cornerstone decode pipeline rather than the
 * server-side JPEG endpoint, so compressed transfer syntaxes the backend cannot
 * decode (JPEG-LS, JPEG 2000) still produce a thumbnail.
 */
export function SeriesThumbnail({ studyUid, seriesUid, sopUid }: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let cancelled = false

    async function render(target: HTMLCanvasElement) {
      try {
        await ensureCornerstoneInit()
        if (cancelled) return
        await utilities.loadImageToCanvas({
          canvas: target,
          imageId: buildWadoUriImageId(studyUid, seriesUid, sopUid),
          thumbnail: true,
          imageAspect: true,
        })
      } catch {
        if (!cancelled) setFailed(true)
      }
    }

    render(canvas)
    return () => {
      cancelled = true
    }
  }, [studyUid, seriesUid, sopUid])

  return (
    <span className="series-thumb">
      {failed ? <span className="series-thumb-empty" /> : <canvas ref={canvasRef} />}
    </span>
  )
}

import { init as csInit, metaData } from '@cornerstonejs/core'
import {
  init as csToolsInit,
  addTool,
  WindowLevelTool,
  PanTool,
  ZoomTool,
  StackScrollTool,
  LengthTool,
} from '@cornerstonejs/tools'
import dicomImageLoader from '@cornerstonejs/dicom-image-loader'
import { loadAuth } from '../api'

let initPromise: Promise<void> | null = null
let toolsRegistered = false

/**
 * One-time Cornerstone3D + DICOM loader + tools bootstrap.
 * Attaches Bearer auth to WADO-URI XHRs via beforeSend.
 */
export function ensureCornerstoneInit(): Promise<void> {
  if (!initPromise) {
    initPromise = (async () => {
      csInit()
      csToolsInit()

      // Registers wadouri/wadors loaders + metadata providers (PixelSpacing, VOI, etc.).
      // useLegacyMetadataProvider: Cornerstone 5.x default naturalizes Part-10 via dcmjs
      // AsyncDicomReader; for multi-frame JPEG Baseline XA that path often caches
      // NATURALIZED without COMPRESSED_FRAME_DATA → black viewport / decode failures.
      // Legacy uses dicom-parser + encapsulated-frame extraction (reliable for XA/US/MR).
      dicomImageLoader.init({
        maxWebWorkers: Math.min(navigator.hardwareConcurrency || 2, 4),
        useLegacyMetadataProvider: true,
        beforeSend(_xhr, _imageId, _defaultHeaders) {
          const token = loadAuth()?.accessToken
          if (token) {
            return { Authorization: `Bearer ${token}` }
          }
        },
      })

      // FALLBACK metadata provider (low priority => consulted only when the loader
      // providers return nothing). Fixes "Invalid VOI LUT function" / black viewport
      // on studies whose DICOM metadata omits the VOI LUT function (window values).
      metaData.addProvider((type: string) => {
        const t = (type || '').toLowerCase()
        if (t === 'voilutfunction' || t === 'voi_lut_function') return 'LINEAR'
        return undefined
      }, 10000)

      if (!toolsRegistered) {
        addTool(WindowLevelTool)
        addTool(PanTool)
        addTool(ZoomTool)
        addTool(StackScrollTool)
        addTool(LengthTool)
        toolsRegistered = true
      }
    })().catch((err) => {
      initPromise = null
      throw err
    })
  }
  return initPromise
}

export function buildWadoUriImageId(
  studyUid: string,
  seriesUid: string,
  sopUid: string,
  frame?: number,
): string {
  const base = `/api/dicom/studies/${studyUid}/series/${seriesUid}/instances/${sopUid}`
  // Absolute URL helps the loader when the page origin differs during preview.
  const absolute = new URL(base, window.location.origin).href
  if (frame != null && frame > 0) {
    return `wadouri:${absolute}?frame=${frame}`
  }
  return `wadouri:${absolute}`
}

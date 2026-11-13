import {
  init as csInit,
  volumeLoader,
  registerImageLoader,
  imageLoadPoolManager,
  Enums as CoreEnums,
  cornerstoneStreamingImageVolumeLoader,
} from '@cornerstonejs/core'
import {
  init as csToolsInit,
  addTool,
  WindowLevelTool,
  PanTool,
  ZoomTool,
  StackScrollTool,
  LengthTool,
  AngleTool,
  CobbAngleTool,
  EllipticalROITool,
  CrosshairsTool,
  TrackballRotateTool,
} from '@cornerstonejs/tools'
import dicomImageLoader from '@cornerstonejs/dicom-image-loader'
import { imageAuthHeaders, imageBase } from '../imageSource'
import { withRetry } from './retryingLoader'
import { StackProbeTool } from './stackProbeTool'

/** Background (prefetch) downloads allowed at once. The default is 1000, which sends a whole
 *  series at once: a newly opened study queues behind it, and one dropped connection fails
 *  hundreds of requests together. */
export const MAX_PREFETCH_REQUESTS = 6

let initPromise: Promise<void> | null = null
let toolsRegistered = false
let volumeLoaderRegistered = false

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
        // The cloud session token for cloud images; the image pass for the hospital server.
        beforeSend(_xhr, imageId, _defaultHeaders) {
          const url = String(imageId).replace(/^(wadouri|dicomweb):/, '')
          const headers = imageAuthHeaders(url)
          if (headers.Authorization) return headers
        },
      })

      // Retry transient failures (dropped mobile connections) instead of leaving a black viewport.
      const wadouriLoad = dicomImageLoader.wadouri.loadImage as unknown as Parameters<typeof withRetry>[0]
      const retrying = withRetry(wadouriLoad) as unknown as Parameters<typeof registerImageLoader>[1]
      registerImageLoader('wadouri', retrying)
      registerImageLoader('dicomweb', retrying)

      // Queued prefetches are dropped when the viewer moves to another series/study
      // (stackPrefetch.disable clears the queue), so only a few are ever in flight.
      imageLoadPoolManager.setMaxSimultaneousRequests(
        CoreEnums.RequestType.Prefetch,
        MAX_PREFETCH_REQUESTS,
      )

      if (!volumeLoaderRegistered) {
        volumeLoader.registerVolumeLoader(
          'cornerstoneStreamingImageVolume',
          cornerstoneStreamingImageVolumeLoader as unknown as Parameters<
            typeof volumeLoader.registerVolumeLoader
          >[1],
        )
        volumeLoaderRegistered = true
      }

      if (!toolsRegistered) {
        addTool(WindowLevelTool)
        addTool(PanTool)
        addTool(ZoomTool)
        addTool(StackScrollTool)
        addTool(LengthTool)
        addTool(AngleTool)
        addTool(CobbAngleTool)
        addTool(EllipticalROITool)
        addTool(StackProbeTool)
        addTool(CrosshairsTool)
        addTool(TrackballRotateTool)
        toolsRegistered = true
      }
    })().catch((err) => {
      initPromise = null
      throw err
    })
  }
  return initPromise
}

/** URL of one DICOM object, from the hospital server or the cloud (see imageSource). */
export function instanceUrl(studyUid: string, seriesUid: string, sopUid: string): string {
  return `${imageBase(studyUid)}/studies/${studyUid}/series/${seriesUid}/instances/${sopUid}`
}

export function buildWadoUriImageId(
  studyUid: string,
  seriesUid: string,
  sopUid: string,
  frame?: number,
): string {
  // Absolute URL: the hospital server is on another origin than the page.
  const absolute = instanceUrl(studyUid, seriesUid, sopUid)
  if (frame != null && frame > 0) {
    return `wadouri:${absolute}?frame=${frame}`
  }
  return `wadouri:${absolute}`
}

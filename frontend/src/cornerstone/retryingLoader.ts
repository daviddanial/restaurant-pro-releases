/**
 * Retry wrapper for the WADO-URI image loader.
 *
 * On mobile links a dropped connection fails every in-flight image at once. Without a
 * retry the viewport stays black until the user reloads. We retry network errors
 * (status 0), timeouts and 5xx with a short backoff; auth/404 errors fail immediately.
 */

type LoadObject = { promise: Promise<unknown>; cancelFn?: () => void }
type Loader = (imageId: string, options?: Record<string, unknown>) => LoadObject

export const RETRY_DELAYS_MS = [500, 1500, 4000]

/** True when the failure looks transient (network drop, timeout, server hiccup). */
export function isRetryable(err: unknown): boolean {
  // Rejects with the XMLHttpRequest on HTTP/network failure; the wadouri loader wraps it
  // as { error: xhr }.
  return typeof statusOf(err) === 'number' && isRetryableStatus(statusOf(err) as number)
}

export function statusOf(err: unknown): number | undefined {
  const e = err as { status?: unknown; error?: { status?: unknown } } | null
  if (typeof e?.status === 'number') return e.status
  if (typeof e?.error?.status === 'number') return e.error.status
  return undefined
}

function isRetryableStatus(status: number): boolean {
  return status === 0 || status === 408 || status === 429 || status >= 500
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export function withRetry(
  loader: Loader,
  delays: number[] = RETRY_DELAYS_MS,
  wait: (ms: number) => Promise<unknown> = sleep,
): Loader {
  return (imageId, options) => {
    const promise = (async () => {
      for (let attempt = 0; ; attempt++) {
        try {
          return await loader(imageId, options).promise
        } catch (err) {
          if (attempt >= delays.length || !isRetryable(err)) throw err
          console.warn(`[telerad] image load failed (status ${statusOf(err)}), retry ${attempt + 1}/${delays.length}`, imageId)
          await wait(delays[attempt])
        }
      }
    })()
    return { promise }
  }
}

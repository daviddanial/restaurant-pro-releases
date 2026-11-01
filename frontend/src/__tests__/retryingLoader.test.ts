import { describe, expect, it, vi } from 'vitest'
import { isRetryable, withRetry } from '../cornerstone/retryingLoader'

const noWait = () => Promise.resolve()

describe('isRetryable', () => {
  it('retries network drops, timeouts and server errors', () => {
    expect(isRetryable({ status: 0 })).toBe(true)
    expect(isRetryable({ status: 408 })).toBe(true)
    expect(isRetryable({ status: 429 })).toBe(true)
    expect(isRetryable({ status: 502 })).toBe(true)
    // wadouri loader wraps the XHR
    expect(isRetryable({ error: { status: 0 } })).toBe(true)
    expect(isRetryable({ error: { status: 404 } })).toBe(false)
  })
  it('does not retry auth, not-found or decode errors', () => {
    expect(isRetryable({ status: 401 })).toBe(false)
    expect(isRetryable({ status: 403 })).toBe(false)
    expect(isRetryable({ status: 404 })).toBe(false)
    expect(isRetryable(new Error('bad pixel data'))).toBe(false)
    expect(isRetryable(null)).toBe(false)
  })
})

describe('withRetry', () => {
  it('succeeds after transient failures', async () => {
    const loader = vi
      .fn()
      .mockReturnValueOnce({ promise: Promise.reject({ status: 0 }) })
      .mockReturnValueOnce({ promise: Promise.reject({ status: 503 }) })
      .mockReturnValueOnce({ promise: Promise.resolve('image') })
    const wait = vi.fn(noWait)
    await expect(withRetry(loader, [1, 2, 3], wait)('wadouri:x').promise).resolves.toBe('image')
    expect(loader).toHaveBeenCalledTimes(3)
    expect(wait).toHaveBeenCalledTimes(2)
  })

  it('gives up after the last delay', async () => {
    const loader = vi.fn(() => ({ promise: Promise.reject({ status: 0 }) }))
    await expect(withRetry(loader, [1, 2], noWait)('wadouri:x').promise).rejects.toEqual({ status: 0 })
    expect(loader).toHaveBeenCalledTimes(3)
  })

  it('fails immediately on a 403', async () => {
    const loader = vi.fn(() => ({ promise: Promise.reject({ status: 403 }) }))
    await expect(withRetry(loader, [1, 2], noWait)('wadouri:x').promise).rejects.toEqual({ status: 403 })
    expect(loader).toHaveBeenCalledTimes(1)
  })
})

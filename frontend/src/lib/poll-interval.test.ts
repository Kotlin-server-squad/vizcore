import { describe, it, expect } from 'vitest'
import { pollInterval, resolvePollDelay, resolveRefetchInterval } from './poll-interval'
import { ApiError, RateLimitedError, parseRetryAfter } from './api-errors'
import { shouldRetryQuery } from './query-client'

function queryState<T>(state: {
  data?: T
  error?: unknown
  dataUpdateCount?: number
  errorUpdateCount?: number
}) {
  return {
    state: {
      data: state.data,
      error: state.error ?? null,
      dataUpdateCount: state.dataUpdateCount ?? 0,
      errorUpdateCount: state.errorUpdateCount ?? 0,
    },
  }
}

describe('pollInterval', () => {
  it('disables polling for false', () => {
    expect(pollInterval(false)).toBe(false)
  })

  it('polls at the base interval, but never sooner than a 429 Retry-After', () => {
    const interval = pollInterval(10_000)
    if (interval === false) throw new Error('expected a function')
    expect(interval(queryState({}))).toBe(10_000)
    expect(interval(queryState({ error: new RateLimitedError(30_000) }))).toBe(30_000)
    expect(interval(queryState({ error: new RateLimitedError(2_000) }))).toBe(10_000)
  })
})

describe('connect-wizard resolve polling (#124)', () => {
  it('starts at 1.5 s and backs off, never faster than 1 s', () => {
    expect(resolvePollDelay(0)).toBe(1500)
    expect(resolvePollDelay(25)).toBe(3000)
    expect(resolvePollDelay(200)).toBe(5000)
    for (let attempt = 0; attempt < 500; attempt++) {
      expect(resolvePollDelay(attempt)).toBeGreaterThanOrEqual(1000)
    }
  })

  it('issues far fewer than 60 requests in the first five minutes', () => {
    let elapsed = 0
    let requests = 0
    while (elapsed < 5 * 60_000) {
      elapsed += resolvePollDelay(requests)
      requests++
    }
    // The old fixed 300 ms poll issued 1000 requests in five minutes.
    expect(requests).toBeLessThan(110)
    expect(requests / 5).toBeLessThan(60)
  })

  it('stops once the token is bound and honours Retry-After on a 429', () => {
    expect(resolveRefetchInterval(queryState({ data: { sessionId: 's' } }))).toBe(false)
    expect(
      resolveRefetchInterval(queryState({ data: null, error: new RateLimitedError(20_000) })),
    ).toBe(20_000)
  })
})

describe('parseRetryAfter', () => {
  it('reads delta-seconds and HTTP dates, clamped to a sane range', () => {
    expect(parseRetryAfter('58')).toBe(58_000)
    expect(parseRetryAfter(null)).toBe(10_000)
    expect(parseRetryAfter('garbage')).toBe(10_000)
    expect(parseRetryAfter('0')).toBe(1000)
    expect(parseRetryAfter('99999')).toBe(120_000)
    const now = Date.parse('2026-10-09T10:00:00Z')
    expect(parseRetryAfter('Fri, 09 Oct 2026 10:00:20 GMT', now)).toBe(20_000)
  })
})

describe('shouldRetryQuery', () => {
  it('never retries a 4xx (a retried 429 is a retry storm), retries other failures once', () => {
    expect(shouldRetryQuery(0, new RateLimitedError(1000))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(404, 'not found'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(503, 'unavailable'))).toBe(true)
    expect(shouldRetryQuery(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetryQuery(1, new TypeError('Failed to fetch'))).toBe(false)
  })
})

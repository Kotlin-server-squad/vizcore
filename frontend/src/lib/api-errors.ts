/**
 * Typed errors thrown by the api-client.
 *
 * `fetchJson` used to throw a bare `Error`, so every caller saw the same thing
 * for a 404, a 429 and a dead network — and rendered all of them as "Session
 * not found". Carrying the HTTP status lets the views tell the user what
 * actually happened and lets the query layer decide what is worth retrying.
 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

/**
 * The backend refused the request because the client exceeded a rate limit
 * (HTTP 429), or the client is still inside the cool-down a previous 429
 * asked for. `retryAfterMs` is how long to wait before trying again.
 */
export class RateLimitedError extends ApiError {
  readonly retryAfterMs: number

  constructor(retryAfterMs: number) {
    super(429, `Rate limited — retrying in ${Math.ceil(retryAfterMs / 1000)}s`)
    this.name = 'RateLimitedError'
    this.retryAfterMs = retryAfterMs
  }
}

/** Used when a 429 carries no (or an unparseable) `Retry-After` header. */
export const DEFAULT_RETRY_AFTER_MS = 10_000

/** Never honour a cool-down longer than this, whatever the header says. */
export const MAX_RETRY_AFTER_MS = 120_000

/**
 * Parse an HTTP `Retry-After` header (delta-seconds or an HTTP-date) into a
 * delay in milliseconds, clamped to [1s, MAX_RETRY_AFTER_MS].
 */
export function parseRetryAfter(header: string | null | undefined, now = Date.now()): number {
  if (!header) return DEFAULT_RETRY_AFTER_MS
  const trimmed = header.trim()
  let ms: number
  if (/^\d+$/.test(trimmed)) {
    ms = Number(trimmed) * 1000
  } else {
    const date = Date.parse(trimmed)
    if (Number.isNaN(date)) return DEFAULT_RETRY_AFTER_MS
    ms = date - now
  }
  return Math.min(Math.max(ms, 1000), MAX_RETRY_AFTER_MS)
}

/** True for client errors that retrying the same request cannot fix. */
export function isNonRetryable(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500
}

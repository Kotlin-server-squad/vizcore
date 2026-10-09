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

/**
 * The request never got an HTTP response: the server is down, the network is
 * gone, or the browser blocked the call. Distinct from an ApiError so the UI
 * can say "can't reach vizcore" rather than inventing a status.
 */
export class NetworkError extends Error {
  /** The underlying fetch rejection, for debugging. */
  readonly reason: unknown

  constructor(reason?: unknown) {
    super("Can't reach the vizcore server")
    this.name = 'NetworkError'
    this.reason = reason
  }
}

export type ApiErrorKind =
  | 'not-found'
  | 'unauthorized'
  | 'rate-limited'
  | 'server'
  | 'network'
  | 'client'
  | 'unknown'

export interface DescribedError {
  kind: ApiErrorKind
  title: string
  message: string
  /** Whether trying the same request again can succeed. */
  retryable: boolean
  /** For rate limits: how long the server asked us to wait. */
  retryAfterMs?: number
}

/**
 * Turn any thrown value into a truthful, user-facing description (#139).
 * `subject` names the thing that failed to load ("This session").
 */
export function describeApiError(error: unknown, subject = 'This resource'): DescribedError {
  if (error instanceof RateLimitedError) {
    return {
      kind: 'rate-limited',
      title: 'Rate limited',
      message: `The vizcore server is throttling requests. Retrying in ${Math.ceil(
        error.retryAfterMs / 1000,
      )}s.`,
      retryable: true,
      retryAfterMs: error.retryAfterMs,
    }
  }
  if (error instanceof NetworkError) {
    return {
      kind: 'network',
      title: "Can't reach vizcore",
      message: 'The vizcore server did not respond. Check that it is still running, then retry.',
      retryable: true,
    }
  }
  if (error instanceof ApiError) {
    if (error.status === 404 || error.status === 410) {
      return {
        kind: 'not-found',
        title: 'Not found',
        message: `${subject} does not exist on this server. It may have been deleted, or the server restarted (sessions are kept in memory).`,
        retryable: false,
      }
    }
    if (error.status === 401 || error.status === 403) {
      return {
        kind: 'unauthorized',
        title: 'Sign-in required',
        message: 'This server requires you to sign in to see this.',
        retryable: false,
      }
    }
    if (error.status >= 500) {
      return {
        kind: 'server',
        title: 'Server error',
        message: `The vizcore server failed to answer (HTTP ${error.status}): ${error.message}`,
        retryable: true,
      }
    }
    return { kind: 'client', title: 'Request failed', message: error.message, retryable: false }
  }
  return {
    kind: 'unknown',
    title: 'Something went wrong',
    message: error instanceof Error ? error.message : String(error),
    retryable: true,
  }
}

/** True for client errors that retrying the same request cannot fix. */
export function isNonRetryable(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500
}

import { RateLimitedError } from './api-errors'

/**
 * The slice of a TanStack `Query` these interval functions read. Typed
 * structurally (not as `Query<…>`) so passing one as `refetchInterval` does
 * not hijack the query's data-type inference.
 */
interface PolledQuery<TData = unknown> {
  state: {
    data: TData | undefined
    error: unknown
    dataUpdateCount: number
    errorUpdateCount: number
  }
}

/**
 * How often the workspace re-reads its snapshot, metrics and threads while the
 * live stream is ON but its SSE connection is down (#124). With the stream
 * connected nothing polls: SSE-driven invalidation refreshes the panels. With
 * the stream OFF nothing polls either: the view is a snapshot the user
 * refreshes explicitly.
 */
export const SSE_FALLBACK_POLL_MS = 10_000

/**
 * A `refetchInterval` that polls every `baseMs`, but never sooner than the
 * server's `Retry-After` after a 429. `false` disables polling.
 */
export function pollInterval(baseMs: number | false) {
  if (baseMs === false) return false as const
  return (query: PolledQuery): number => {
    const error = query.state.error
    if (error instanceof RateLimitedError) {
      return Math.max(baseMs, error.retryAfterMs)
    }
    return baseMs
  }
}

/**
 * Connect-wizard resolve-poll cadence (#124). The old fixed 300 ms poll
 * (200 req/min) drained the server's rate-limit budget in seconds and then
 * starved the user's own app of the `POST /api/sessions` it needs to bind.
 * Poll every 1.5 s while the user is most likely to be starting their app,
 * then back off.
 *
 * @param attempt - polls completed so far (successes + failures)
 */
export function resolvePollDelay(attempt: number): number {
  if (attempt < 20) return 1500 // first ~30 s
  if (attempt < 50) return 3000 // next ~90 s
  return 5000
}

/** `refetchInterval` for the resolve query: back off, honour 429, stop once bound. */
export function resolveRefetchInterval(
  query: PolledQuery<{ sessionId: string } | null>,
): number | false {
  const { data, error, dataUpdateCount, errorUpdateCount } = query.state
  if (data?.sessionId) return false
  const delay = resolvePollDelay(dataUpdateCount + errorUpdateCount)
  return error instanceof RateLimitedError ? Math.max(delay, error.retryAfterMs) : delay
}

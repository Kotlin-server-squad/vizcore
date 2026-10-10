import { useEffect, useState, useCallback, useRef } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { normalizeEvent } from '@/lib/utils'
import type { VizEvent, VizEventKind } from '@/types/api'
import {
  COROUTINE_EVENT_KINDS,
  DISPATCHER_EVENT_KINDS,
  DEFERRED_EVENT_KINDS,
  JOB_EVENT_KINDS,
  CHANNEL_EVENT_KINDS,
  FLOW_EVENT_KINDS,
  SYNC_EVENT_KINDS,
  ACTOR_EVENT_KINDS,
  SELECT_EVENT_KINDS,
} from '@/types/api'

/**
 * Complete SSE listener list (REVIEW CR-01).
 *
 * The SSE route names each event after its `kind`, and a browser EventSource
 * only delivers events that have a registered listener for that exact name —
 * any kind missing here is SILENTLY dropped. The list is therefore derived
 * from the shared kind constants in types/api.ts (one source of truth with
 * category detection) instead of a hand-maintained inline array, plus the
 * legacy kebab-case names for backwards compatibility.
 *
 * Completeness is enforced by use-event-stream-kinds.test.ts, which asserts
 * coverage of all 66 kinds registered in the backend's
 * VizEventSerializersModule.kt.
 */
export const SSE_EVENT_TYPES: readonly string[] = [
  ...COROUTINE_EVENT_KINDS,
  ...JOB_EVENT_KINDS, // includes WaitingForChildren
  ...DISPATCHER_EVENT_KINDS,
  ...DEFERRED_EVENT_KINDS,
  ...CHANNEL_EVENT_KINDS,
  ...FLOW_EVENT_KINDS,
  ...SYNC_EVENT_KINDS, // mutex + semaphore + deadlock
  ...ACTOR_EVENT_KINDS,
  ...SELECT_EVENT_KINDS,
  'AntiPatternDetected',
  // Legacy kebab-case names (backwards compatibility)
  'coroutine.created',
  'coroutine.started',
  'coroutine.suspended',
  'coroutine.resumed',
  'coroutine.body-completed',
  'coroutine.completed',
  'coroutine.cancelled',
  'coroutine.failed',
  'thread.assigned',
]

/**
 * Coalescing window for SSE-driven cache refreshes (ms): a burst of events
 * that starts after a quiet period is refreshed once, this long after its
 * first event.
 */
const REFRESH_COALESCE_MS = 400

/**
 * Minimum gap between two SSE-driven refreshes (ms) — a throttle, not a
 * debounce, so a sustained stream refreshes at a steady, bounded rate instead
 * of either never (pure debounce) or once a second (#124/#137). Each refresh
 * re-reads three cheap endpoints (snapshot, threads, metrics), so a session
 * that streams non-stop costs at most 3 x 60/5 = 36 requests a minute; a quiet
 * stream costs nothing.
 */
export const LIVE_REFRESH_MIN_INTERVAL_MS = 5000

/**
 * Upper bound on buffered live events (#137). Matches the backend's bounded
 * EventStore ring (10k): the backend replays at most this many on connect, so
 * older events would be gone after a reload anyway. Without a bound a long
 * stream grows browser memory without limit.
 */
export const LIVE_EVENTS_MAX = 10_000

/**
 * Re-read the live view's server-side read models for one session.
 *
 * `exact: true` matters (#137): the snapshot key ['sessions', id] is a PREFIX
 * of ['sessions', id, 'events'] — the full event history, up to 10k events —
 * and of the hierarchy/timeline keys. A prefix invalidation re-downloaded the
 * entire history on every flush although the SSE stream already delivers it.
 */
function refreshLiveQueries(queryClient: QueryClient, sessionId: string) {
  void queryClient.invalidateQueries({ queryKey: ['sessions', sessionId], exact: true })
  // CR-01: the threads panel relies on this while live (it does not poll).
  void queryClient.invalidateQueries({ queryKey: ['thread-activity', sessionId], exact: true })
  void queryClient.invalidateQueries({ queryKey: ['session-metrics', sessionId], exact: true })
}

/** Keep only the newest LIVE_EVENTS_MAX events. */
function capLiveEvents(list: VizEvent[]): VizEvent[] {
  return list.length > LIVE_EVENTS_MAX ? list.slice(list.length - LIVE_EVENTS_MAX) : list
}

/**
 * Bounded retry budget for FATAL EventSource errors (UAT gap 2). Per the
 * EventSource spec a non-200 response is terminal — the browser will NOT
 * auto-reconnect — so without our own retry the live view stays dead until
 * a full page reload.
 */
const SSE_MAX_RETRIES = 5

/** Base reconnect delay; doubles per attempt (1s, 2s, 4s, 8s, 8s). */
const SSE_RETRY_BASE_DELAY_MS = 1000

/** Exponential backoff cap. */
const SSE_RETRY_MAX_DELAY_MS = 8000

/**
 * Upper bound for the replay-dedup seen-seq set (REVIEW WR-13). When the set
 * exceeds this size the oldest entries (insertion order) are evicted. The
 * bound only needs to comfortably cover the reconnect replay window (the
 * backend replays full history on every connection), so eviction can only
 * matter for sessions far larger than the bounded EventStore retains.
 */
const SEEN_SEQS_MAX = 10_000

/**
 * The CLOSED readyState as a numeric literal — jsdom test environments do
 * not reliably provide the EventSource global (so we avoid its static
 * constants), and the hook only ever receives instances from
 * apiClient.createEventSource.
 */
const EVENTSOURCE_CLOSED = 2

export function useEventStream(
  sessionId: string | undefined,
  enabled = true,
  replayActive = false,
) {
  const [events, setEvents] = useState<VizEvent[]>([])
  const [isConnected, setIsConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Cumulative count of events the backend shed under overload (D-08). Surfaced
  // from the `dropped` control frame ONLY — never derived from a stored event,
  // so the rendered event list stays free of phantom nodes (T-10-13).
  const [droppedCount, setDroppedCount] = useState(0)
  // Total events accepted since the stream (re)started. Unlike events.length
  // it keeps counting once the buffer is capped, so "N new events" stays true.
  const [receivedCount, setReceivedCount] = useState(0)
  // Bumped by reconnect() to tear down and re-open the stream after the
  // automatic retry budget is spent.
  const [connectNonce, setConnectNonce] = useState(0)
  const queryClient = useQueryClient()
  // Live mirror of replayActive so the SSE listener (registered once per
  // connection inside the effect below) reads the current value without
  // tearing down / re-registering the EventSource. While true, the cache
  // side effect (invalidation/refetch) is gated OFF — events still buffer
  // for the "● N new events" badge, but the frozen replay panels are not
  // jittered by live invalidation (D-02 / T-02-12).
  const replayActiveRef = useRef(replayActive)
  // Pending throttled refresh. While one is scheduled, further events ride
  // along with it instead of scheduling another.
  const refreshTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // When the last refresh ran — the throttle's reference point.
  const lastRefreshAtRef = useRef(Number.NEGATIVE_INFINITY)
  // Consecutive fatal-error retries since the last successful open.
  const retryCountRef = useRef(0)
  // Pending reconnect timer (fatal-error backoff), cancelled on teardown.
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Seqs already appended. The backend replays FULL history on every new
  // connection (REVIEW WR-01), so without dedup a reconnect would visibly
  // duplicate every event in the live view. Dedup is by MEMBERSHIP, not a
  // max-seq watermark: seq is allocated at event construction but appended
  // later without a common lock, so events can legitimately arrive out of
  // seq order — a watermark would silently drop them (REVIEW WR-13).
  const seenSeqsRef = useRef<Set<number>>(new Set())
  // The current EventSource (reconnects replace it within one effect run).
  const eventSourceRef = useRef<EventSource | null>(null)

  const clearEvents = useCallback(() => {
    setEvents([])
  }, [])

  const reconnect = useCallback(() => {
    setConnectNonce(n => n + 1)
  }, [])

  useEffect(() => {
    if (!sessionId || !enabled) {
      setIsConnected(false)
      setError(null)
      return
    }

    // Reset on sessionId/enabled change only — NOT on reconnect, which
    // happens inside a single effect run via connect() below. The event
    // buffer is cleared together with the dedup state (WR-16): TanStack
    // Router reuses the component instance on param-only navigation, so
    // without this reset session B's replay would be appended after session
    // A's events, and re-enabling the stream would duplicate history.
    // clearEvents stays available for explicit user-driven clearing.
    retryCountRef.current = 0
    seenSeqsRef.current = new Set()
    lastRefreshAtRef.current = Number.NEGATIVE_INFINITY
    setEvents([])
    setDroppedCount(0)
    setReceivedCount(0)
    setError(null)

    const flushRefresh = () => {
      refreshTimerRef.current = null
      lastRefreshAtRef.current = Date.now()
      refreshLiveQueries(queryClient, sessionId)
    }

    // Throttled refresh (#124/#137): at most one per
    // LIVE_REFRESH_MIN_INTERVAL_MS, the first one REFRESH_COALESCE_MS after a
    // quiet period so a burst still lands as a single refresh.
    const scheduleRefresh = () => {
      // D-02 replay gate: while replay is active events keep buffering (for
      // the "N new events" badge) but the frozen panels are not refreshed;
      // the replay-exit effect below performs one flush instead (D-04).
      if (replayActiveRef.current) return
      if (refreshTimerRef.current !== null) return
      const sinceLast = Date.now() - lastRefreshAtRef.current
      const delay = Math.max(REFRESH_COALESCE_MS, LIVE_REFRESH_MIN_INTERVAL_MS - sinceLast)
      refreshTimerRef.current = setTimeout(flushRefresh, delay)
    }

    // Append accepted events in ONE state update (#137): a batch frame of N
    // events used to cost N array copies and N updates.
    const append = (accepted: VizEvent[]) => {
      if (accepted.length === 0) return
      setEvents(prev => capLiveEvents(prev.concat(accepted)))
      setReceivedCount(n => n + accepted.length)
      scheduleRefresh()
    }

    // Shared per-element ingest body (D-04). Both the single-event per-kind
    // listeners AND the batch-array loop route every raw event through this
    // one function so the seq-dedup and bounded-set eviction cannot drift
    // between the two paths. Returns the normalized event, or null when it is
    // a replayed duplicate. `fallbackKind` lets a per-kind frame stamp its
    // event name when the payload omits `kind`.
    const accept = (rawEvent: unknown, fallbackKind?: string): VizEvent | null => {
      const event = normalizeEvent(rawEvent)
      if (!event.kind && fallbackKind) {
        (event as { kind?: VizEventKind }).kind = fallbackKind as VizEventKind
      }

      // Replay dedup: a reconnect replays FULL history, so drop any event whose
      // seq was already appended. Membership in a bounded seen-set (not a
      // max-seq watermark) so legitimately out-of-order seqs are NOT dropped
      // (WR-13). Events without a numeric seq (legacy kebab-case frames)
      // bypass the guard.
      const seq = (event as { seq?: unknown }).seq
      if (typeof seq === 'number') {
        if (seenSeqsRef.current.has(seq)) {
          return null
        }
        seenSeqsRef.current.add(seq)
        // Bound the set: evict the oldest entry (Set iteration is
        // insertion-ordered) once the cap is exceeded.
        if (seenSeqsRef.current.size > SEEN_SEQS_MAX) {
          const oldest = seenSeqsRef.current.values().next().value
          if (oldest !== undefined) {
            seenSeqsRef.current.delete(oldest)
          }
        }
      }
      return event
    }

    const connect = () => {
      retryTimerRef.current = null

      try {
        const eventSource = apiClient.createEventSource(sessionId)
        eventSourceRef.current = eventSource

        eventSource.onopen = () => {
          setIsConnected(true)
          setError(null)
          // A successful open restores the full retry budget.
          retryCountRef.current = 0
        }

        eventSource.onerror = () => {
          setIsConnected(false)

          if (eventSourceRef.current?.readyState === EVENTSOURCE_CLOSED) {
            // FATAL: per the EventSource spec, CLOSED is the terminal state
            // after a non-200 response — the browser will not reconnect on
            // its own (exactly the UAT gap 2 failure mode). Retry ourselves
            // with bounded exponential backoff.
            eventSourceRef.current.close()
            eventSourceRef.current = null

            if (retryCountRef.current < SSE_MAX_RETRIES) {
              retryCountRef.current += 1
              const delay = Math.min(
                SSE_RETRY_BASE_DELAY_MS * 2 ** (retryCountRef.current - 1),
                SSE_RETRY_MAX_DELAY_MS,
              )
              setError('Connection lost — retrying')
              retryTimerRef.current = setTimeout(connect, delay)
            } else {
              setError('Connection lost')
            }
          } else {
            // TRANSIENT: the browser's native auto-reconnect is still alive.
            // Do NOT create a new EventSource (prevents double connections).
            setError('Connection lost')
          }
        }

        // Listen for ALL backend event kinds (PascalCase wire names, derived
        // from the shared kind constants) plus the legacy kebab-case names.
        SSE_EVENT_TYPES.forEach(eventType => {
          eventSource.addEventListener(eventType, (e: Event) => {
            const messageEvent = e as MessageEvent
            try {
              const event = accept(JSON.parse(messageEvent.data), eventType)
              if (event) append([event])
            } catch {
              // Silently ignore malformed events
            }
          })
        })

        // Hybrid wire format (D-04): under load the backend coalesces events
        // into a single `event: batch` frame whose data is a JSON ARRAY. A
        // browser EventSource dispatches by event NAME, so a batched array can
        // never ride `event: <kind>` — it needs its own listener (Pitfall P4).
        // Every element goes through the SAME accept() spine so it is
        // seq-deduped one-by-one (T-10-12); the survivors are appended in a
        // single state update (#137). A malformed/non-array frame is skipped
        // by the try/catch so one bad frame cannot kill the listener (T-10-14).
        eventSource.addEventListener('batch', (e: Event) => {
          const messageEvent = e as MessageEvent
          try {
            const parsed: unknown = JSON.parse(messageEvent.data)
            if (!Array.isArray(parsed)) {
              return
            }
            const accepted: VizEvent[] = []
            for (const rawEvent of parsed) {
              const event = accept(rawEvent)
              if (event) accepted.push(event)
            }
            append(accepted)
          } catch {
            // Silently ignore malformed batch frames
          }
        })

        // Drop observability (D-08): when the backend sheds non-structural
        // events under overload it emits a `dropped` control frame
        // ({"count":N}). Surface the cumulative count as a marker. This is a
        // NON-stored control frame — it must NOT route through accept/append,
        // must NOT append to `events`, and must NOT touch seenSeqsRef, otherwise
        // it would render as a phantom node (T-10-13). Mirrors the `error`
        // control-frame listener below.
        eventSource.addEventListener('dropped', (e: Event) => {
          const messageEvent = e as MessageEvent
          try {
            const data = JSON.parse(messageEvent.data)
            const count = (data as { count?: unknown }).count
            if (typeof count === 'number' && count > 0) {
              setDroppedCount(prev => prev + count)
            }
          } catch {
            // Silently ignore malformed dropped frames
          }
        })

        // Also listen for error events from server
        eventSource.addEventListener('error', (e: Event) => {
          const messageEvent = e as MessageEvent
          if (messageEvent.data) {
            try {
              const errorData = JSON.parse(messageEvent.data)
              setError(errorData.error || 'Unknown error')
            } catch {
              // ignore
            }
          }
        })
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to connect')
        setIsConnected(false)
      }
    }

    connect()

    return () => {
      // Cancel any pending fatal-error reconnect
      if (retryTimerRef.current !== null) {
        clearTimeout(retryTimerRef.current)
        retryTimerRef.current = null
      }
      if (eventSourceRef.current) {
        eventSourceRef.current.close()
        eventSourceRef.current = null
        setIsConnected(false)
      }
      // Clear any pending refresh on teardown
      if (refreshTimerRef.current !== null) {
        clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
    }
  }, [sessionId, enabled, queryClient, connectNonce])

  // Replay gate sync + exit flush (D-02 / D-04). Keep the ref in lockstep with
  // the prop so the SSE listener reads the live value, and when replay turns
  // OFF perform exactly one invalidation flush so the events buffered during
  // replay apply to the now-live panels. Cancel any stale pending debounce
  // timer first so the flush stays single. Skipped when there is no active
  // session/stream (nothing to flush).
  const wasReplayActiveRef = useRef(replayActive)
  useEffect(() => {
    const wasActive = wasReplayActiveRef.current
    replayActiveRef.current = replayActive
    wasReplayActiveRef.current = replayActive

    if (wasActive && !replayActive && sessionId && enabled) {
      if (refreshTimerRef.current !== null) {
        clearTimeout(refreshTimerRef.current)
        refreshTimerRef.current = null
      }
      lastRefreshAtRef.current = Date.now()
      refreshLiveQueries(queryClient, sessionId)
    }
  }, [replayActive, sessionId, enabled, queryClient])

  return {
    /** Buffered live events, newest last, capped at LIVE_EVENTS_MAX. */
    events,
    isConnected,
    /** Human-readable connection problem, or null while healthy. */
    error,
    clearEvents,
    /** Events the backend shed under overload since the stream started. */
    droppedCount,
    /** Events accepted since the stream started (keeps counting past the cap). */
    receivedCount,
    /** Re-open the stream, e.g. after the retry budget is exhausted. */
    reconnect,
  }
}

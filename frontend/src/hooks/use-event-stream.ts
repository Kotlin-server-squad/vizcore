import { useEffect, useState, useCallback, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
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

/** Debounce window for batching SSE-driven cache invalidations (ms). */
const INVALIDATION_DEBOUNCE_MS = 400

/**
 * Max-wait cap for the invalidation debounce (ms). Under a sustained event
 * stream whose inter-event gap stays below INVALIDATION_DEBOUNCE_MS, a pure
 * trailing-edge debounce would be reset forever and never flush. This cap
 * guarantees at least one flush per INVALIDATION_MAX_WAIT_MS.
 */
const INVALIDATION_MAX_WAIT_MS = 1000

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

export function useEventStream(sessionId: string | undefined, enabled = true) {
  const [events, setEvents] = useState<VizEvent[]>([])
  const [isConnected, setIsConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const queryClient = useQueryClient()
  // Ref to hold the debounce timer for invalidation — reset on each event,
  // so a burst of events produces only one trailing-edge invalidation.
  const invalidationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Timestamp of the first event in the current un-flushed window. Used to
  // enforce the max-wait cap so a sustained stream cannot starve the flush.
  const firstInvalidationAtRef = useRef<number | null>(null)
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

  useEffect(() => {
    if (!sessionId || !enabled) {
      setIsConnected(false)
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
    setEvents([])

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
              const rawEvent = JSON.parse(messageEvent.data)
              // Normalize event from backend format (type -> kind)
              const event = normalizeEvent(rawEvent)
              // If still no kind, set from SSE event type
              if (!event.kind) {
                (event as { kind?: VizEventKind }).kind = eventType as VizEventKind
              }

              // Replay dedup: a reconnect replays FULL history, so drop any
              // event whose seq was already appended — BEFORE setEvents and
              // BEFORE the invalidation debounce (duplicates must not burn
              // invalidations either). Membership in a bounded seen-set (not
              // a max-seq watermark) so legitimately out-of-order seqs are
              // NOT dropped (WR-13). Events without a numeric seq (legacy
              // kebab-case frames) bypass the guard.
              const seq = (event as { seq?: unknown }).seq
              if (typeof seq === 'number') {
                if (seenSeqsRef.current.has(seq)) {
                  return
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

              setEvents(prev => [...prev, event])

              // Max-wait-capped debounced invalidation: a burst of events still
              // produces only one trailing-edge invalidation, but a sustained
              // stream is guaranteed to flush at least once per
              // INVALIDATION_MAX_WAIT_MS (the trailing edge can never be pushed
              // past the max-wait boundary).
              const flushInvalidation = () => {
                invalidationTimerRef.current = null
                // Reset the window so the next event starts a fresh max-wait clock.
                firstInvalidationAtRef.current = null
                queryClient.invalidateQueries({ queryKey: ['sessions', sessionId] })
                // CR-01 fix: the Threads tab relies on this invalidation while
                // live (its background poll is slowed during streaming).
                queryClient.invalidateQueries({ queryKey: ['thread-activity', sessionId] })
              }

              if (firstInvalidationAtRef.current === null) {
                firstInvalidationAtRef.current = Date.now()
              }
              const elapsed = Date.now() - firstInvalidationAtRef.current

              if (invalidationTimerRef.current !== null) {
                clearTimeout(invalidationTimerRef.current)
              }
              if (elapsed >= INVALIDATION_MAX_WAIT_MS) {
                flushInvalidation()
              } else {
                invalidationTimerRef.current = setTimeout(
                  flushInvalidation,
                  Math.min(INVALIDATION_DEBOUNCE_MS, INVALIDATION_MAX_WAIT_MS - elapsed),
                )
              }
            } catch {
              // Silently ignore malformed events
            }
          })
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
      // Clear any pending debounce timer on teardown
      if (invalidationTimerRef.current !== null) {
        clearTimeout(invalidationTimerRef.current)
        invalidationTimerRef.current = null
      }
      firstInvalidationAtRef.current = null
    }
  }, [sessionId, enabled, queryClient])

  return {
    events,
    isConnected,
    error,
    clearEvents,
  }
}

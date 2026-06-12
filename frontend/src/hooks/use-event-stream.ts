import { useEffect, useState, useCallback, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { normalizeEvent } from '@/lib/utils'
import type { VizEvent, VizEventKind } from '@/types/api'

/** Debounce window for batching SSE-driven cache invalidations (ms). */
const INVALIDATION_DEBOUNCE_MS = 400

/**
 * Max-wait cap for the invalidation debounce (ms). Under a sustained event
 * stream whose inter-event gap stays below INVALIDATION_DEBOUNCE_MS, a pure
 * trailing-edge debounce would be reset forever and never flush. This cap
 * guarantees at least one flush per INVALIDATION_MAX_WAIT_MS.
 */
const INVALIDATION_MAX_WAIT_MS = 1000

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

  const clearEvents = useCallback(() => {
    setEvents([])
  }, [])

  useEffect(() => {
    if (!sessionId || !enabled) {
      setIsConnected(false)
      return
    }

    let eventSource: EventSource | null = null

    try {
      eventSource = apiClient.createEventSource(sessionId)

      eventSource.onopen = () => {
        setIsConnected(true)
        setError(null)
      }

      eventSource.onerror = () => {
        setIsConnected(false)
        setError('Connection lost')
      }

      // Listen for all event types - both backend format (PascalCase) and frontend format (kebab-case)
      // Backend sends: CoroutineCreated, CoroutineStarted, etc.
      // Frontend expects: coroutine.created, coroutine.started, etc.
      const eventTypes = [
        // Backend PascalCase format
        'CoroutineCreated',
        'CoroutineStarted',
        'CoroutineSuspended',
        'CoroutineResumed',
        'CoroutineBodyCompleted',
        'CoroutineCompleted',
        'CoroutineCancelled',
        'CoroutineFailed',
        'ThreadAssigned',
        'DispatcherSelected',
        'DeferredValueAvailable',
        'DeferredAwaitStarted',
        'DeferredAwaitCompleted',
        'JobStateChanged',
        'JobCancellationRequested',
        'JobJoinRequested',
        'JobJoinCompleted',
        // Frontend kebab-case format (for backwards compatibility)
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

      eventTypes.forEach(eventType => {
        eventSource?.addEventListener(eventType, (e: Event) => {
          const messageEvent = e as MessageEvent
          try {
            const rawEvent = JSON.parse(messageEvent.data)
            // Normalize event from backend format (type -> kind)
            const event = normalizeEvent(rawEvent)
            // If still no kind, set from SSE event type
            if (!event.kind) {
              (event as any).kind = eventType as VizEventKind
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

    return () => {
      if (eventSource) {
        eventSource.close()
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


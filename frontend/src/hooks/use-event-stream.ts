import { useEffect, useState, useCallback, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiClient } from '@/lib/api-client'
import { normalizeEvent } from '@/lib/utils'
import type { VizEvent, VizEventKind } from '@/types/api'

/** Debounce window for batching SSE-driven cache invalidations (ms). */
const INVALIDATION_DEBOUNCE_MS = 400

export function useEventStream(sessionId: string | undefined, enabled = true) {
  const [events, setEvents] = useState<VizEvent[]>([])
  const [isConnected, setIsConnected] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const queryClient = useQueryClient()
  // Ref to hold the debounce timer for invalidation — reset on each event,
  // so a burst of events produces only one trailing-edge invalidation.
  const invalidationTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

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

            // Debounced invalidation: reset the timer on each event so that a burst
            // of events produces only one trailing-edge invalidation call.
            if (invalidationTimerRef.current !== null) {
              clearTimeout(invalidationTimerRef.current)
            }
            invalidationTimerRef.current = setTimeout(() => {
              invalidationTimerRef.current = null
              queryClient.invalidateQueries({ queryKey: ['sessions', sessionId] })
            }, INVALIDATION_DEBOUNCE_MS)
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
    }
  }, [sessionId, enabled, queryClient])

  return {
    events,
    isConnected,
    error,
    clearEvents,
  }
}


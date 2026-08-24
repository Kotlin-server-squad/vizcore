import { useEffect, useRef } from 'react'

/** Debounce window for coalescing live-event-driven session refetches (ms). */
const SESSION_REFETCH_DEBOUNCE_MS = 500

/**
 * Max-wait cap for the session-refetch debounce (ms). Under a sustained event
 * stream whose inter-event gap stays below SESSION_REFETCH_DEBOUNCE_MS, a pure
 * trailing-edge debounce would never fire; this cap guarantees the session
 * snapshot refetches at least once per SESSION_REFETCH_MAX_WAIT_MS.
 */
const SESSION_REFETCH_MAX_WAIT_MS = 1500

interface UseSessionRefetchArgs {
  /**
   * Whether live events should drive refetches at all. False while replay is
   * active (D-02: the frozen panels must not be refetched out from under the
   * cursor — buffered events apply on exit via the useEventStream exit flush)
   * and while the stream is off.
   */
  enabled: boolean
  /** Live event count. Its change is the trigger; the value itself is unused. */
  eventCount: number
  refetch: () => void
  /**
   * The max-wait window is torn down when the STREAM toggles, not when
   * `enabled` does. Entering replay suspends refetching but must not restart
   * the clock — on exit the window should carry on where it left off.
   */
  streamEnabled: boolean
}

/**
 * Coalesced session refetch (CR-02): debounce so a burst of SSE events triggers
 * at most one refetch per debounce window (trailing edge), with a max-wait cap
 * so a sustained stream still refetches at least once per max-wait window — the
 * trailing edge can never be starved.
 */
export function useSessionRefetch({
  enabled,
  eventCount,
  refetch,
  streamEnabled,
}: UseSessionRefetchArgs) {
  // Debounce ref: reset on each new live event; only the trailing edge refetches.
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Timestamp of the first un-flushed live event in the current debounce
  // window — used to enforce the max-wait cap.
  const firstAtRef = useRef<number | null>(null)

  useEffect(() => {
    if (!enabled || eventCount === 0) return

    const flushRefetch = () => {
      timerRef.current = null
      // Reset the window so the next event starts a fresh max-wait clock.
      firstAtRef.current = null
      refetch()
    }

    if (firstAtRef.current === null) {
      firstAtRef.current = Date.now()
    }
    const elapsed = Date.now() - firstAtRef.current

    if (timerRef.current !== null) {
      clearTimeout(timerRef.current)
    }
    if (elapsed >= SESSION_REFETCH_MAX_WAIT_MS) {
      flushRefetch()
    } else {
      timerRef.current = setTimeout(
        flushRefetch,
        Math.min(SESSION_REFETCH_DEBOUNCE_MS, SESSION_REFETCH_MAX_WAIT_MS - elapsed),
      )
    }

    return () => {
      // NOTE: this cleanup runs between every eventCount change, so it must NOT
      // reset firstAtRef here — doing so would restart the max-wait clock on
      // every event and reintroduce starvation. The window ref is reset on
      // flush (above) and on teardown (below).
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [enabled, eventCount, refetch])

  // Teardown for the max-wait window: reset the debounce refs only when the
  // stream toggles or the component unmounts (not between individual events).
  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
      firstAtRef.current = null
    }
  }, [streamEnabled])
}

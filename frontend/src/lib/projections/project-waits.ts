/**
 * Pure client-side reducer: VizEvent[] -> what each coroutine waits for right now (spec 2026-11-05, S-7).
 *
 * A wait starts at a `coroutine.suspended` event that a mutex, semaphore or channel wrapper recorded with
 * `waitKind` (and `resourceId`), and ends at the coroutine's next resume, suspension or terminal event. A
 * suspension without `waitKind` (a delay, an await, or an event from an older producer) is not a wrapper wait.
 *
 * Kept apart from projectCoroutines, whose nodes must deep-equal the server snapshot. Framework-free and
 * side-effect-free, so it is safe to call inside a useMemo over the replay `visibleEvents` slice.
 */

import type { CoroutineSuspendedEvent, VizEvent, WaitKind } from '@/types/api'

export interface WaitFact {
  waitKind: WaitKind
  /** The mutexId, semaphoreId or channelId waited for; null when the producer did not say. */
  resourceId: string | null
  /** seq and tsNanos of the suspension that started the wait. */
  sinceSeq: number
  sinceTsNanos: number
}

/** Event kinds that end a coroutine's current wait (a suspension may start the next one). */
const ENDS_WAIT: ReadonlySet<string> = new Set([
  'coroutine.suspended',
  'coroutine.resumed',
  'coroutine.completed',
  'coroutine.cancelled',
  'coroutine.failed',
])

/**
 * @param events - VizEvents in seq order (typically the replay `visibleEvents` slice)
 * @returns coroutineId -> its current wait, for coroutines waiting on a wrapper now
 */
export function projectWaits(events: VizEvent[]): Map<string, WaitFact> {
  const waits = new Map<string, WaitFact>()

  for (const event of events) {
    if (!ENDS_WAIT.has(event.kind) || !('coroutineId' in event)) continue
    waits.delete(event.coroutineId)

    if (event.kind === 'coroutine.suspended') {
      const suspended = event as CoroutineSuspendedEvent
      if (suspended.waitKind) {
        waits.set(suspended.coroutineId, {
          waitKind: suspended.waitKind,
          resourceId: suspended.resourceId ?? null,
          sinceSeq: suspended.seq,
          sinceTsNanos: suspended.tsNanos,
        })
      }
    }
  }

  return waits
}

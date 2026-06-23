/**
 * Pure client-side reducer: VizEvent[] -> ThreadActivity (Map<threadId, ThreadEvent[]>).
 *
 * Reimplements the backend ProjectionService thread-activity projection
 * (coroutine-viz-core/session/ProjectionService.kt) in TypeScript so the Threads
 * tab can derive its lanes from the replay `visibleEvents` slice instead of the
 * server `GET /sessions/{id}/threads` snapshot (ADR-017 / OQ-1).
 *
 * The output is the exact wire shape `buildThreadLanes` consumes: an object keyed
 * by `threadId.toString()`, each value an arrival-ordered array of ASSIGNED
 * ThreadEvents. The backend only records ASSIGNED entries here (RELEASED is not
 * emitted by ThreadAssigned), so this reducer mirrors that — every entry is
 * `eventType: 'ASSIGNED'`.
 *
 * Framework-free and side-effect-free (does not mutate its input) — safe inside
 * a useMemo.
 */

import type { ThreadActivity, ThreadAssignedEvent, ThreadEvent, VizEvent } from '@/types/api'

function isThreadAssigned(event: VizEvent): event is ThreadAssignedEvent {
  return event.kind === 'thread.assigned'
}

/**
 * Fold ThreadAssigned events into the wire-shape thread-activity map.
 *
 * @param events - VizEvents (typically the replay `visibleEvents` slice), in seq order
 * @returns Map<threadId, ThreadEvent[]> deep-equal to the threads-endpoint payload
 */
export function projectThreadActivity(events: VizEvent[]): ThreadActivity {
  const activity: ThreadActivity = {}

  for (const event of events) {
    if (!isThreadAssigned(event)) continue

    const key = event.threadId.toString()
    const threadEvent: ThreadEvent = {
      coroutineId: event.coroutineId,
      threadId: event.threadId,
      threadName: event.threadName,
      timestamp: event.tsNanos,
      eventType: 'ASSIGNED',
      dispatcherName: event.dispatcherName,
    }

    const existing = activity[key]
    if (existing) {
      existing.push(threadEvent)
    } else {
      activity[key] = [threadEvent]
    }
  }

  return activity
}

/**
 * Pure adapter from the REAL wire shape of GET /sessions/{id}/threads
 * (ThreadActivity = Map<threadId, ThreadEvent[]>, see ProjectionService.kt)
 * to the derived ThreadActivityResponse view model consumed by the
 * thread-lane / dispatcher-overview UI.
 *
 * This module is intentionally framework-free (no React imports) so the
 * derivation logic is unit-testable without renderHook.
 */

import type {
  DispatcherInfo,
  ThreadActivity,
  ThreadActivityResponse,
  ThreadLaneData,
  ThreadSegment,
} from '@/types/api'

/**
 * Derive thread lanes (segments + utilization) and dispatcher grouping from
 * the wire-shape thread activity map.
 *
 * - ASSIGNED opens a segment for its coroutineId; the next RELEASED with the
 *   same coroutineId on the same thread closes it. Unmatched RELEASED events
 *   are ignored. Segments left open keep `endNanos: null`. A duplicate
 *   ASSIGNED for a coroutineId with an open segment (e.g. an intermediate
 *   RELEASED dropped by the bounded EventStore) closes the stale segment at
 *   the new event's timestamp before opening the new one — never orphaning
 *   it (an orphan would inflate busy time until the global max timestamp).
 * - Utilization is computed against the global span across ALL events in the
 *   map; open segments are closed at the global max timestamp for the busy
 *   calculation. A zero span yields utilization 0 (never NaN).
 * - Lanes group into DispatcherInfo by each lane's resolved dispatcherName
 *   (the last event on the thread carrying a non-null dispatcherName);
 *   lanes without any dispatcherName group under 'Unknown'.
 */
export function buildThreadLanes(activity: ThreadActivity): ThreadActivityResponse {
  const entries = Object.entries(activity)

  // Global span across ALL events in the map.
  let minTimestamp = Number.POSITIVE_INFINITY
  let maxTimestamp = Number.NEGATIVE_INFINITY
  for (const [, events] of entries) {
    for (const event of events) {
      if (event.timestamp < minTimestamp) minTimestamp = event.timestamp
      if (event.timestamp > maxTimestamp) maxTimestamp = event.timestamp
    }
  }
  const hasEvents = Number.isFinite(minTimestamp) && Number.isFinite(maxTimestamp)
  const span = hasEvents ? maxTimestamp - minTimestamp : 0

  const threads: ThreadLaneData[] = entries.map(([threadIdStr, rawEvents]) => {
    // Backend already sorts by timestamp, but do not rely on it.
    const events = [...rawEvents].sort((a, b) => a.timestamp - b.timestamp)

    // Pair ASSIGNED/RELEASED into segments per coroutineId.
    const segments: ThreadSegment[] = []
    const openByCoroutine = new Map<string, ThreadSegment>()
    for (const event of events) {
      if (event.eventType === 'ASSIGNED') {
        // Duplicate ASSIGNED for a coroutine that already has an open
        // segment: close the stale segment at this event's timestamp instead
        // of orphaning it (IN-13 — an orphan stays open forever, inflating
        // utilization and making useActiveCoroutinesPerThread report the
        // coroutine as permanently active).
        const stale = openByCoroutine.get(event.coroutineId)
        if (stale) {
          stale.endNanos = event.timestamp
        }
        const segment: ThreadSegment = {
          coroutineId: event.coroutineId,
          coroutineName: null,
          startNanos: event.timestamp,
          endNanos: null,
          state: 'ACTIVE',
        }
        segments.push(segment)
        openByCoroutine.set(event.coroutineId, segment)
      } else if (event.eventType === 'RELEASED') {
        const open = openByCoroutine.get(event.coroutineId)
        if (open) {
          open.endNanos = event.timestamp
          openByCoroutine.delete(event.coroutineId)
        }
        // RELEASED with no open segment for that coroutineId is ignored.
      }
    }

    // Busy time: open segments are closed at the global max timestamp.
    const busy = segments.reduce(
      (sum, segment) => sum + ((segment.endNanos ?? maxTimestamp) - segment.startNanos),
      0,
    )
    const utilization = span > 0 ? Math.min(busy / span, 1) : 0

    // dispatcherName: last event on this thread carrying a non-null value.
    let dispatcherName: string | null = null
    for (const event of events) {
      if (event.dispatcherName != null) dispatcherName = event.dispatcherName
    }

    return {
      threadId: Number(threadIdStr),
      threadName: events[0]?.threadName ?? `Thread ${threadIdStr}`,
      dispatcherId: dispatcherName,
      dispatcherName,
      segments,
      utilization,
    }
  })

  // Group lanes by resolved dispatcher name (null -> 'Unknown'),
  // preserving first-occurrence order.
  const groups = new Map<string, number[]>()
  for (const lane of threads) {
    const name = lane.dispatcherName ?? 'Unknown'
    const ids = groups.get(name) ?? []
    ids.push(lane.threadId)
    groups.set(name, ids)
  }

  const dispatcherInfo: DispatcherInfo[] = Array.from(groups.entries()).map(
    ([name, threadIds]) => ({
      id: name,
      name,
      threadIds: [...threadIds].sort((a, b) => a - b),
      queueDepth: null,
    }),
  )

  return { threads, dispatcherInfo }
}

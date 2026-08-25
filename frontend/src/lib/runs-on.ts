import type { ThreadActivity } from '@/types/api'

export interface RunsOn {
  threadName: string
  /** Null when the backend reported the assignment without naming a dispatcher. */
  dispatcherName: string | null
}

/**
 * Which thread and dispatcher a coroutine is currently on.
 *
 * The per-coroutine half of what the Threads tab used to answer. It reads
 * `/sessions/{id}/threads` rather than the coroutine timeline: the timeline
 * projection is source-only by design (D-02) and carries no per-event
 * `threadName`/`dispatcherName`, so the inspector could never fill from it and
 * said "not reported" against a live backend that had the data all along.
 *
 * Pure, so it works identically over the server snapshot and over the
 * client-side projection replay feeds it.
 */
export function resolveRunsOn(
  activity: ThreadActivity | undefined,
  coroutineId: string,
): RunsOn | null {
  if (!activity) return null

  let latest: { timestamp: number; threadName: string; dispatcherName: string | null } | null = null

  for (const events of Object.values(activity)) {
    for (const event of events) {
      if (event.coroutineId !== coroutineId) continue
      // Track RELEASED too, and only decide at the end: a release is what says
      // the coroutine LEFT a thread, so reading assignments alone would keep
      // reporting a thread it is no longer on.
      if (latest !== null && event.timestamp <= latest.timestamp) continue
      latest = {
        timestamp: event.timestamp,
        threadName: event.eventType === 'ASSIGNED' ? event.threadName : '',
        dispatcherName: event.eventType === 'ASSIGNED' ? event.dispatcherName ?? null : null,
      }
    }
  }

  if (latest === null || latest.threadName === '') return null
  return { threadName: latest.threadName, dispatcherName: latest.dispatcherName }
}

import type { ThreadActivity } from '@/types/api'
import { plural } from './evidence-summary'

/** One thread name's activity (triage T-5). */
export interface ThreadGroup {
  name: string
  /** Distinct JVM threads seen under this name (pool threads come and go). */
  jvmThreads: number
  coroutinesSeen: number
  assignments: number
  /** The last ASSIGNED event's timestamp, on the app's clock. */
  lastAssignedNanos: number | null
}

/**
 * Thread activity grouped by thread name, busiest first. One row per name, not
 * one per event: a real app's thread list was thousands of rows (spec Problem 4).
 */
export function groupThreadsByName(activity: ThreadActivity | undefined): ThreadGroup[] {
  const groups = new Map<string, { ids: Set<string>; coroutines: Set<string>; assignments: number; last: number | null }>()
  for (const [threadId, events] of Object.entries(activity ?? {})) {
    for (const event of events) {
      const name = event.threadName || `Thread ${threadId}`
      let g = groups.get(name)
      if (!g) {
        g = { ids: new Set(), coroutines: new Set(), assignments: 0, last: null }
        groups.set(name, g)
      }
      g.ids.add(threadId)
      g.coroutines.add(event.coroutineId)
      if (event.eventType === 'ASSIGNED') {
        g.assignments++
        g.last = g.last === null ? event.timestamp : Math.max(g.last, event.timestamp)
      }
    }
  }
  return [...groups.entries()]
    .map(([name, g]) => ({
      name,
      jvmThreads: g.ids.size,
      coroutinesSeen: g.coroutines.size,
      assignments: g.assignments,
      lastAssignedNanos: g.last,
    }))
    .sort((a, b) => b.assignments - a.assignments || a.name.localeCompare(b.name))
}

/** "18 names (89 JVM threads)", or "none captured". */
export function threadSummary(groups: readonly ThreadGroup[]): string {
  if (groups.length === 0) return 'none captured'
  const jvm = groups.reduce((n, g) => n + g.jvmThreads, 0)
  return `${plural(groups.length, 'name')} (${plural(jvm, 'JVM thread')})`
}

/** The first thread event's timestamp, or null when there is none. */
export function earliestThreadEvent(activity: ThreadActivity | undefined): number | null {
  let first: number | null = null
  for (const events of Object.values(activity ?? {})) {
    for (const event of events) {
      if (first === null || event.timestamp < first) first = event.timestamp
    }
  }
  return first
}

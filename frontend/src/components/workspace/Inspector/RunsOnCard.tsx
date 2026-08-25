import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { CoroutineTimeline } from '@/types/api'

/**
 * Which thread and dispatcher this coroutine is on — the per-coroutine half of
 * what the Threads tab used to answer (the aggregate half is the metric tiles).
 *
 * Reads the NEWEST event that reports each field, not the first: a coroutine
 * that moved from Main to IO runs on IO now, and the creation event would
 * report a thread it has long since left.
 */
export function RunsOnCard({ timeline }: { timeline: CoroutineTimeline | undefined }) {
  const events = timeline?.events ?? []
  const thread = findLatest(events, e => e.threadName)
  const dispatcher = findLatest(events, e => e.dispatcherName)

  return (
    <InspectorCard title="Runs on" testId="runs-on-card">
      <InspectorRow label="Thread" value={thread ?? <NotReported />} />
      <InspectorRow label="Dispatcher" value={dispatcher ?? <NotReported />} />
    </InspectorCard>
  )
}

function findLatest<T>(
  events: readonly T[],
  pick: (event: T) => string | null | undefined,
): string | undefined {
  for (let i = events.length - 1; i >= 0; i--) {
    const event = events[i]
    if (!event) continue
    const value = pick(event)
    if (value) return value
  }
  return undefined
}

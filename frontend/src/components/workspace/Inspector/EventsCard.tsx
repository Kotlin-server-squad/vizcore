import { formatRelativeTime } from '@/lib/utils'
import { InspectorCard } from './InspectorCard'
import type { CoroutineTimeline } from '@/types/api'

/** How many of the coroutine's most recent events the card lists. */
const RECENT = 5

/**
 * The tail of this coroutine's own event history.
 *
 * Deliberately a summary, not a list: the full, filterable event view is the
 * canvas drawer. This card answers "what just happened to this one" at a
 * glance, which is why it sits last in the D-7 order.
 */
export function EventsCard({ timeline }: { timeline: CoroutineTimeline | undefined }) {
  const events = timeline?.events ?? []
  const recent = events.slice(-RECENT).reverse()
  // `tsNanos` is an ABSOLUTE timestamp. Formatting it as a duration reads as
  // "891603.96s", which is a wall-clock epoch offset dressed up as an elapsed
  // time. Every row is shown relative to the coroutine's first event instead.
  const originNanos = events.length > 0 ? events[0]!.tsNanos : 0

  return (
    <InspectorCard title="Events" testId="events-card">
      <p className="mb-2 text-sm text-default-500">
        {events.length === 1 ? '1 event' : `${events.length} events`}
      </p>
      {recent.length > 0 && (
        <ul className="space-y-1">
          {recent.map(event => (
            <li key={event.seq} className="flex items-baseline justify-between gap-2 text-xs">
              <span className="truncate font-mono text-default-700">{event.kind}</span>
              <span className="shrink-0 text-default-400">
                {formatRelativeTime(event.tsNanos, originNanos)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </InspectorCard>
  )
}

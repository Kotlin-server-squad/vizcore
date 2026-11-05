import { useMemo } from 'react'
import { EventsList } from '../EventsList'
import type { VizEvent } from '@/types/api'

interface EventsPanelProps {
  /** The session's events, from the live snapshot or the replay cursor. */
  events: VizEvent[]
  /** Scopes the list. Null = the whole session. */
  selectedCoroutine: { id: string; label: string | null } | null
}

/**
 * The Events tab (spec S-4): the raw event stream, scoped to what is selected
 * in the tree. It narrows to the selected coroutine rather than making the
 * developer search a session-wide list for the id they just clicked.
 */
export function EventsPanel({ events, selectedCoroutine }: EventsPanelProps) {
  const scoped = useMemo(() => {
    if (!selectedCoroutine) return events
    // Events with no coroutineId are session-scoped and belong to the
    // unselected view — attributing them to a coroutine would be a fabrication.
    return events.filter(
      e => (e as { coroutineId?: string }).coroutineId === selectedCoroutine.id,
    )
  }, [events, selectedCoroutine])

  const who = selectedCoroutine
    ? `${selectedCoroutine.label ?? selectedCoroutine.id}`
    : null

  return (
    <div className="space-y-3">
      <p data-testid="events-scope" className="text-xs text-default-500">
        {who ? (
          <>
            Events for <span className="font-mono text-default-700">{who}</span> · {scoped.length}
          </>
        ) : (
          `All ${events.length} events in this session`
        )}
      </p>
      <EventsList events={scoped} />
    </div>
  )
}

import { useMemo, useRef } from 'react'
import { EventsList } from '../EventsList'
import { eventConcerns, type Selection } from '@/lib/selection'
import type { VizEvent } from '@/types/api'

interface EventsPanelProps {
  /** The session's events, from the live snapshot or the replay cursor. */
  events: VizEvent[]
  /** The page's one selection (S-2). Null = the whole session. */
  selection: Selection | null
  /** What to call it: the coroutine's or the resource's name. */
  selectionLabel: string | null
  /** What a row is about; with `onSelect`, rows become buttons (live view only, PD-01). */
  subjectOf?: (event: VizEvent) => Selection | null
  onSelect?: (selection: Selection) => void
  /** Clears the selection, back to every event. */
  onShowAll?: () => void
  /** Resource id -> name, so a row about a channel can name it. */
  resourceNames?: ReadonlyMap<string, string>
  /** With a group selected (S-12): its members, whose events are the group's. */
  groupMembers?: ReadonlySet<string>
}

/**
 * The Events tab (spec S-4): the raw event stream, scoped to the page's
 * selection — a coroutine's events, the lock and permit events about it
 * included, or a resource's. A row selects what it is about (S-2).
 */
export function EventsPanel({
  events,
  selection,
  selectionLabel,
  subjectOf,
  onSelect,
  onShowAll,
  resourceNames,
  groupMembers,
}: EventsPanelProps) {
  const scoped = useMemo(
    () => (selection ? events.filter(e => eventConcerns(e, selection, groupMembers)) : events),
    [events, selection, groupMembers],
  )
  const who = selection ? (selectionLabel ?? selection.id) : null
  // "All events" disappears once pressed: keep focus in the tab, on the scope line.
  const scopeRef = useRef<HTMLParagraphElement>(null)

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-x-2">
        <p ref={scopeRef} tabIndex={-1} data-testid="events-scope" className="text-xs text-default-500">
          {who ? (
            <>
              {selection?.kind === 'resource' ? 'Events about ' : 'Events for '}
              <span className="font-mono text-default-700">{who}</span> · {scoped.length}
            </>
          ) : (
            `All ${events.length} events in this session`
          )}
        </p>
        {selection && onShowAll && (
          <button
            type="button"
            onClick={() => {
              onShowAll()
              scopeRef.current?.focus()
            }}
            className="ml-auto text-xs text-primary hover:underline"
          >
            All events
          </button>
        )}
      </div>
      <EventsList events={scoped} subjectOf={subjectOf} onSelect={onSelect} resourceNames={resourceNames} />
    </div>
  )
}

import { useMemo, useState } from 'react'
import { Card, CardBody } from '@heroui/react'
import { FiChevronDown, FiChevronRight } from 'react-icons/fi'
import { EventsList } from '../EventsList'
import type { VizEvent } from '@/types/api'

interface EventsDrawerProps {
  /** The session's events, from the live snapshot or the replay cursor. */
  events: VizEvent[]
  /** Scopes the drawer. Null = the whole session. */
  selectedCoroutine: { id: string; label: string | null } | null
}

/**
 * The raw event stream, scoped to what is selected on the canvas.
 *
 * This was a tab, which was the wrong shape: reading a coroutine's events means
 * holding its position in the tree, and a tab takes the tree away to show them.
 * So it is a disclosure beneath the canvas — the tree stays on screen — and it
 * narrows to the selected coroutine rather than making the developer search a
 * session-wide list for the id they just clicked.
 *
 * Collapsed by default. It is the deepest level of detail in the workspace and
 * should cost a deliberate click.
 */
export function EventsDrawer({ events, selectedCoroutine }: EventsDrawerProps) {
  const [open, setOpen] = useState(false)

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
    <div className="space-y-2">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(prev => !prev)}
        className="flex items-center gap-2 rounded-medium px-2 py-1 text-sm text-default-600 transition-colors hover:bg-default-100"
      >
        {open ? (
          <FiChevronDown className="h-4 w-4" aria-hidden="true" />
        ) : (
          <FiChevronRight className="h-4 w-4" aria-hidden="true" />
        )}
        <span>Events ({scoped.length})</span>
      </button>

      {open && (
        <Card data-testid="events-drawer-body">
          <CardBody className="space-y-3">
            <p data-testid="events-drawer-scope" className="text-xs text-default-500">
              {who ? (
                <>
                  Events for <span className="font-mono text-default-700">{who}</span>
                </>
              ) : (
                `All ${events.length} events in this session`
              )}
            </p>
            <EventsList events={scoped} />
          </CardBody>
        </Card>
      )}
    </div>
  )
}

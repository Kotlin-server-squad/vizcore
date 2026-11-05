import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { VizEvent } from '@/types/api'

vi.mock('../EventsList', () => ({
  EventsList: ({ events, onSelect }: { events: VizEvent[]; onSelect?: unknown }) => (
    <div data-testid="events-list" data-count={events.length} data-selectable={String(!!onSelect)} />
  ),
}))

import { EventsPanel } from './EventsPanel'

function event(seq: number, coroutineId?: string): VizEvent {
  return {
    sessionId: 's-1',
    seq,
    tsNanos: seq * 1_000,
    kind: 'coroutine.created',
    ...(coroutineId
      ? { coroutineId, jobId: `job-${coroutineId}`, parentCoroutineId: null, scopeId: 'scope-1', label: `label-${seq}` }
      : {}),
  } as VizEvent
}

const lockEvent = { sessionId: 's-1', seq: 5, tsNanos: 5, kind: 'MutexLockAcquired', mutexId: 'm-1', acquirerId: 'c-1' } as unknown as VizEvent
const EVENTS: VizEvent[] = [event(1, 'c-1'), event(2, 'c-2'), event(3, 'c-1'), event(4), lockEvent]

describe('EventsPanel', () => {
  it('lists the whole session when nothing is selected', () => {
    render(<EventsPanel events={EVENTS} selection={null} selectionLabel={null} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('All 5 events in this session')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '5')
    expect(screen.queryByRole('button', { name: 'All events' })).toBeNull()
  })

  it('narrows to the selected coroutine, its lock and permit events included', () => {
    render(<EventsPanel events={EVENTS} selection={{ kind: 'coroutine', id: 'c-1' }} selectionLabel="fetchUser" />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for fetchUser · 3')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '3')
  })

  it('narrows to a selected resource', () => {
    render(
      <EventsPanel events={EVENTS} selection={{ kind: 'resource', resource: 'mutex', id: 'm-1' }} selectionLabel="inventory-lock" />,
    )
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about inventory-lock · 1')
  })

  it('falls back to the id when the selection has no label', () => {
    render(<EventsPanel events={EVENTS} selection={{ kind: 'coroutine', id: 'c-1' }} selectionLabel={null} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for c-1')
  })

  it('goes back to all events, clearing the selection', async () => {
    const onShowAll = vi.fn()
    render(<EventsPanel events={EVENTS} selection={{ kind: 'coroutine', id: 'c-1' }} selectionLabel="c-1" onShowAll={onShowAll} />)
    await userEvent.click(screen.getByRole('button', { name: 'All events' }))
    expect(onShowAll).toHaveBeenCalled()
  })

  it('makes rows selectable only when the page offers selection', () => {
    const { rerender } = render(<EventsPanel events={EVENTS} selection={null} selectionLabel={null} />)
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-selectable', 'false')
    rerender(<EventsPanel events={EVENTS} selection={null} selectionLabel={null} subjectOf={() => null} onSelect={vi.fn()} />)
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-selectable', 'true')
  })
})

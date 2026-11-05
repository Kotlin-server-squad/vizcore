import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { VizEvent } from '@/types/api'

vi.mock('../EventsList', () => ({
  EventsList: ({ events }: { events: VizEvent[] }) => <div data-testid="events-list" data-count={events.length} />,
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

const EVENTS: VizEvent[] = [event(1, 'c-1'), event(2, 'c-2'), event(3, 'c-1'), event(4)]

describe('EventsPanel', () => {
  it('lists the whole session when nothing is selected', () => {
    render(<EventsPanel events={EVENTS} selectedCoroutine={null} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('All 4 events in this session')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '4')
  })

  it('narrows to the selected coroutine and says whose events these are', () => {
    render(<EventsPanel events={EVENTS} selectedCoroutine={{ id: 'c-1', label: 'fetchUser' }} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for fetchUser · 2')
    expect(screen.getByTestId('events-list')).toHaveAttribute('data-count', '2')
  })

  it('falls back to the coroutine id when it has no label', () => {
    render(<EventsPanel events={EVENTS} selectedCoroutine={{ id: 'c-1', label: null }} />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for c-1')
  })
})

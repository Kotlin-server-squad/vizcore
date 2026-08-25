import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { VizEvent } from '@/types/api'
import { EventsDrawer } from './EventsDrawer'

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

const events: VizEvent[] = [
  event(1, 'c-1'),
  event(2, 'c-2'),
  event(3, 'c-1'),
  event(4), // session-scoped: no coroutineId
]

describe('EventsDrawer', () => {
  it('is collapsed by default and names the count it would show', () => {
    render(<EventsDrawer events={events} selectedCoroutine={null} />)

    expect(screen.getByRole('button', { name: /events \(4\)/i })).toBeInTheDocument()
    // Nothing from EventsList is mounted while collapsed.
    expect(screen.queryByPlaceholderText(/filter events/i)).toBeNull()
  })

  it('lists the whole session when nothing is selected', async () => {
    render(<EventsDrawer events={events} selectedCoroutine={null} />)

    await userEvent.click(screen.getByRole('button', { name: /events \(4\)/i }))

    expect(screen.getByTestId('events-drawer-body')).toBeInTheDocument()
    expect(
      within(screen.getByTestId('events-drawer-scope')).getByText(/all 4 events in this session/i),
    ).toBeInTheDocument()
  })

  it('narrows to the selected coroutine and says whose events these are', async () => {
    render(
      <EventsDrawer
        events={events}
        selectedCoroutine={{ id: 'c-1', label: 'UserService.register' }}
      />,
    )

    // The count in the toggle is the SCOPED count — the drawer must not promise
    // four events and then show two.
    const toggle = screen.getByRole('button', { name: /events \(2\)/i })
    await userEvent.click(toggle)

    const scope = screen.getByTestId('events-drawer-scope')
    expect(within(scope).getByText('UserService.register')).toBeInTheDocument()
  })

  it('falls back to the coroutine id when it has no label', async () => {
    render(<EventsDrawer events={events} selectedCoroutine={{ id: 'c-2', label: null }} />)

    await userEvent.click(screen.getByRole('button', { name: /events \(1\)/i }))

    const scope = screen.getByTestId('events-drawer-scope')
    expect(within(scope).getByText('c-2')).toBeInTheDocument()
  })

  it('collapses again on a second click', async () => {
    render(<EventsDrawer events={events} selectedCoroutine={null} />)

    const toggle = screen.getByRole('button', { name: /events \(4\)/i })
    await userEvent.click(toggle)
    expect(screen.getByTestId('events-drawer-body')).toBeInTheDocument()

    await userEvent.click(toggle)
    expect(screen.queryByTestId('events-drawer-body')).toBeNull()
  })

  it('stays open across a selection change, and re-scopes to the new coroutine', async () => {
    const { rerender } = render(<EventsDrawer events={events} selectedCoroutine={null} />)

    await userEvent.click(screen.getByRole('button', { name: /events \(4\)/i }))

    rerender(
      <EventsDrawer events={events} selectedCoroutine={{ id: 'c-2', label: 'PaymentClient' }} />,
    )

    // Selecting a node in the canvas must not close a drawer the user opened.
    expect(screen.getByTestId('events-drawer-body')).toBeInTheDocument()
    const scope = screen.getByTestId('events-drawer-scope')
    expect(within(scope).getByText('PaymentClient')).toBeInTheDocument()
  })
})

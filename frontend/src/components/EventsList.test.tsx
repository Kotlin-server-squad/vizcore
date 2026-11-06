import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { VizEvent } from '@/types/api'
import { eventSubject } from '@/lib/selection'
import { EventsList } from './EventsList'

vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))

const acquired = {
  sessionId: 's', seq: 7, tsNanos: 7, kind: 'MutexLockAcquired',
  mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-2', acquirerLabel: 'order-2', waitDurationNanos: 0,
} as unknown as VizEvent
const created = {
  sessionId: 's', seq: 6, tsNanos: 6, kind: 'MutexCreated', mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null,
} as unknown as VizEvent
const closed = { sessionId: 's', seq: 9, tsNanos: 9, kind: 'ChannelClosed', channelId: 'ch-1', cause: null } as unknown as VizEvent
const deadlock = { sessionId: 's', seq: 8, tsNanos: 8, kind: 'DeadlockDetected', involvedCoroutines: [] } as unknown as VizEvent

describe('EventsList', () => {
  it('names a lock event by who and what', () => {
    render(<EventsList events={[acquired]} />)
    expect(screen.getByText('order-2 · inventory-lock')).toBeInTheDocument()
  })

  it('names a channel-only event by the channel\'s name', () => {
    render(<EventsList events={[closed]} resourceNames={new Map([['ch-1', 'orders']])} />)
    expect(screen.getByText('orders')).toBeInTheDocument()
  })

  it('makes each row about something selectable a button that selects it (S-2)', async () => {
    const onSelect = vi.fn()
    render(
      <EventsList
        events={[created, acquired, deadlock, closed]}
        subjectOf={eventSubject}
        onSelect={onSelect}
        resourceNames={new Map([['ch-1', 'orders']])}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Select order-2 · inventory-lock (event #7)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Select inventory-lock (event #6)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Select orders (event #9)' }))
    expect(onSelect.mock.calls).toEqual([
      [{ kind: 'coroutine', id: 'c-2' }],
      [{ kind: 'resource', resource: 'mutex', id: 'm-1' }],
      [{ kind: 'resource', resource: 'channel', id: 'ch-1' }],
    ])
    // A deadlock is about no one thing: not a button.
    expect(screen.getAllByRole('button', { name: /^Select / })).toHaveLength(3)
  })

  it('keeps rows presentational without onSelect', () => {
    render(<EventsList events={[acquired]} />)
    expect(screen.queryByRole('button', { name: /^Select / })).toBeNull()
  })
})

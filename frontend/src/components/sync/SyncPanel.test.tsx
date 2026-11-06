import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SyncPanel } from './SyncPanel'
import { MutexStateIndicator } from './MutexStateIndicator'
import { SemaphoreGauge } from './SemaphoreGauge'
import { DeadlockWarning } from './DeadlockWarning'
import { WaitQueueList } from './WaitQueueList'
import { apiClient } from '@/lib/api-client'
import type { VizEvent, DeadlockDetected } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getSessionEvents: vi.fn(),
  },
}))

const mockedApiClient = vi.mocked(apiClient)

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

function makeSyncEvent(kind: string, seq: number, overrides: Record<string, unknown> = {}): VizEvent {
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: seq * 1_000_000,
    kind: kind as VizEvent['kind'],
    ...overrides,
  } as unknown as VizEvent
}

describe('SyncPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders empty state when no sync events exist', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])

    render(<SyncPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const emptyPanel = await screen.findByTestId('sync-empty')
    expect(emptyPanel).toBeInTheDocument()
    expect(screen.getByText('No Sync Primitives')).toBeInTheDocument()
  })

  it('renders mutex section when mutex events are present', async () => {
    const events = [
      makeSyncEvent('MutexCreated', 1, {
        mutexId: 'mx-1',
        mutexLabel: 'TestMutex',
        ownerCoroutineId: null,
      }),
      makeSyncEvent('MutexLockAcquired', 2, {
        mutexId: 'mx-1',
        mutexLabel: 'TestMutex',
        acquirerId: 'c1',
        acquirerLabel: 'Worker-1',
        waitDurationNanos: 0,
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<SyncPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const panel = await screen.findByTestId('sync-panel')
    expect(panel).toBeInTheDocument()
    expect(screen.getByText(/Mutexes/)).toBeInTheDocument()
    expect(screen.getByText('TestMutex')).toBeInTheDocument()
  })

  it('renders semaphore section when semaphore events are present', async () => {
    const events = [
      makeSyncEvent('SemaphoreCreated', 1, {
        semaphoreId: 'sem-1',
        semaphoreLabel: 'Pool',
        totalPermits: 5,
      }),
      makeSyncEvent('SemaphorePermitAcquired', 2, {
        semaphoreId: 'sem-1',
        semaphoreLabel: 'Pool',
        acquirerId: 'c1',
        acquirerLabel: 'Worker-1',
        remainingPermits: 4,
        waitDurationNanos: 0,
      }),
    ]

    mockedApiClient.getSessionEvents.mockResolvedValue(events)

    render(<SyncPanel sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    const panel = await screen.findByTestId('sync-panel')
    expect(panel).toBeInTheDocument()
    expect(screen.getByText(/Semaphores/)).toBeInTheDocument()
    expect(screen.getByText('Pool')).toBeInTheDocument()
  })
})

describe('MutexStateIndicator', () => {
  it('renders locked state', () => {
    render(
      <MutexStateIndicator
        mutex={{
          mutexId: 'mx-1',
          label: 'MyMutex',
          isLocked: true,
          currentHolder: 'c1',
          currentHolderLabel: 'Worker-1',
          waitQueue: [],
          contentionCount: 3,
          lockAcquisitions: 5,
        }}
      />,
    )

    expect(screen.getByTestId('lock-status')).toHaveTextContent('Locked')
    expect(screen.getByText('Worker-1')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('5')).toBeInTheDocument()

    // Holding a lock is normal work, not an error (spec Problem 8): neutral, never red.
    const chip = screen.getByTestId('lock-status')
    expect(chip.className).not.toMatch(/danger/)
    expect(chip.className).toMatch(/default/)
  })

  it('renders unlocked state', () => {
    render(
      <MutexStateIndicator
        mutex={{
          mutexId: 'mx-1',
          label: 'FreeMutex',
          isLocked: false,
          currentHolder: null,
          currentHolderLabel: null,
          waitQueue: [],
          contentionCount: 0,
          lockAcquisitions: 0,
        }}
      />,
    )

    expect(screen.getByTestId('lock-status')).toHaveTextContent('Unlocked')
    expect(screen.getByText('None')).toBeInTheDocument()
  })
})

describe('SemaphoreGauge', () => {
  it('renders gauge with available/total permits', () => {
    render(
      <SemaphoreGauge
        semaphore={{
          semaphoreId: 'sem-1',
          label: 'TestSemaphore',
          totalPermits: 5,
          availablePermits: 3,
          activeHolders: [],
          unknownHolderPermits: 0,
          unsettled: false,
          availableKnown: true,
          waitQueue: [],
        }}
      />,
    )

    expect(screen.getByTestId('semaphore-gauge')).toBeInTheDocument()
    expect(screen.getByText('3 / 5 available')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument() // used permits
  })
})

describe('DeadlockWarning', () => {
  it('renders nothing when no deadlocks or warnings', () => {
    const { container } = render(<DeadlockWarning deadlocks={[]} warnings={[]} />)
    expect(container.firstChild).toBeNull()
  })

  it('renders deadlock detection alert', () => {
    const deadlocks: DeadlockDetected[] = [
      {
        kind: 'DeadlockDetected',
        sessionId: 's1',
        seq: 1,
        tsNanos: 1000000,
        involvedCoroutines: ['c1', 'c2'],
        involvedCoroutineLabels: ['Worker-A', 'Worker-B'],
        involvedMutexes: ['mx-1', 'mx-2'],
        involvedMutexLabels: ['Lock-1', 'Lock-2'],
        waitGraph: { c1: 'mx-2', c2: 'mx-1' },
        holdGraph: { 'mx-1': 'c1', 'mx-2': 'c2' },
        cycleDescription: 'c1 holds mx-1, waits for mx-2; c2 holds mx-2, waits for mx-1',
      },
    ]

    render(<DeadlockWarning deadlocks={deadlocks} warnings={[]} />)
    expect(screen.getByTestId('deadlock-warning')).toBeInTheDocument()
    expect(screen.getByText('Deadlock Detected')).toBeInTheDocument()
    expect(screen.getByTestId('deadlock-description')).toBeInTheDocument()
  })
})

describe('WaitQueueList', () => {
  it('renders empty message when queue is empty', () => {
    render(<WaitQueueList queue={[]} />)
    expect(screen.getByTestId('wait-queue-empty')).toBeInTheDocument()
  })

  it('renders entries in order', () => {
    const queue = [
      { id: 'c1', label: 'First' },
      { id: 'c2', label: 'Second' },
    ]

    render(<WaitQueueList queue={queue} />)
    expect(screen.getByTestId('wait-queue-list')).toBeInTheDocument()
    expect(screen.getAllByTestId('wait-queue-entry')).toHaveLength(2)
    expect(screen.getByText('First')).toBeInTheDocument()
    expect(screen.getByText('Second')).toBeInTheDocument()
  })
})

describe('SyncPanel — live facts and one selection (S-2, S-6)', () => {
  const lockEvents = [
    makeSyncEvent('MutexCreated', 1, { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
    makeSyncEvent('MutexLockAcquired', 2, { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
    makeSyncEvent('MutexQueueChanged', 3, { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-2'], waitingLabels: ['order-2'] }),
  ]
  const permits = (fields: Record<string, unknown>) => [
    makeSyncEvent('SemaphoreCreated', 4, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 }),
    makeSyncEvent('SemaphoreStateChanged', 5, {
      semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3,
      activeHolders: ['c-3', 'c-4'], activeHolderLabels: ['order-3', 'order-4'], waitingCoroutines: [], waitingLabels: [],
      ...fields,
    }),
  ]
  const anyone = (select = vi.fn()) => ({ select, canSelect: () => true })

  beforeEach(() => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
  })

  it('reads the events it is given, so it follows the live stream', () => {
    render(<SyncPanel sessionId="session-1" events={lockEvents} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('order-1')
  })

  it('lists a semaphore\'s holders with their permits', () => {
    render(<SyncPanel sessionId="session-1" events={permits({ availablePermits: 0, activeHolderPermits: [2, 1] })} />, {
      wrapper: createWrapper(),
    })
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('order-3 (2 permits)')
    expect(held).toHaveTextContent('order-4')
    expect(held).not.toHaveTextContent('holder unknown')
  })

  it('says "holder unknown" when a permit came back from a coroutine that held none (S-6, S-7)', () => {
    // Two permits listed, one in use: one of the two was released by somebody else, and which is unknown.
    render(
      <SyncPanel sessionId="session-1" events={permits({ availablePermits: 2, activeHolderPermits: [1, 1], unknownHolderPermits: 1 })} />,
      { wrapper: createWrapper() },
    )
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('holder unknown')
    expect(held).toHaveTextContent('1 of 3 permits in use')
    expect(held).toHaveTextContent('last recorded holders order-3, order-4')
  })

  it('says the holder is unknown when permits are in use with no holder recorded', () => {
    // A semaphore created with permits already taken (acquiredPermits) lists no holder for them.
    render(
      <SyncPanel sessionId="session-1" events={permits({ availablePermits: 0, activeHolders: [], activeHolderLabels: [] })} />,
      { wrapper: createWrapper() },
    )
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('holder unknown')
    expect(held).toHaveTextContent('3 of 3 permits in use; no holder was recorded')
    expect(held).not.toHaveTextContent('none')
  })

  it('names the recorded holders and counts the permits no holder was recorded for', () => {
    render(
      <SyncPanel sessionId="session-1" events={permits({ availablePermits: 1, activeHolders: ['c-3'], activeHolderLabels: ['order-3'], activeHolderPermits: [1] })} />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('semaphore-holders')).toHaveTextContent('order-3, and 1 permit with no recorded holder')
  })

  it('after an acquire or release since the last snapshot, says "holder unknown" and counts nothing per holder', () => {
    // A acquires twice between snapshots: two permits in use, both acquisitions name order-3; no
    // "no recorded holder" count is guessed.
    const acquire = (seq: number, remainingPermits: number) =>
      makeSyncEvent('SemaphorePermitAcquired', seq, {
        semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', acquirerId: 'c-3', acquirerLabel: 'order-3', remainingPermits, waitDurationNanos: 0,
      })
    const events = [
      makeSyncEvent('SemaphoreCreated', 1, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 }),
      makeSyncEvent('SemaphoreStateChanged', 2, {
        semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3, availablePermits: 3,
        activeHolders: [], activeHolderLabels: [], waitingCoroutines: [], waitingLabels: [],
      }),
      acquire(3, 2),
      acquire(4, 1),
    ]
    render(<SyncPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('holder unknown — 2 of 3 permits in use; last recorded holders order-3')
    expect(held).not.toHaveTextContent('no recorded holder')
    expect(held).not.toHaveTextContent('permits)')
  })

  it('with no total in the retained events, says the permits in use are unknown rather than "none"', () => {
    const events = [
      makeSyncEvent('SemaphorePermitAcquired', 1, {
        semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', acquirerId: 'c-3', acquirerLabel: 'order-3', remainingPermits: 1, waitDurationNanos: 0,
      }),
    ]
    render(<SyncPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    const gauge = screen.getByTestId('semaphore-gauge')
    expect(gauge).toHaveTextContent('total unknown')
    // No negative count: the used figure is unknown.
    expect(gauge).toHaveTextContent('?used')
    expect(gauge).not.toHaveTextContent(/-\d+used/)
    expect(screen.getByTestId('semaphore-holders')).toHaveTextContent('holder unknown')
    expect(screen.getByTestId('semaphore-holders')).not.toHaveTextContent('none')
  })

  it('keeps focus on a waiter\'s name when the waiter ahead of it leaves the queue', async () => {
    const queue = (ids: string[], seq: number) =>
      makeSyncEvent('MutexQueueChanged', seq, { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ids, waitingLabels: ids })
    const base = lockEvents.slice(0, 2)
    const wrapper = createWrapper()
    const picker = anyone()
    const { rerender } = render(<SyncPanel sessionId="session-1" events={[...base, queue(['c-2', 'c-3'], 3)]} picker={picker} />, { wrapper })
    screen.getByRole('button', { name: 'c-3' }).focus()
    rerender(<SyncPanel sessionId="session-1" events={[...base, queue(['c-3'], 4)]} picker={picker} />)
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'c-3' }))
  })

  it('right after creation, says availability is not known yet (permits may be taken at creation)', () => {
    const events = [makeSyncEvent('SemaphoreCreated', 1, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 })]
    render(<SyncPanel sessionId="session-1" events={events} />, { wrapper: createWrapper() })
    const gauge = screen.getByTestId('semaphore-gauge')
    expect(gauge).toHaveTextContent('3 permits · availability unknown')
    expect(gauge).toHaveTextContent('?used')
    expect(screen.getByTestId('semaphore-holders')).toHaveTextContent('holder unknown')
    expect(screen.getByTestId('semaphore-holders')).not.toHaveTextContent('none')
  })

  it('selects a holder or a waiter by name', async () => {
    const select = vi.fn()
    render(<SyncPanel sessionId="session-1" events={lockEvents} picker={anyone(select)} />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'order-1' }))
    await userEvent.click(screen.getByRole('button', { name: 'order-2' }))
    expect(select.mock.calls).toEqual([['c-1'], ['c-2']])
  })

  it('offers no button for a coroutine the page cannot select, and names no coroutine for "unknown"', () => {
    const events = [
      makeSyncEvent('MutexCreated', 1, { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
      makeSyncEvent('MutexLockAcquired', 2, { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'unknown-42', acquirerLabel: null, waitDurationNanos: 0 }),
      makeSyncEvent('MutexQueueChanged', 3, { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-gone'], waitingLabels: ['gone'] }),
    ]
    render(
      <SyncPanel sessionId="session-1" events={events} picker={{ select: vi.fn(), canSelect: id => id === 'c-1' }} />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('outside any coroutine')
    expect(screen.getByText('gone')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'gone' })).toBeNull()
  })

  it('scrolls the selected resource into view, again when it is selected again', () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    const wrapper = createWrapper()
    const { rerender } = render(
      <SyncPanel sessionId="session-1" events={lockEvents} selectedResourceId="m-1" revealKey={{}} />,
      { wrapper },
    )
    expect(scrollIntoView).toHaveBeenCalledTimes(1)
    expect(scrollIntoView.mock.contexts[0]).toBe(screen.getByTestId('resource-m-1'))
    rerender(<SyncPanel sessionId="session-1" events={lockEvents} selectedResourceId="m-1" revealKey={{}} />)
    expect(scrollIntoView).toHaveBeenCalledTimes(2)
  })

  it('marks the selected coroutine and rings the selected resource', () => {
    render(
      <SyncPanel
        sessionId="session-1"
        events={[...lockEvents, ...permits({ availablePermits: 0, activeHolderPermits: [2, 1] })]}
        selectedCoroutineId="c-1"
        selectedResourceId="sem-1"
        picker={anyone()}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByRole('button', { name: 'order-1' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('button', { name: 'order-2' })).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('resource-sem-1')).toHaveAttribute('data-selected', 'true')
    expect(screen.getByTestId('resource-m-1')).toHaveAttribute('data-selected', 'false')
  })
})

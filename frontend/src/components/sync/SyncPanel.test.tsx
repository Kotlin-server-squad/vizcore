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
  const permitEvents = [
    makeSyncEvent('SemaphoreCreated', 4, { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 }),
    makeSyncEvent('SemaphoreStateChanged', 5, {
      semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', availablePermits: 0, totalPermits: 3,
      activeHolders: ['c-3'], activeHolderLabels: ['order-3'], activeHolderPermits: [2], unknownHolderPermits: 1,
      waitingCoroutines: [], waitingLabels: [],
    }),
  ]

  beforeEach(() => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])
  })

  it('reads the events it is given, so it follows the live stream', () => {
    render(<SyncPanel sessionId="session-1" events={lockEvents} />, { wrapper: createWrapper() })
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('order-1')
  })

  it('lists a semaphore\'s holders with their permits, and permits whose holder is unknown', () => {
    render(<SyncPanel sessionId="session-1" events={permitEvents} />, { wrapper: createWrapper() })
    const held = screen.getByTestId('semaphore-holders')
    expect(held).toHaveTextContent('order-3 (2 permits)')
    expect(held).toHaveTextContent('1 permit, holder unknown')
  })

  it('selects a holder or a waiter by name', async () => {
    const onSelectCoroutine = vi.fn()
    render(<SyncPanel sessionId="session-1" events={lockEvents} onSelectCoroutine={onSelectCoroutine} />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'order-1' }))
    await userEvent.click(screen.getByRole('button', { name: 'order-2' }))
    expect(onSelectCoroutine.mock.calls).toEqual([['c-1'], ['c-2']])
  })

  it('marks the selected coroutine and rings the selected resource', () => {
    render(
      <SyncPanel sessionId="session-1" events={[...lockEvents, ...permitEvents]} selectedCoroutineId="c-1" selectedResourceId="sem-1" onSelectCoroutine={vi.fn()} />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByRole('button', { name: 'order-1' })).toHaveAttribute('aria-current', 'true')
    expect(screen.getByRole('button', { name: 'order-2' })).not.toHaveAttribute('aria-current')
    expect(screen.getByTestId('resource-sem-1')).toHaveAttribute('data-selected', 'true')
    expect(screen.getByTestId('resource-m-1')).toHaveAttribute('data-selected', 'false')
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { CoroutineState, type CoroutineNode, type CoroutineTimeline } from '@/types/api'
import { Inspector } from './Inspector'

const getCoroutineTimeline = vi.fn()

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getCoroutineTimeline: (...args: unknown[]) => getCoroutineTimeline(...args),
  },
}))

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function coroutine(overrides: Partial<CoroutineNode> = {}): CoroutineNode {
  return {
    id: 'c-1',
    jobId: 'job-1',
    parentId: 'c-parent',
    scopeId: 'scope-1',
    label: 'UserService.register',
    state: CoroutineState.SUSPENDED,
    ...overrides,
  }
}

function timeline(overrides: Partial<CoroutineTimeline> = {}): CoroutineTimeline {
  return {
    coroutineId: 'c-1',
    name: 'UserService.register',
    state: 'SUSPENDED',
    totalDuration: 4_200_000_000,
    activeDuration: 200_000_000,
    suspendedDuration: 4_000_000_000,
    parentId: 'c-parent',
    childrenIds: [],
    events: [
      {
        seq: 1,
        tsNanos: 1_000,
        kind: 'coroutine.created',
        threadName: 'main',
        dispatcherName: 'Dispatchers.Main',
        suspensionPoint: {
          function: 'com.acme.UserService.register',
          fileName: 'UserService.kt',
          lineNumber: 42,
        },
      },
      {
        seq: 2,
        tsNanos: 2_000,
        kind: 'coroutine.suspended',
        threadName: 'DefaultDispatcher-worker-3',
        dispatcherName: 'Dispatchers.IO',
        suspensionPoint: {
          function: 'com.acme.PaymentClient.charge',
          fileName: 'PaymentClient.kt',
          lineNumber: 88,
        },
      },
    ],
    ...overrides,
  } as CoroutineTimeline
}

describe('Inspector', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getCoroutineTimeline.mockResolvedValue(timeline())
  })

  it('shows a placeholder and fetches nothing while nothing is selected', () => {
    render(<Inspector sessionId="s-1" coroutine={null} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByText(/Select a coroutine to inspect it/i)).toBeInTheDocument()
    expect(getCoroutineTimeline).not.toHaveBeenCalled()
  })

  it('orders the cards most-diagnostic-first: timing, suspended at, runs on, identity, events (D-7)', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    const inspector = await screen.findByTestId('inspector')
    const headings = within(inspector)
      .getAllByRole('heading')
      .map(h => h.textContent)

    expect(headings).toEqual(['Timing', 'Suspended at', 'Runs on', 'Identity', 'Events'])
  })

  it('marks durations as poll-bounded with a ~ prefix', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    // 4.2s total, 200ms active, 4s suspended — sampling is poll-bounded, so
    // every duration is approximate and says so (validated sketch 001-C).
    expect(await screen.findByText('~4.20s')).toBeInTheDocument()
    expect(screen.getByText('~200.00ms')).toBeInTheDocument()
    expect(screen.getByText('~4.00s')).toBeInTheDocument()
  })

  it('says "not reported" rather than 0 when the backend sends no duration', async () => {
    getCoroutineTimeline.mockResolvedValue(
      timeline({ totalDuration: null, activeDuration: null, suspendedDuration: null }),
    )

    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    const timing = await screen.findByTestId('timing-card')
    expect(within(timing).getAllByText('not reported').length).toBe(3)
    expect(within(timing).queryByText(/~0/)).toBeNull()
  })

  it('names the thread and dispatcher from the most recent event that reports them', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    // The NEWEST event that carries them wins — a coroutine that moved from
    // Main to IO runs on IO now, and reporting the first event would be stale.
    expect(await screen.findByText('DefaultDispatcher-worker-3')).toBeInTheDocument()
    const runsOn = screen.getByTestId('runs-on-card')
    expect(within(runsOn).getByText('Dispatchers.IO')).toBeInTheDocument()
    expect(within(runsOn).queryByText('Dispatchers.Main')).toBeNull()
  })

  it('reports an unknown thread rather than inventing one', async () => {
    getCoroutineTimeline.mockResolvedValue(
      timeline({
        events: [{ seq: 1, tsNanos: 1, kind: 'coroutine.created' }],
      }),
    )

    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    // Wait for the query to settle before asserting the fallback, or the
    // pre-fetch render would pass this test for the wrong reason.
    await screen.findByText('coroutine.created')
    const runsOn = screen.getByTestId('runs-on-card')
    expect(within(runsOn).getAllByText('not reported').length).toBe(2)
  })

  it('shows identity without any fetch', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    const identity = await screen.findByTestId('identity-card')
    expect(within(identity).getByText('c-1')).toBeInTheDocument()
    expect(within(identity).getByText('job-1')).toBeInTheDocument()
    expect(within(identity).getByText('scope-1')).toBeInTheDocument()
    expect(within(identity).getByText('c-parent')).toBeInTheDocument()
  })

  it('renders identity only in read-only mode, and issues no protected fetch', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly />, {
      wrapper: createWrapper(),
    })

    const identity = await screen.findByTestId('identity-card')
    expect(within(identity).getByText('c-1')).toBeInTheDocument()

    // The shared shell carries no Bearer — every timeline-backed card is absent.
    expect(getCoroutineTimeline).not.toHaveBeenCalled()
    expect(screen.queryByTestId('timing-card')).toBeNull()
    expect(screen.queryByTestId('runs-on-card')).toBeNull()
    expect(screen.queryByTestId('suspended-at-card')).toBeNull()
  })
})

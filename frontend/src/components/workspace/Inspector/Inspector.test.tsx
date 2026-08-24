import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  CoroutineState,
  type CoroutineNode,
  type CoroutineTimeline,
  type ThreadActivity,
} from '@/types/api'
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

function threads(): ThreadActivity {
  return {
    '34': [
      {
        coroutineId: 'c-1',
        threadId: 34,
        threadName: 'DefaultDispatcher-worker-3',
        timestamp: 2_000,
        eventType: 'ASSIGNED',
        dispatcherName: 'Dispatchers.IO',
      },
    ],
  }
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

  it('names the thread and dispatcher from thread activity', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine()}
        readOnly={false}
        threadActivity={threads()}
      />,
      { wrapper: createWrapper() },
    )

    // Thread activity, not the timeline: the timeline projection is source-only
    // (D-02) and names no thread, which is why this card used to read
    // "not reported" against a live backend that had the data.
    const runsOn = screen.getByTestId('runs-on-card')
    expect(within(runsOn).getByText('DefaultDispatcher-worker-3')).toBeInTheDocument()
    expect(within(runsOn).getByText('Dispatchers.IO')).toBeInTheDocument()
  })

  it('reports an unknown thread rather than inventing one', () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    // No thread activity at all — "not reported" is still the honest answer.
    const runsOn = screen.getByTestId('runs-on-card')
    expect(within(runsOn).getAllByText('not reported').length).toBe(2)
  })

  it('does not attribute another coroutine\'s thread to this one', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ id: 'c-other' })}
        readOnly={false}
        threadActivity={threads()}
      />,
      { wrapper: createWrapper() },
    )

    const runsOn = screen.getByTestId('runs-on-card')
    expect(within(runsOn).queryByText('DefaultDispatcher-worker-3')).toBeNull()
    expect(within(runsOn).getAllByText('not reported').length).toBe(2)
  })

  it("times events relative to the coroutine's first event, not the epoch", async () => {
    // Absolute epoch-ish timestamps, 250ms apart.
    getCoroutineTimeline.mockResolvedValue(
      timeline({
        events: [
          { seq: 1, tsNanos: 1_700_000_000_000_000_000, kind: 'coroutine.created' },
          { seq: 2, tsNanos: 1_700_000_000_250_000_000, kind: 'coroutine.suspended' },
        ],
      }),
    )

    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    await screen.findByText('coroutine.suspended')
    const events = screen.getByTestId('events-card')
    // tsNanos is an ABSOLUTE timestamp. Formatted as a duration it reads as a
    // wall-clock epoch offset (~1.7 billion seconds), which is meaningless
    // here. Rows are offsets from the coroutine's own first event.
    expect(within(events).getByText('+250.00ms')).toBeInTheDocument()
    expect(within(events).getByText('+0.00ms')).toBeInTheDocument()
    expect(within(events).queryByText(/\d{6,}/)).toBeNull()
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

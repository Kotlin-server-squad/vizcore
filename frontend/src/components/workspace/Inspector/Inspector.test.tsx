import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  CoroutineState,
  type CoroutineNode,
  type CoroutineTimeline,
  type ThreadActivity,
} from '@/types/api'
import { Inspector } from './Inspector'
import type { MomentView } from '@/hooks/use-moment'
import { ApiError, RateLimitedError } from '@/lib/api-errors'

const getCoroutineTimeline = vi.fn()
const getTimelineAt = vi.fn()
const getHierarchyAt = vi.fn()

vi.mock('@/lib/api-client', async () => {
  const errors = await vi.importActual<typeof import('@/lib/api-errors')>('@/lib/api-errors')
  return {
    ...errors,
    apiClient: {
      getCoroutineTimeline: (...args: unknown[]) => getCoroutineTimeline(...args),
      getTimelineAt: (...args: unknown[]) => getTimelineAt(...args),
      getHierarchyAt: (...args: unknown[]) => getHierarchyAt(...args),
    },
  }
})

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
    // The cards' own headings; the h2 above them names the coroutine.
    const headings = within(inspector)
      .getAllByRole('heading', { level: 4 })
      .map(h => h.textContent)

    expect(headings).toEqual(['Timing', 'Suspended at', 'Runs on', 'Identity', 'Events'])
  })

  it("heads the inspector with the coroutine's name and state", () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, { wrapper: createWrapper() })
    const heading = screen.getByRole('heading', { level: 2 })
    expect(heading).toHaveTextContent('UserService.register')
    expect(heading).toHaveTextContent('Suspended')
  })

  it('shows the empty state it is given when nothing is selected', () => {
    render(<Inspector sessionId="s-1" coroutine={null} readOnly={false} emptyState={<p>summary</p>} />, {
      wrapper: createWrapper(),
    })
    expect(screen.getByText('summary')).toBeInTheDocument()
    expect(screen.queryByText(/Select a coroutine to inspect it/)).toBeNull()
  })

  it('keeps the session summary mounted under a selection, and offers to return to it', async () => {
    const onClearSelection = vi.fn()
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine()}
        readOnly={false}
        emptyState={<p>summary</p>}
        onClearSelection={onClearSelection}
      />,
      { wrapper: createWrapper() },
    )
    // Hidden, not unmounted: its metrics query stays subscribed (#124).
    expect(screen.getByText('summary')).not.toBeVisible()
    await userEvent.click(screen.getByRole('button', { name: 'Session summary' }))
    expect(onClearSelection).toHaveBeenCalledTimes(1)
  })

  it('marks durations as poll-bounded with a ~ prefix', async () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    // 4.2s total, 200ms active, 4s suspended — sampling is poll-bounded, so
    // every duration is approximate and says so (validated sketch 001-C).
    expect(await screen.findByText('~4.2 s')).toBeInTheDocument()
    expect(screen.getByText('~200 ms')).toBeInTheDocument()
    expect(screen.getByText('~4.0 s')).toBeInTheDocument()
  })

  it('says "not reported" rather than 0 when the backend sends no duration', async () => {
    getCoroutineTimeline.mockResolvedValue(
      timeline({ totalDuration: null, activeDuration: null, suspendedDuration: null, events: [] }),
    )

    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    const timing = await screen.findByTestId('timing-card')
    expect(within(timing).getAllByText('not reported').length).toBe(3)
    expect(within(timing).queryByText(/~0/)).toBeNull()
  })

  it('shows an approximate total "so far" for a coroutine that is still running (#145)', async () => {
    // Backend: total is completion-only; active/suspended count closed intervals.
    getCoroutineTimeline.mockResolvedValue(
      timeline({
        totalDuration: null,
        activeDuration: 200_000_000,
        suspendedDuration: 1_000_000_000,
        events: [
          { seq: 1, tsNanos: 0, kind: 'coroutine.created' },
          { seq: 2, tsNanos: 3_000_000_000, kind: 'coroutine.suspended' },
        ],
      } as Partial<CoroutineTimeline>),
    )

    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    const timing = await screen.findByTestId('timing-card')
    // The event span (3s) is longer than the closed intervals (1.2s) — the
    // larger figure is the honest lower bound.
    expect(await within(timing).findByText('~3.0 s so far')).toBeInTheDocument()
  })

  it('counts a running coroutine up to now and flags a long run without suspending (#218)', async () => {
    // Resumed 3 s after creation and silent since: blocked in a call on its thread.
    const createdAtEpochMs = Date.now() - 60_000
    getCoroutineTimeline.mockResolvedValue(
      timeline({
        totalDuration: null,
        activeDuration: 100_000_000,
        suspendedDuration: 2_900_000_000,
        events: [
          { seq: 1, tsNanos: 0, kind: 'coroutine.created' },
          { seq: 2, tsNanos: 100_000_000, kind: 'coroutine.suspended' },
        ],
        openPhase: 'ACTIVE',
        openPhaseSinceNanos: 3_000_000_000,
        createdAtNanos: 0,
        createdAtEpochMs,
        observedAtEpochMs: createdAtEpochMs + 60_000,
      } as Partial<CoroutineTimeline>),
    )

    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, {
      wrapper: createWrapper(),
    })

    const timing = await screen.findByTestId('timing-card')
    expect(await within(timing).findByText(/^~(59\.\d s|1 m 0 s) so far$/)).toBeInTheDocument()
    expect(within(timing).getByTestId('possibly-blocked')).toHaveTextContent('without suspending')
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

describe('Inspector — what the coroutine waits for or holds (S-6)', () => {
  beforeEach(() => {
    getCoroutineTimeline.mockReset()
    getCoroutineTimeline.mockResolvedValue(timeline())
  })

  it('says it under the heading, a wait in the Suspended colour', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine()}
        readOnly={false}
        activity={{ text: 'waits for inventory-lock · 1st', waiting: true }}
      />,
      { wrapper: createWrapper() },
    )
    const line = screen.getByTestId('inspector-activity')
    expect(line).toHaveTextContent('waits for inventory-lock · 1st')
    expect(line.className).toContain('text-warning')
  })

  it('has no such line when the coroutine waits for and holds nothing', () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} />, { wrapper: createWrapper() })
    expect(screen.queryByTestId('inspector-activity')).toBeNull()
  })
})


describe('Inspector at a moment (Part 8, S-11)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  /** A store with events up to seq 20; the backend answers `atSeq` with the events up to it, as it does. */
  const store = [
    { seq: 3, tsNanos: 3_000, kind: 'coroutine.created' },
    { seq: 6, tsNanos: 6_000, kind: 'coroutine.suspended' },
    { seq: 15, tsNanos: 15_000, kind: 'coroutine.resumed-later-marker' },
  ]
  function answerAtSeq() {
    getTimelineAt.mockImplementation(async (read: { binding: { atSeq: number } }) =>
      timeline({ events: store.filter(e => e.seq <= read.binding.atSeq) as CoroutineTimeline['events'], totalDuration: null }),
    )
  }

  const replay = (over: Partial<MomentView> = {}): MomentView => ({
    read: { source: { kind: 'session', sessionId: 's-1' }, binding: { kind: 'replay', atSeq: 10 } },
    cursorSeq: 10,
    changesBetween: (_id, a, b) => [3, 6, 15].some(seq => seq > Math.min(a, b) && seq <= Math.max(a, b)),
    settleMs: 5,
    hold: false,
    moment: { kind: 'replay', atNanos: 4.2e9, index: 9, total: 20 },
    ...over,
  })

  it('inspects the coroutine at the cursor, never showing a later event in the store', async () => {
    answerAtSeq()
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} />, {
      wrapper: createWrapper(),
    })
    const events = await screen.findByTestId('events-card')
    expect(await within(events).findByText('2 events')).toBeInTheDocument()
    expect(within(events).queryByText('coroutine.resumed-later-marker')).toBeNull()
    expect(screen.getByTestId('moment-note')).toHaveTextContent('At 4.2 s · event 10 of 20')
    expect(getCoroutineTimeline).not.toHaveBeenCalled()
  })

  it('says where it last ran, not where it runs, at a moment', async () => {
    answerAtSeq()
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} threadActivity={threads()} />, {
      wrapper: createWrapper(),
    })
    expect(await screen.findByRole('heading', { level: 4, name: 'Last observed on' })).toBeInTheDocument()
  })

  it('reads the coroutine in a shared link, bound to the loaded cut, even though the view is read-only', async () => {
    getTimelineAt.mockResolvedValue(timeline())
    const shared: MomentView = replay({
      read: { source: { kind: 'shared', sessionId: 's-1', token: 'tok' }, binding: { kind: 'bound', asOfSeq: 30, asOfCut: 'k' } },
      cursorSeq: 30,
      moment: { kind: 'loaded', seq: 30 },
    })
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly moment={shared} />, { wrapper: createWrapper() })
    await screen.findByTestId('events-card')
    expect(getTimelineAt).toHaveBeenCalledWith(shared.read, 'c-1')
  })

  it('shows what the durable summary keeps for a coroutine whose early events were trimmed, and no made-up duration', async () => {
    getTimelineAt.mockResolvedValue(
      timeline({ eventsTrimmed: true, totalDuration: null, activeDuration: null, suspendedDuration: null, events: [store[1]] as CoroutineTimeline['events'] }),
    )
    getHierarchyAt.mockResolvedValue([
      {
        id: 'c-1',
        parentId: null,
        name: 'UserService.register',
        scopeId: null,
        jobId: null,
        state: 'SUSPENDED',
        createdAtNanos: 1e9,
        creationPoint: { function: 'com.acme.UserService.register', fileName: 'UserService.kt', lineNumber: 42 },
        exceptionType: null,
        summary: { lastThread: { threadName: 'worker-7' } },
      },
    ])
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} />, { wrapper: createWrapper() })
    const card = await screen.findByTestId('summary-card')
    expect(await within(card).findByText(/UserService\.kt:42/)).toBeInTheDocument()
    expect(within(card).getByText('worker-7')).toBeInTheDocument()
    expect(within(card).getByText(/from the durable summary/i)).toBeInTheDocument()
    const timing = screen.getByTestId('timing-card')
    expect(timing).toHaveTextContent(/unavailable: early events were trimmed/i)
    expect(timing).not.toHaveTextContent(/so far/)
    expect(screen.getByTestId('events-card')).toHaveTextContent(/earlier events were trimmed/i)
  })

  it('says when its state at the cursor is unknown', async () => {
    getTimelineAt.mockResolvedValue(timeline({ partial: true }))
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} />, { wrapper: createWrapper() })
    expect(await screen.findByText(/state at this moment is unknown/i)).toBeInTheDocument()
  })

  it('says it is catching up rather than showing another moment', async () => {
    getTimelineAt.mockReturnValue(new Promise(() => {}))
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} />, { wrapper: createWrapper() })
    expect(await screen.findByText(/updating to this moment/i)).toBeInTheDocument()
  })

  it('says the shared view has moved on, with a reload, when a bound read is stale', async () => {
    getTimelineAt.mockRejectedValue(new ApiError(409, 'moved on', 'stale'))
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly moment={replay()} />, { wrapper: createWrapper() })
    expect(await screen.findByText(/this view has moved on since the link was loaded/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
  })

  it('says a read was rate limited, with a retry', async () => {
    getTimelineAt.mockRejectedValue(new RateLimitedError(5000))
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} />, { wrapper: createWrapper() })
    expect(await screen.findByText(/too many requests/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('says why details are unavailable when the session keeps no summaries', async () => {
    getTimelineAt.mockRejectedValue(new ApiError(503, 'x', 'history-trimmed'))
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} moment={replay()} />, { wrapper: createWrapper() })
    expect(await screen.findByText(/details unavailable: this session's early events were trimmed/i)).toBeInTheDocument()
  })

  it('does not guess a binding in a shared link without a health', () => {
    render(
      <Inspector sessionId="s-1" coroutine={coroutine()} readOnly unavailableReason="history-trimmed" />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByText(/details unavailable: this link's health could not be read \(history-trimmed\)/i)).toBeInTheDocument()
    expect(getTimelineAt).not.toHaveBeenCalled()
  })

  it('keeps a selection that does not exist yet at the cursor, and says so', () => {
    render(
      <Inspector sessionId="s-1" coroutine={null} readOnly={false} moment={replay()} pendingSelectionLabel="order-7" />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('inspector-pending')).toHaveTextContent('order-7 · not created yet at this moment')
  })
})

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

  it('shows an approximate lower bound for a coroutine that is still running (#145; "at least", Part 7)', async () => {
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
    expect(await within(timing).findByText('at least ~3.0 s')).toBeInTheDocument()
  })

  it('estimates no "now" from the browser clock and gives no "may be blocked" hint of its own (Part 7, #232 removed)', async () => {
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
    // The closed intervals (3.0 s) are what the events record; nothing counts on with the browser's clock.
    expect(await within(timing).findByText('at least ~3.0 s')).toBeInTheDocument()
    expect(within(timing).queryByTestId('possibly-blocked')).toBeNull()
  })

  it('leads a failed coroutine with its exception, in T-3\'s order', async () => {
    getCoroutineTimeline.mockResolvedValue(timeline())
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.FAILED })}
        readOnly={false}
        problem={{
          kind: 'FAILED',
          failure: { coroutineId: 'c-1', name: 'pay', exceptionType: 'java.lang.IllegalStateException', message: 'card declined', failedAtSeq: 9, detailsDropped: false },
          round: { failed: 2, cancelledAfter: 3 },
        }}
      />,
      { wrapper: createWrapper() },
    )
    const card = screen.getByTestId('exception-card')
    expect(card).toHaveTextContent('java.lang.IllegalStateException')
    expect(card).toHaveTextContent('card declined')
    expect(card).toHaveTextContent('1 of 2 failed coroutines in this round')
    expect(card).toHaveTextContent('3 cancelled after the first failure (context, not a cause)')
    const order = ['exception-card', 'suspended-at-card', 'timing-card', 'runs-on-card', 'identity-card'].map(id => screen.getByTestId(id))
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1]!.compareDocumentPosition(order[i]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
  })

  it('says when a failure\'s details were dropped', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.FAILED })}
        readOnly={false}
        problem={{
          kind: 'FAILED',
          failure: { coroutineId: 'c-1', name: 'pay', exceptionType: null, message: null, failedAtSeq: 9, detailsDropped: true },
          round: null,
        }}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('exception-card')).toHaveTextContent('Details dropped')
    expect(screen.getByTestId('exception-card')).toHaveTextContent('Round unavailable')
  })

  it('shows a possibly blocked coroutine as observed, its clue, then its transitions', async () => {
    getCoroutineTimeline.mockResolvedValue(timeline({ totalDuration: null }))
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.ACTIVE })}
        readOnly={false}
        threadActivity={threads()}
        problem={{
          kind: 'POSSIBLY_BLOCKED',
          incident: {
            type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c-1', name: 'poll', roundId: null, anchorId: null,
            lastThread: { threadId: 3, threadName: 'lettuce-nioEventLoop-4-1', seq: 7 }, dispatcher: 'Dispatchers.Unconfined',
            lastSuspension: { function: 'com.acme.Poller.poll', fileName: 'Poller.kt', lineNumber: 12, reason: 'delay' },
            lastSuspensionProvenance: 'CREATION_FALLBACK',
            lastRunStart: { kind: 'RESUMED', appNanos: 2_000_000_000, seq: 6 }, runningForNanos: 12_300_000_000,
            transitions: [
              { kind: 'CREATED', appNanos: 0, seq: 1 },
              { kind: 'SUSPENDED', appNanos: 500_000_000, seq: 3 },
              { kind: 'RESUMED', appNanos: 2_000_000_000, seq: 6 },
            ],
            transitionsGap: true, firstObservedRunning: false, eventLoopClue: 'lettuce-nioEventLoop-', sortSeq: 6,
          },
        }}
        problemAsOf="as of 14:02:31"
        captureStartAppNanos={0}
      />,
      { wrapper: createWrapper() },
    )
    const observed = screen.getByTestId('observed-card')
    expect(observed).toHaveTextContent('Observed')
    expect(observed).toHaveTextContent('lettuce-nioEventLoop-4-1')
    expect(observed).toHaveTextContent('Dispatchers.Unconfined')
    // Only a frame the SDK read is a "last suspension point" (triage provenance).
    expect(observed).toHaveTextContent('suspension location unavailable')
    expect(observed).not.toHaveTextContent('Poller.kt')
    expect(observed).toHaveTextContent('as of 14:02:31')
    // The live cards that pick any thread and any frame are not shown for this kind.
    expect(screen.queryByTestId('runs-on-card')).toBeNull()
    expect(screen.queryByTestId('suspended-at-card')).toBeNull()
    const clue = screen.getByTestId('clue-card')
    expect(clue).toHaveTextContent('lettuce-nioEventLoop-')
    expect(clue).toHaveTextContent('If the code it is running makes a blocking call')
    const timing = screen.getByTestId('timing-card')
    expect(within(timing).getByTestId('transitions')).toHaveTextContent('Running for 12.3 s with no suspension observed')
    expect(within(timing).getByTestId('transitions')).toHaveTextContent('Resumed at 2.0 s')
    expect(within(timing).getByTestId('transitions')).toHaveTextContent('Earlier transitions were not retained')
    const order = ['observed-card', 'clue-card', 'timing-card', 'identity-card'].map(id => screen.getByTestId(id))
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1]!.compareDocumentPosition(order[i]!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
  })

  it('shows a real suspension frame, and says when no thread was observed in the current run', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.ACTIVE })}
        readOnly={false}
        problem={{
          kind: 'POSSIBLY_BLOCKED',
          incident: {
            type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c-1', name: 'poll', roundId: null, anchorId: null,
            lastThread: null, dispatcher: null,
            lastSuspension: { function: 'com.acme.Poller.poll', fileName: 'Poller.kt', lineNumber: 12, reason: 'delay' },
            lastSuspensionProvenance: 'REAL_FRAME',
            lastRunStart: { kind: 'RESUMED', appNanos: 0, seq: 2 }, runningForNanos: 11_000_000_000,
            transitions: [], transitionsGap: false, firstObservedRunning: false, eventLoopClue: null, sortSeq: 2,
          },
        }}
      />,
      { wrapper: createWrapper() },
    )
    const observed = screen.getByTestId('observed-card')
    expect(observed).toHaveTextContent('not observed for the current run')
    expect(observed).toHaveTextContent('Poller.kt:12')
  })

  it('says a coroutine missing from a cut list may still have a problem', () => {
    render(
      <Inspector sessionId="s-1" coroutine={coroutine({ state: CoroutineState.FAILED })} readOnly={false} problemsCut />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('problem-not-listed')).toHaveTextContent(
      'Problem details: not among the problems the health lists (it lists the first 50)',
    )
  })

  it('opens a problem whose coroutine is not drawn from the incident alone, reading no timeline', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={null}
        readOnly={false}
        problemOnly={{
          id: 'c-9',
          label: 'pay-9',
          problem: {
            kind: 'FAILED',
            failure: { coroutineId: 'c-9', name: 'pay-9', exceptionType: 'IOException', message: 'reset', failedAtSeq: 3, detailsDropped: false },
            round: null,
          },
        }}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('pay-9')
    expect(screen.getByTestId('exception-card')).toHaveTextContent('IOException')
    expect(screen.getByTestId('problem-only-note')).toHaveTextContent('Not drawn in the tree at this moment')
    expect(getCoroutineTimeline).not.toHaveBeenCalled()
  })

  it('hides the session summary under an incident-only selection, and keeps a blocked one\'s transitions', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={null}
        readOnly={false}
        emptyState={<p>the session summary</p>}
        problemOnly={{
          id: 'c-9',
          label: 'poll-9',
          problem: {
            kind: 'POSSIBLY_BLOCKED',
            incident: {
              type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c-9', name: 'poll-9', roundId: null, anchorId: null,
              lastThread: null, dispatcher: null, lastSuspension: null, lastSuspensionProvenance: null,
              lastRunStart: { kind: 'STARTED', appNanos: 0, seq: 2 }, runningForNanos: 11e9,
              transitions: [{ kind: 'STARTED', appNanos: 0, seq: 2 }], transitionsGap: true, firstObservedRunning: true,
              eventLoopClue: null, sortSeq: 2,
            },
          },
        }}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByText('the session summary')).not.toBeVisible()
    expect(screen.getByTestId('transitions')).toHaveTextContent('Running for 11.0 s with no suspension observed')
    expect(getCoroutineTimeline).not.toHaveBeenCalled()
  })

  it('names each transition by its own event when the capture start is unknown', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.ACTIVE })}
        readOnly={false}
        problem={{
          kind: 'POSSIBLY_BLOCKED',
          incident: {
            type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c-1', name: 'poll', roundId: null, anchorId: null,
            lastThread: null, dispatcher: null, lastSuspension: null, lastSuspensionProvenance: null,
            lastRunStart: { kind: 'RESUMED', appNanos: 9, seq: 6 }, runningForNanos: 11e9,
            transitions: [{ kind: 'CREATED', appNanos: 0, seq: 1 }, { kind: 'RESUMED', appNanos: 9, seq: 6 }],
            transitionsGap: false, firstObservedRunning: false, eventLoopClue: null, sortSeq: 6,
          },
        }}
      />,
      { wrapper: createWrapper() },
    )
    const list = screen.getByTestId('transitions')
    expect(list).toHaveTextContent('Created at event 1')
    expect(list).toHaveTextContent('Resumed at event 6')
  })

  it('has no clue card without a clue', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.ACTIVE })}
        readOnly={false}
        problem={{
          kind: 'POSSIBLY_BLOCKED',
          incident: {
            type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c-1', name: 'poll', roundId: null, anchorId: null,
            lastThread: null, dispatcher: null, lastSuspension: null, lastSuspensionProvenance: null,
            lastRunStart: { kind: 'STARTED', appNanos: 0, seq: 2 }, runningForNanos: 11_000_000_000,
            transitions: [], transitionsGap: false, firstObservedRunning: true, eventLoopClue: null, sortSeq: 2,
          },
        }}
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.queryByTestId('clue-card')).toBeNull()
    expect(screen.getByTestId('transitions')).toHaveTextContent('No suspension was ever observed')
  })

  it("re-reads a live coroutine's timeline after its own new events, at most every 10 s (Part 7, P-1)", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      getCoroutineTimeline.mockResolvedValue(timeline({ totalDuration: null }))
      const wrapper = createWrapper()
      const { rerender } = render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} liveSeq={1} />, { wrapper })
      await vi.waitFor(() => expect(getCoroutineTimeline).toHaveBeenCalledTimes(1))
      rerender(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} liveSeq={2} />)
      rerender(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} liveSeq={3} />)
      await vi.advanceTimersByTimeAsync(5_000)
      expect(getCoroutineTimeline).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(5_500)
      await vi.waitFor(() => expect(getCoroutineTimeline).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })

  it("shows the server's lifetime in Timing, as the card does, and the card's facts under the heading (Part 7, P-9)", async () => {
    getCoroutineTimeline.mockResolvedValue(timeline({ totalDuration: null }))
    render(
      <Inspector
        sessionId="s-1"
        coroutine={coroutine({ state: CoroutineState.ACTIVE, lifetimeNanos: 12.3e9, lifetimeKind: 'SO_FAR' })}
        readOnly={false}
      />,
      { wrapper: createWrapper() },
    )
    const timing = await screen.findByTestId('timing-card')
    expect(within(timing).getByText('12.3 s so far')).toBeInTheDocument()
    expect(screen.getByTestId('node-meta')).toHaveTextContent('12.3 s so far')
  })

  it('a state change while streaming waits for the same 10 s throttle: one path, not two (Part 7, P-1)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      getCoroutineTimeline.mockResolvedValue(timeline({ totalDuration: null }))
      const wrapper = createWrapper()
      const { rerender } = render(<Inspector sessionId="s-1" coroutine={coroutine({ state: CoroutineState.ACTIVE })} readOnly={false} liveSeq={1} />, { wrapper })
      await vi.waitFor(() => expect(getCoroutineTimeline).toHaveBeenCalledTimes(1))
      rerender(<Inspector sessionId="s-1" coroutine={coroutine({ state: CoroutineState.SUSPENDED })} readOnly={false} liveSeq={2} />)
      await vi.advanceTimersByTimeAsync(2_000)
      expect(getCoroutineTimeline).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(9_000)
      await vi.waitFor(() => expect(getCoroutineTimeline).toHaveBeenCalledTimes(2))
      await vi.advanceTimersByTimeAsync(10_000)
      expect(getCoroutineTimeline).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })

  it('moves focus to its heading on request (Show, T-3)', () => {
    render(<Inspector sessionId="s-1" coroutine={coroutine()} readOnly={false} focusKey={1} />, { wrapper: createWrapper() })
    expect(screen.getByRole('heading', { level: 2 })).toHaveFocus()
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

  it('says a selection from the summaries is updating, not missing, while their read catches up', () => {
    render(
      <Inspector
        sessionId="s-1"
        coroutine={null}
        readOnly={false}
        moment={replay()}
        pendingSelectionLabel="sneaker-drop"
        pendingSelectionNote="updating to this moment…"
      />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('inspector-pending')).toHaveTextContent('sneaker-drop · updating to this moment…')
  })

  it('keeps a selection that does not exist yet at the cursor, and says so', () => {
    render(
      <Inspector sessionId="s-1" coroutine={null} readOnly={false} moment={replay()} pendingSelectionLabel="order-7" />,
      { wrapper: createWrapper() },
    )
    expect(screen.getByTestId('inspector-pending')).toHaveTextContent('order-7 · not created yet at this moment')
  })
})

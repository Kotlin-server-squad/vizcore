import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ProblemsPanel, type ProblemsPanelProps } from './ProblemsPanel'
import { health } from '@/test/health-fixture'
import type { Incident } from '@/types/health'
import { ApiError } from '@/lib/api-errors'

const failure = (id: string, name: string) => ({
  coroutineId: id,
  name,
  exceptionType: 'java.lang.IllegalStateException',
  message: `${name} broke`,
  failedAtSeq: 9,
  detailsDropped: false,
})

const ROUND: Incident = {
  type: 'FAILED_ROUND',
  id: 'i1',
  roundId: 'r1',
  anchorId: 'order-7',
  failures: { items: [failure('pay', 'payment')], total: 3 },
  cancelledAfter: { items: ['ship'], total: 4 },
  firstFailureSeq: 9,
  sortSeq: 9,
}
const UNGROUPED: Incident = { type: 'UNGROUPED_FAILURE', id: 'i2', failure: failure('lone', 'lonely'), sortSeq: 8 }
const BLOCKED: Incident = {
  type: 'POSSIBLY_BLOCKED',
  id: 'i3',
  coroutineId: 'poll',
  name: 'poller',
  roundId: null,
  anchorId: null,
  lastThread: { threadId: 1, threadName: 'worker-1', seq: 3 },
  dispatcher: null,
  lastSuspension: null,
  lastSuspensionProvenance: null,
  lastRunStart: { kind: 'RESUMED', appNanos: 0, seq: 2 },
  runningForNanos: 12_300_000_000,
  transitions: [],
  transitionsGap: false,
  firstObservedRunning: false,
  eventLoopClue: null,
  sortSeq: 2,
}

function props(over: Partial<ProblemsPanelProps> = {}): ProblemsPanelProps {
  return {
    result: { health: health(), status: 'ready', error: null, retry: vi.fn() },
    captureStartAppNanos: 0,
    clock: { timeZone: 'UTC' },
    onPage: () => true,
    labelOf: id => (id === 'order-7' ? 'order-7 (checkout)' : undefined),
    onShow: vi.fn(),
    onLongLived: vi.fn(),
    checks: { kind: 'status', status: { sessionId: 's', lastRun: null, newEventsSince: null }, latestSeq: 40, onRun: vi.fn(), onOpen: vi.fn() },
    ...over,
  }
}

const withProblems = health({
  state: 'PROBLEMS',
  problemCount: 3,
  failedRoundCount: 1,
  ungroupedFailureCount: 1,
  possiblyBlockedCount: 1,
  incidents: { items: [ROUND, UNGROUPED, BLOCKED], total: 3 },
})

describe('ProblemsPanel (S-8)', () => {
  it('nothing detected: the honest sentence, with its "as of" and, for an attached app, the sampling note', () => {
    render(<ProblemsPanel {...props({ sampled: true })} />)
    expect(screen.getByTestId('problems-headline')).toHaveTextContent(
      'No problems detected in the captured coroutines · as of 14:02:31',
    )
    expect(screen.getByTestId('problems-headline')).toHaveAttribute('data-tone', 'clean')
    expect(screen.getByText(/samples an attached app's coroutines on a poll/)).toBeInTheDocument()
    expect(screen.getByText('Nothing possibly blocked')).toBeInTheDocument()
  })

  it('a demo, captured in-process rather than sampled, gets no sampling note', () => {
    render(<ProblemsPanel {...props()} />)
    expect(screen.queryByText(/samples an attached app's coroutines/)).toBeNull()
  })

  it('not evaluated: says so with the reason, never as healthy', () => {
    render(
      <ProblemsPanel
        {...props({ result: { health: undefined, status: 'error', error: new ApiError(503, 'x', 'history-trimmed'), retry: vi.fn() } })}
      />,
    )
    expect(screen.getByTestId('problems-headline')).toHaveTextContent(
      "Problems not evaluated (this session's early events were trimmed)",
    )
    expect(screen.getByTestId('problems-headline')).toHaveAttribute('data-tone', 'neutral')
  })

  it('possibly blocked not evaluated for a demo: the reason in words', () => {
    render(
      <ProblemsPanel
        {...props({ result: { health: health({ state: 'NO_FAILURES_PARTIAL', possiblyBlocked: { status: 'NOT_EVALUATED', reason: 'DEMO' } }), status: 'ready', error: null, retry: vi.fn() } })}
      />,
    )
    expect(screen.getByText(/Possibly blocked: not evaluated \(demo session/)).toBeInTheDocument()
  })

  it('problems: the headline, a failed round with its rows, an ungrouped failure and a possibly blocked coroutine, each with Show', async () => {
    const onShow = vi.fn()
    render(<ProblemsPanel {...props({ result: { health: withProblems, status: 'ready', error: null, retry: vi.fn() }, onShow })} />)
    expect(screen.getByTestId('problems-headline')).toHaveTextContent('3 problems detected · as of 14:02:31')
    expect(screen.getByTestId('problems-headline')).toHaveTextContent('Failed 2 (1 round · 1 ungrouped) · Possibly blocked 1')
    const problems = screen.getAllByTestId('problem')
    expect(problems).toHaveLength(3)
    expect(problems[0]).toHaveTextContent('Failed round · order-7 (checkout)')
    expect(problems[0]).toHaveTextContent('3 failed · 4 cancelled after the first failure')
    expect(problems[0]).toHaveTextContent('payment')
    expect(problems[0]).toHaveTextContent('java.lang.IllegalStateException: payment broke')
    expect(problems[0]).toHaveTextContent('and 2 more failed in this round')
    expect(problems[1]).toHaveTextContent('Failed · lonely')
    expect(problems[1]).toHaveTextContent('round unavailable: its ancestry could not be resolved')
    expect(problems[2]).toHaveTextContent('Possibly blocked · poller')
    expect(problems[2]).toHaveTextContent('running for 12.3 s with no suspension observed · on worker-1')
    await userEvent.click(within(problems[0]!).getByRole('button', { name: 'Show payment' }))
    expect(onShow).toHaveBeenCalledWith('pay')
    await userEvent.click(within(problems[2]!).getByRole('button', { name: 'Show poller' }))
    expect(onShow).toHaveBeenCalledWith('poll')
  })

  it('a problem whose coroutine is not drawn says so, and Show still opens it', () => {
    render(
      <ProblemsPanel {...props({ result: { health: withProblems, status: 'ready', error: null, retry: vi.fn() }, onPage: id => id !== 'lone' })} />,
    )
    expect(screen.getAllByTestId('problem')[1]).toHaveTextContent('not in the tree at this moment')
    expect(within(screen.getAllByTestId('problem')[1]!).getByRole('button', { name: 'Show lonely' })).toBeInTheDocument()
  })

  it('says when it lists only part of the problems', () => {
    const h = { ...withProblems, incidents: { items: withProblems.incidents.items, total: 80 } }
    render(<ProblemsPanel {...props({ result: { health: h, status: 'ready', error: null, retry: vi.fn() } })} />)
    expect(screen.getByText('Showing 3 of 80 problems; the round picker lists every problem round.')).toBeInTheDocument()
  })

  it('Long-lived is an observation that opens the tree filter, not a problem', async () => {
    const onLongLived = vi.fn()
    const h = health({ observations: { longLived: { items: [], total: 4 }, longSuspended: { items: [], total: 2 } } })
    render(<ProblemsPanel {...props({ result: { health: h, status: 'ready', error: null, retry: vi.fn() }, onLongLived, longLivedNote: 'the filter shows the first 50' })} />)
    const obs = screen.getByTestId('observations')
    await userEvent.click(within(obs).getByRole('button', { name: 'Long-lived (4)' }))
    expect(onLongLived).toHaveBeenCalled()
    expect(obs).toHaveTextContent('Long suspended (2)')
    expect(obs).toHaveTextContent('the filter shows the first 50')
  })

  it("names vizcore's own connection apart", () => {
    const h = health({ vizcoreConnection: { captured: 3, failed: 0, failures: { items: [], total: 0 } } })
    render(<ProblemsPanel {...props({ result: { health: h, status: 'ready', error: null, retry: vi.fn() } })} />)
    expect(screen.getByTestId('observations')).toHaveTextContent('vizcore connection: 3 captured, no failed coroutines captured')
  })

  describe('checks status (T-8)', () => {
    const run = (failedRuleCount: number) => ({ checkedSeq: 30, ranAtEpochMs: 1, rules: [], failedRuleCount })

    it('not run, with Run', async () => {
      const onRun = vi.fn()
      render(<ProblemsPanel {...props({ checks: { kind: 'status', status: { sessionId: 's', lastRun: null, newEventsSince: null }, latestSeq: 40, onRun } })} />)
      expect(screen.getByTestId('checks-status')).toHaveTextContent('Instrumentation checks: not run')
      await userEvent.click(screen.getByRole('button', { name: 'Run checks' }))
      expect(onRun).toHaveBeenCalled()
    })

    it('passed, with how many events came since', () => {
      render(<ProblemsPanel {...props({ checks: { kind: 'status', status: { sessionId: 's', lastRun: run(0), newEventsSince: 2 }, latestSeq: 40 } })} />)
      expect(screen.getByTestId('checks-status')).toHaveTextContent('Instrumentation checks: passed at event 30 · 10 new events since')
    })

    it('failed, opening the checks', async () => {
      const onOpen = vi.fn()
      render(<ProblemsPanel {...props({ checks: { kind: 'status', status: { sessionId: 's', lastRun: run(2), newEventsSince: 0 }, latestSeq: null, onOpen } })} />)
      expect(screen.getByTestId('checks-status')).toHaveTextContent('Instrumentation checks: failed at event 30 · 2 rules failed')
      await userEvent.click(screen.getByRole('button', { name: 'Open checks' }))
      expect(onOpen).toHaveBeenCalled()
    })

    it('unavailable is not "not run"', () => {
      render(<ProblemsPanel {...props({ checks: { kind: 'unavailable' } })} />)
      expect(screen.getByTestId('checks-status')).toHaveTextContent('Instrumentation checks: status unavailable')
    })
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import type { CoroutineState, SessionSnapshot } from '@/types/api'
import type { Incident, SessionHealth } from '@/types/health'
import { health } from '@/test/health-fixture'

vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [], isPending: false, isError: false })),
  useDeleteSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
}))
vi.mock('@/hooks/use-event-stream', () => ({ useEventStream: vi.fn() }))
vi.mock('@/hooks/use-scenarios', () => ({ useRunScenario: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })) }))
const getHealthAt = vi.fn()
const getChecksStatus = vi.fn()
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(() => Promise.resolve({})),
    getCoroutineTimeline: vi.fn(() => new Promise(() => {})),
    getMetrics: vi.fn(() => new Promise(() => {})),
    getSessionEvents: vi.fn(() => Promise.resolve([])),
    getRounds: vi.fn(() => new Promise(() => {})),
    getHealthAt: (...a: unknown[]) => getHealthAt(...a),
    getChecksStatus: (...a: unknown[]) => getChecksStatus(...a),
  },
}))
vi.mock('./DispatcherOverview', () => ({ DispatcherOverview: () => null }))
vi.mock('./SessionMetrics', () => ({ SessionMetrics: () => null }))
vi.mock('./export/ExportMenu', () => ({ ExportMenu: () => null }))
vi.mock('./replay/RecordConfirmModal', () => ({ RecordConfirmModal: () => null }))
vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn(() => vi.fn()) }))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))
vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<typeof import('framer-motion')>('framer-motion')
  const pass = ({ children, ...p }: Record<string, unknown>) => <div {...(p as object)}>{children as ReactNode}</div>
  return { ...actual, motion: { div: pass, span: pass, button: pass, circle: () => null }, AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>, LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</> }
})

import { useSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'

const ATTACHED = 'orders-app-1730900000000'
const DEMO = 'demo-session-1'

function snapshot(id: string, states: CoroutineState[], over: Partial<SessionSnapshot> = {}): SessionSnapshot {
  return {
    sessionId: id,
    coroutineCount: states.length,
    eventCount: 10,
    coroutines: states.map((state, i) => ({ id: `c-${i + 1}`, jobId: `j${i}`, parentId: i === 0 ? null : 'c-1', scopeId: 's', label: `task-${i + 1}`, state })),
    ...over,
  }
}

const failed: Incident = {
  type: 'UNGROUPED_FAILURE',
  id: 'i1',
  failure: { coroutineId: 'c-2', name: 'task-2', exceptionType: 'java.io.IOException', message: 'reset', failedAtSeq: 9, detailsDropped: false },
  sortSeq: 9,
}
const trimmed: Incident = {
  type: 'UNGROUPED_FAILURE',
  id: 'i2',
  failure: { coroutineId: 'gone', name: 'old-task', exceptionType: 'java.lang.Error', message: 'x', failedAtSeq: 2, detailsDropped: false },
  sortSeq: 2,
}

const problems = (...incidents: Incident[]): SessionHealth =>
  health({
    state: 'PROBLEMS',
    problemCount: incidents.length,
    ungroupedFailureCount: incidents.length,
    incidents: { items: incidents, total: incidents.length },
    observations: { longLived: { items: [{ coroutineId: 'c-1', name: 'task-1', ageNanos: 40e9 }], total: 1 }, longSuspended: { items: [], total: 0 } },
  })

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

function setup(snap: SessionSnapshot, h: SessionHealth) {
  vi.mocked(useSession).mockReturnValue({ data: snap, isLoading: false, refetch: vi.fn(), dataUpdatedAt: 1 } as unknown as ReturnType<typeof useSession>)
  getHealthAt.mockResolvedValue(h)
}

describe('SessionWorkspace — Problems (Part 7, S-8, S-5)', () => {
  beforeEach(() => {
    localStorage.clear()
    getHealthAt.mockReset()
    getChecksStatus.mockReset()
    getChecksStatus.mockResolvedValue({ sessionId: 'x', lastRun: null, newEventsSince: null })
    vi.mocked(useEventStream).mockReturnValue({
      events: [], isConnected: true, error: null, clearEvents: vi.fn(), droppedCount: 0, receivedCount: 0, reconnect: vi.fn(),
    } as unknown as ReturnType<typeof useEventStream>)
  })

  it('an attached app opens on Problems; the tab is first and carries no badge without problems', async () => {
    setup(snapshot(ATTACHED, ['ACTIVE' as CoroutineState], { kind: 'ATTACHED', appConnected: true, startedAtEpochMs: 1, lastIngestEpochMs: 2, lastWatermark: null }), health())
    render(<SessionWorkspace sessionId={ATTACHED} />, { wrapper })
    const tab = await screen.findByRole('tab', { name: 'Problems' })
    await waitFor(() => expect(tab).toHaveAttribute('aria-selected', 'true'))
    expect(screen.getAllByRole('tab')[0]).toBe(tab)
    expect(within(tab).queryByText(/\d/)).toBeNull()
    expect(screen.getByTestId('problems-headline')).toHaveTextContent('No problems detected in the captured coroutines')
    // The header says whether the app is connected (S-3).
    expect(screen.getByTestId('connection')).toHaveTextContent('App connected')
  })

  it('a demo with a detected problem opens on Problems with a count badge; Show selects it and focuses the Inspector', async () => {
    setup(snapshot(DEMO, ['COMPLETED' as CoroutineState, 'FAILED' as CoroutineState]), problems(failed))
    render(<SessionWorkspace sessionId={DEMO} scenarioId="exception-propagation" scenarioName="Exceptions" />, { wrapper })
    const tab = await screen.findByRole('tab', { name: 'Problems, 1 problem' })
    await waitFor(() => expect(tab).toHaveAttribute('aria-selected', 'true'))
    // A demo shows no connection pill.
    expect(screen.queryByTestId('connection')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Show task-2' }))
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('exception-card')).toHaveTextContent('java.io.IOException')
    await waitFor(() => expect(document.getElementById('inspector-heading')).toHaveFocus())
  })

  it('a clean finished demo opens on the Inspector, and Problems says nothing was detected', async () => {
    setup(snapshot(DEMO, ['COMPLETED' as CoroutineState]), health({ state: 'NO_FAILURES_PARTIAL', possiblyBlocked: { status: 'NOT_EVALUATED', reason: 'DEMO' } }))
    render(<SessionWorkspace sessionId={DEMO} scenarioId="basics" scenarioName="Basics" />, { wrapper })
    await waitFor(() => expect(getHealthAt).toHaveBeenCalled())
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true'))
    await userEvent.click(screen.getByRole('tab', { name: 'Problems' }))
    expect(screen.getByTestId('problems-headline')).toHaveTextContent('No failures detected in the captured coroutines')
    expect(screen.getByText(/Possibly blocked: not evaluated \(demo session/)).toBeInTheDocument()
  })

  it("a card carries the health's badge (Part 7, P-9)", async () => {
    const blocked: Incident = {
      type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'c-1', name: 'task-1', roundId: null, anchorId: null, lastThread: null,
      dispatcher: null, lastSuspension: null, lastSuspensionProvenance: null, lastRunStart: { kind: 'STARTED', appNanos: 0, seq: 1 },
      runningForNanos: 11e9, transitions: [], transitionsGap: false, firstObservedRunning: true, eventLoopClue: null, sortSeq: 1,
    }
    setup(snapshot(ATTACHED, ['ACTIVE' as CoroutineState], { kind: 'ATTACHED', appConnected: true }), health({ state: 'PROBLEMS', problemCount: 1, possiblyBlockedCount: 1, incidents: { items: [blocked], total: 1 } }))
    render(<SessionWorkspace sessionId={ATTACHED} />, { wrapper })
    await screen.findByRole('tab', { name: 'Problems, 1 problem' })
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    expect(await screen.findByText('Possibly blocked', { selector: '[data-look-part="node-badge"]' })).toBeInTheDocument()
  })

  it('Show on a problem the tree does not draw opens it from the incident', async () => {
    setup(snapshot(DEMO, ['COMPLETED' as CoroutineState, 'FAILED' as CoroutineState]), problems(failed, trimmed))
    render(<SessionWorkspace sessionId={DEMO} scenarioId="x" scenarioName="X" />, { wrapper })
    await screen.findByRole('tab', { name: 'Problems, 2 problems' })
    await userEvent.click(screen.getByRole('tab', { name: 'Problems, 2 problems' }))
    await userEvent.click(screen.getByRole('button', { name: 'Show old-task' }))
    expect(screen.getByTestId('problem-only-note')).toHaveTextContent('Not drawn in the tree at this moment')
    expect(screen.getByTestId('exception-card')).toHaveTextContent('java.lang.Error')
  })

  it('Long-lived (n) applies the tree filter, and the state bar has no checks chip', async () => {
    setup(snapshot(ATTACHED, ['ACTIVE' as CoroutineState], { kind: 'ATTACHED', appConnected: false }), problems(failed))
    render(<SessionWorkspace sessionId={ATTACHED} />, { wrapper })
    await userEvent.click(await screen.findByRole('button', { name: 'Long-lived (1)' }))
    expect(screen.getByRole('button', { name: /^Long-lived \d+$/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('button', { name: /^Checks \d/ })).toBeNull()
    expect(screen.getByTestId('checks-status')).toHaveTextContent('Instrumentation checks: not run')
  })
})

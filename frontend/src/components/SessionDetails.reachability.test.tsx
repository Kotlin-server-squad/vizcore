import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionDetails } from './SessionDetails'
import type { SessionSnapshot, CoroutineState, CoroutineTimeline } from '@/types/api'

// RCO-06 reachability proof. Unlike the sibling SessionDetails.test.tsx (which
// stubs CoroutineTree/Graph to isolate wiring), this file renders the REAL live
// CoroutineTree + the REAL inline CoroutineSourceStack (mounted in the dock's
// right-column sourcePanel slot), and only mocks the per-coroutine timeline
// fetch — so it proves the genuine click → inline-source → file:line pipeline
// end-to-end (NOT a compile-only check). Module-scoped vi.mock() makes this
// impossible to express in the stub-mocked sibling file, hence a dedicated
// reachability test file.
//
// Surface 002 (PD-05): the live-view source is hosted INLINE in the dock — the
// right-side CoroutineSourceDrawer mount is retired — so the proof asserts the
// inline file:line jump target, not a Drawer title.

vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [] })),
  useDeleteSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
}))

vi.mock('@/hooks/use-event-stream', () => ({
  useEventStream: vi.fn(() => ({
    events: [],
    isConnected: false,
    error: null,
    clearEvents: vi.fn(),
  })),
}))

vi.mock('@/hooks/use-scenarios', () => ({
  useRunScenario: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
}))

vi.mock('@/hooks/use-event-categories', () => ({
  useEventCategories: vi.fn(() => ({
    hasChannels: false,
    hasFlowOps: false,
    hasSyncPrimitives: false,
    hasJobs: false,
    hasValidation: false,
  })),
}))

// api-client: the source drawer reaches the timeline through useCoroutineTimeline
// → apiClient.getCoroutineTimeline. Return a timeline whose suspension frame
// carries a USER-code fileName + lineNumber so the drawer renders a file:line.
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(() => Promise.resolve({})),
    getCoroutineTimeline: vi.fn(),
    // The LiveDockPanel sources its inline LeakList from useSessionMetrics →
    // apiClient.getMetrics. A leak fixture lets us assert the single dock-owned
    // amber leak mount in the live view.
    getMetrics: vi.fn(),
  },
}))

// Toast is fired by jump-to-code; mock it so HeroUI's ToastProvider is not needed.
vi.mock('@/lib/toast', () => ({ toastSuccess: vi.fn(), toastError: vi.fn() }))

// Heavy/irrelevant panels stubbed so SessionDetails mounts in jsdom without
// pulling in html2canvas / MediaRecorder / framer MotionValue loops. The live
// CoroutineTree and the inline CoroutineSourceStack are LEFT REAL.
vi.mock('./CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: () => <div data-testid="coroutine-tree-graph" />,
}))
vi.mock('./EventsList', () => ({ EventsList: () => <div data-testid="events-list" /> }))
vi.mock('./export/ExportMenu', () => ({ ExportMenu: () => <div data-testid="export-menu" /> }))
vi.mock('./StructuredConcurrencyInfo', () => ({
  StructuredConcurrencyInfo: () => <div data-testid="structured-concurrency-info" />,
}))
vi.mock('./DispatcherOverview', () => ({
  DispatcherOverview: () => <div data-testid="dispatcher-overview" />,
}))
vi.mock('./SessionMetrics', () => ({ SessionMetrics: () => <div data-testid="session-metrics" /> }))
vi.mock('./LivePill', () => ({ LivePill: () => <div data-testid="live-pill" /> }))
vi.mock('./channels/ChannelPanel', () => ({ ChannelPanel: () => <div /> }))
vi.mock('./flow/FlowPanel', () => ({ FlowPanel: () => <div /> }))
vi.mock('./sync/SyncPanel', () => ({ SyncPanel: () => <div /> }))
vi.mock('./jobs/JobPanel', () => ({ JobPanel: () => <div /> }))
vi.mock('./validation/ValidationPanel', () => ({ ValidationPanel: () => <div /> }))
vi.mock('./replay/ReplayController', () => ({ ReplayController: () => <div /> }))
vi.mock('./replay/RecordConfirmModal', () => ({ RecordConfirmModal: () => null }))

const recordReplayShim = {
  canRecord: false,
  isRecording: false,
  isArming: false,
  elapsedMs: 0,
  startRecording: vi.fn(),
  stopRecording: vi.fn(),
  confirmOpen: false,
  confirmEstimateMs: 0,
  confirmSpeed: 1,
  confirmRecord: vi.fn(),
  cancelConfirm: vi.fn(),
}
vi.mock('@/hooks/use-record-replay', () => ({ useRecordReplay: () => recordReplayShim }))

vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => <div {...props}>{children as ReactNode}</div>,
    span: ({ children, ...props }: Record<string, unknown>) => <span {...props}>{children as ReactNode}</span>,
  },
  AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
  LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))

vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn(() => vi.fn()) }))

import { useSession } from '@/hooks/use-sessions'
import { apiClient } from '@/lib/api-client'
import type { MetricsResponse } from '@/types/api'

const mockedUseSession = vi.mocked(useSession)
const mockedApiClient = vi.mocked(apiClient)

// A metrics fixture carrying two leaks so the dock's single inline LeakList
// renders the "2 potential leaks" amber badge in the live view.
function makeMetrics(): MetricsResponse {
  return {
    active: 1,
    peak: 2,
    throughputPerSec: 0,
    dispatcherUtilization: { Default: 1 },
    leaks: [
      { coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 },
      { coroutineId: 'dp-2', label: 'pollLoop', aliveMs: 51_000 },
    ],
    leakThresholdMs: 30_000,
  }
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
}

function makeSession(overrides: Partial<SessionSnapshot> = {}): SessionSnapshot {
  return {
    sessionId: 'session-1',
    coroutineCount: 1,
    eventCount: 3,
    coroutines: [
      {
        id: 'c-live',
        jobId: 'j-live',
        parentId: null,
        scopeId: 'scope-1',
        label: 'LiveWorker',
        state: 'ACTIVE' as CoroutineState,
      },
    ],
    ...overrides,
  }
}

// A timeline whose suspension frame is a user-code site (UserService.kt:42) so
// the drawer renders a clickable file:line jump target.
function makeTimeline(): CoroutineTimeline {
  return {
    coroutineId: 'c-live',
    name: 'LiveWorker',
    state: 'SUSPENDED',
    parentId: null,
    childrenIds: [],
    totalDuration: 0,
    activeDuration: 0,
    suspendedDuration: 0,
    events: [
      {
        seq: 1,
        tsNanos: 1000,
        kind: 'coroutine.suspended',
        suspensionPoint: {
          function: 'com.acme.app.UserService.loadUser',
          fileName: 'UserService.kt',
          lineNumber: 42,
          reason: 'delay',
        },
      },
    ],
  }
}

describe('SessionDetails — live source-attribution reachability (RCO-06)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedApiClient.getThreadActivity.mockResolvedValue({})
    mockedApiClient.getCoroutineTimeline.mockResolvedValue(makeTimeline())
    mockedApiClient.getMetrics.mockResolvedValue(makeMetrics())
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('clicking a live node renders the inline source panel with a real file:line frame', async () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    // No CoroutineSourceDrawer is mounted in the live view — the source is inline
    // in the dock's right column (PD-05). The retired Drawer would render a
    // "Coroutine source — {label}" title; assert it is absent.
    expect(screen.queryByText(/Coroutine source —/)).toBeNull()

    // Switch to the list view so the real CoroutineTree (not the mocked graph) renders.
    await userEvent.click(screen.getByRole('button', { name: /list view/i }))

    // Click the live coroutine node — additive onSelect wiring (08.2 idiom).
    const node = await screen.findByRole('button', { name: 'Open source for LiveWorker' })
    await userEvent.click(node)

    // The inline source panel renders the real file:line frame from the fetched
    // timeline — a user-code jump target, NOT a compile-only mount. (The single
    // suspension frame backs both the derived "Created at" and "Suspended at"
    // compact chips, so the jump target legitimately appears more than once.)
    const jumpTargets = await screen.findAllByRole('button', {
      name: 'Jump to code: UserService.kt:42',
    })
    expect(jumpTargets.length).toBeGreaterThanOrEqual(1)
    expect(jumpTargets[0]).toHaveTextContent('UserService.kt:42')

    // Still inline — no Drawer title appears after selection.
    expect(screen.queryByText(/Coroutine source —/)).toBeNull()

    // The timeline was actually fetched for the selected coroutine.
    expect(mockedApiClient.getCoroutineTimeline).toHaveBeenCalledWith('session-1', 'c-live')
  })

  it('highlights the selected node with ring-2 ring-primary while its inline source is shown', async () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    await userEvent.click(screen.getByRole('button', { name: /list view/i }))

    const node = await screen.findByRole('button', { name: 'Open source for LiveWorker' })
    await userEvent.click(node)

    // The inline source panel renders its file:line jump target…
    expect(
      (await screen.findAllByRole('button', { name: 'Jump to code: UserService.kt:42' })).length,
    ).toBeGreaterThanOrEqual(1)

    // …and the selected node carries the persistent selection ring (no Drawer
    // focus-trap, so the node stays directly reachable inline).
    expect(node.className).toContain('ring-2')
    expect(node.className).toContain('ring-primary')
  })

  it('shows the placeholder + does not fetch the timeline until a node is clicked (no eager fetch)', () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    // Before any selection the inline source slot shows its muted placeholder and
    // no timeline is fetched (the enabled-guard keeps the query disabled while
    // coroutineId is null, D-08). No retired-Drawer title appears.
    expect(screen.getByText('Select a coroutine to view its source')).toBeInTheDocument()
    expect(screen.queryByText(/Coroutine source —/)).toBeNull()
    expect(mockedApiClient.getCoroutineTimeline).not.toHaveBeenCalled()
  })

  // ── Surface 001 IDE-dock reconcile (Task 2) ──────────────────────────────

  it('renders the LiveDockPanel (metric strip + live list) in the live view, with a single inline leak list', async () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    // The dock header strip mounts the metric tiles + LIVE/DEMO pill (un-buried
    // from the Threads tab). Mounted exactly once.
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
    expect(screen.getByTestId('live-pill')).toBeInTheDocument()

    // The live list still renders inside the dock's left column.
    expect(screen.getByText(/What's running now/)).toBeInTheDocument()

    // The dock owns the single inline amber LeakList (fed by getMetrics). The
    // "2 potential leaks" badge appears exactly once — no duplicate leak mount.
    const leakBadges = await screen.findAllByText('2 potential leaks')
    expect(leakBadges).toHaveLength(1)
  })

  it('renders the existing tabs with NO dock and no source affordances in the read-only shared view', async () => {
    render(<SessionDetails sessionId="session-1" readOnly />, { wrapper: createWrapper() })

    // PD-01 back-compat: the read-only shared view keeps the standalone tabbed
    // layout — the dock metric strip + LIVE pill are NOT mounted.
    expect(screen.queryByTestId('session-metrics')).toBeNull()
    expect(screen.queryByTestId('live-pill')).toBeNull()

    // The live list still renders (presentational).
    expect(screen.getByText(/What's running now/)).toBeInTheDocument()

    // Switch to the REAL CoroutineTree (list view) and prove the nodes carry no
    // clickable source affordance — onSelect is wired only in the live view.
    await userEvent.click(screen.getByRole('button', { name: /list view/i }))
    expect(screen.getByText('LiveWorker')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Open source for LiveWorker' })).toBeNull()

    // And no protected metrics fetch fires in the shared shell (no Bearer).
    expect(mockedApiClient.getMetrics).not.toHaveBeenCalled()
  })
})

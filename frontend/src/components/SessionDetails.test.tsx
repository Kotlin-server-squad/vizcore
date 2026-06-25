import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionDetails } from './SessionDetails'
import type { SessionSnapshot, CoroutineState, ThreadActivity } from '@/types/api'

// Mock all hooks the component depends on
vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [] })),
  useDeleteSession: vi.fn(() => ({
    mutateAsync: vi.fn(),
    isPending: false,
  })),
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
  useRunScenario: vi.fn(() => ({
    mutateAsync: vi.fn(),
    isPending: false,
  })),
}))

// The real use-thread-activity hooks run against a mocked apiClient so the
// Threads tab integration test exercises the genuine wire-shape pipeline
// (hook -> ThreadTimeline) end-to-end. Default resolves an empty map.
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(() => Promise.resolve({})),
  },
}))

vi.mock('@/hooks/use-event-categories', () => ({
  useEventCategories: vi.fn(() => ({
    hasChannels: false,
    hasFlowOps: false,
    hasSyncPrimitives: false,
    hasJobs: false,
    hasValidation: true,
  })),
}))

// Mock child components to isolate SessionDetails. The graph/list/events mocks
// expose the props they receive so replay-vs-live data-source tests can assert
// what was fed to them.
vi.mock('./CoroutineTree', () => ({
  CoroutineTree: ({ coroutines }: { coroutines: { id: string }[] }) => (
    <div data-testid="coroutine-tree" data-count={coroutines.length}>
      CoroutineTree
    </div>
  ),
}))

vi.mock('./CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: ({ coroutines }: { coroutines: { id: string }[] }) => (
    <div
      data-testid="coroutine-tree-graph"
      data-count={coroutines.length}
      data-ids={coroutines.map((c) => c.id).join(',')}
    >
      CoroutineTreeGraph
    </div>
  ),
}))

vi.mock('./EventsList', () => ({
  EventsList: ({ events }: { events: { seq: number }[] }) => (
    <div data-testid="events-list" data-count={events.length}>
      EventsList
    </div>
  ),
}))

// ExportMenu mocked to a simple trigger so we can assert it is mounted without
// pulling in html2canvas / toast plumbing.
vi.mock('./export/ExportMenu', () => ({
  ExportMenu: () => <div data-testid="export-menu">ExportMenu</div>,
}))

vi.mock('./StructuredConcurrencyInfo', () => ({
  StructuredConcurrencyInfo: () => <div data-testid="structured-concurrency-info" />,
}))

vi.mock('./DispatcherOverview', () => ({
  DispatcherOverview: () => <div data-testid="dispatcher-overview" />,
}))

vi.mock('./SessionMetrics', () => ({
  SessionMetrics: ({ sessionId, isLive }: { sessionId: string; isLive?: boolean }) => (
    <div data-testid="session-metrics" data-session={sessionId} data-live={String(!!isLive)} />
  ),
}))

vi.mock('./LivePill', () => ({
  LivePill: ({ streamEnabled }: { streamEnabled: boolean }) => (
    <div data-testid="live-pill" data-stream={String(streamEnabled)} />
  ),
}))

vi.mock('./channels/ChannelPanel', () => ({
  ChannelPanel: () => <div data-testid="channel-panel" />,
}))

vi.mock('./flow/FlowPanel', () => ({
  FlowPanel: () => <div data-testid="flow-panel" />,
}))

vi.mock('./sync/SyncPanel', () => ({
  SyncPanel: () => <div data-testid="sync-panel" />,
}))

vi.mock('./jobs/JobPanel', () => ({
  JobPanel: () => <div data-testid="job-panel" />,
}))

vi.mock('./validation/ValidationPanel', () => ({
  ValidationPanel: () => <div data-testid="validation-panel" />,
}))

// ReplayController mocked to a thin shim exposing whether replay is active and
// the toggle target index, so SessionDetails replay wiring can be tested
// without the real framer-motion MotionValue loop.
vi.mock('./replay/ReplayController', () => ({
  ReplayController: ({
    replay,
  }: {
    replay: { totalEvents: number; currentIndex: number }
  }) => (
    <div
      data-testid="replay-controller"
      data-total={replay.totalEvents}
      data-index={replay.currentIndex}
    />
  ),
}))

// useRecordReplay mocked to an inert shim — the scripted WebM pipeline is unit-
// tested in use-record-replay.test.ts; here we only need SessionDetails to mount
// without touching MediaRecorder/captureStream (absent in jsdom). The shim's
// isArming/isRecording flags are overridable per-test so the CR-01 D-03 gate
// (suppress the auto-seek-to-end while a recording is arming) can be exercised.
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
vi.mock('@/hooks/use-record-replay', () => ({
  useRecordReplay: () => recordReplayShim,
}))

// RecordConfirmModal mocked to a no-op so SessionDetails mounts without HeroUI
// Modal portal machinery in these wiring tests.
vi.mock('./replay/RecordConfirmModal', () => ({
  RecordConfirmModal: () => null,
}))

vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => <div {...props}>{children as ReactNode}</div>,
    span: ({ children, ...props }: Record<string, unknown>) => <span {...props}>{children as ReactNode}</span>,
  },
  AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: vi.fn(() => vi.fn()),
}))

import { useSession, useDeleteSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'
import { apiClient } from '@/lib/api-client'

const mockedUseSession = vi.mocked(useSession)
const mockedUseEventStream = vi.mocked(useEventStream)
const mockedUseDeleteSession = vi.mocked(useDeleteSession)
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

function makeSession(overrides: Partial<SessionSnapshot> = {}): SessionSnapshot {
  return {
    sessionId: 'session-1',
    coroutineCount: 3,
    eventCount: 15,
    coroutines: [
      {
        id: 'c-root',
        jobId: 'j-root',
        parentId: null,
        scopeId: 'scope-1',
        label: 'root',
        state: 'ACTIVE' as CoroutineState,
      },
    ],
    ...overrides,
  }
}

describe('SessionDetails', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders loading state', () => {
    mockedUseSession.mockReturnValue({
      data: undefined,
      isLoading: true,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionDetails sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    // HeroUI Spinner renders a div with aria-label="Loading"
    expect(screen.getByLabelText('Loading')).toBeInTheDocument()
  })

  it('renders error state when session not found', () => {
    mockedUseSession.mockReturnValue({
      data: undefined,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionDetails sessionId="nonexistent" />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByText('Session not found')).toBeInTheDocument()
  })

  it('displays session info with coroutine and event counts', () => {
    const session = makeSession({
      sessionId: 'session-abc',
      coroutineCount: 5,
      eventCount: 42,
    })

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionDetails sessionId="session-abc" />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByText('Session Details')).toBeInTheDocument()
    expect(screen.getByText('session-abc')).toBeInTheDocument()
    expect(screen.getByText('5 coroutines')).toBeInTheDocument()
    expect(screen.getByText('42 events')).toBeInTheDocument()
  })

  it('renders scenario name chip when scenarioName is provided', () => {
    const session = makeSession({ coroutineCount: 1 })

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(
      <SessionDetails
        sessionId="session-1"
        scenarioId="sc-1"
        scenarioName="Producer-Consumer"
      />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByText('Producer-Consumer')).toBeInTheDocument()
  })

  it('shows "Enable Live Stream" button by default', () => {
    const session = makeSession()

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionDetails sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByText('Enable Live Stream')).toBeInTheDocument()
  })

  it('shows "Run Scenario" enabled when coroutineCount is 0 (not started)', () => {
    const session = makeSession({
      coroutineCount: 0,
      coroutines: [],
    })

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(
      <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // Not-started state: button is "Run Scenario" and enabled
    const runButton = screen.getByRole('button', { name: /run scenario/i })
    expect(runButton).not.toBeDisabled()
  })

  it('does NOT show the disabled "Scenario Running" button when all coroutines are terminal', () => {
    const session = makeSession({
      coroutineCount: 2,
      coroutines: [
        {
          id: 'c1',
          jobId: 'j1',
          parentId: null,
          scopeId: 'scope-1',
          label: 'root',
          state: 'COMPLETED' as CoroutineState,
        },
        {
          id: 'c2',
          jobId: 'j2',
          parentId: 'c1',
          scopeId: 'scope-1',
          label: 'child',
          state: 'COMPLETED' as CoroutineState,
        },
      ],
    })

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(
      <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // Completed state: the disabled "Scenario Running" button should NOT be present
    expect(screen.queryByRole('button', { name: /scenario running/i })).not.toBeInTheDocument()
    // Instead, an explicit completed indicator button should appear
    expect(screen.getByRole('button', { name: /scenario completed/i })).toBeInTheDocument()
  })

  it('shows disabled "Scenario Running" button when some coroutines are still active (in-progress)', () => {
    const session = makeSession({
      coroutineCount: 2,
      coroutines: [
        {
          id: 'c1',
          jobId: 'j1',
          parentId: null,
          scopeId: 'scope-1',
          label: 'root',
          state: 'ACTIVE' as CoroutineState,
        },
        {
          id: 'c2',
          jobId: 'j2',
          parentId: 'c1',
          scopeId: 'scope-1',
          label: 'child',
          state: 'COMPLETED' as CoroutineState,
        },
      ],
    })

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(
      <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // In-progress: "Scenario Running" disabled button is shown
    const runningButton = screen.getByRole('button', { name: /scenario running/i })
    expect(runningButton).toBeDisabled()
  })

  it('lets the user switch the live stream OFF on scenario pages (WR-04: auto-enable only once)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(
      <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // The scenario auto-enables the stream once on mount
    const toggle = await screen.findByRole('button', { name: /live stream active/i })

    // Disabling must stick — the old auto-enable effect re-armed instantly
    await userEvent.click(toggle)
    expect(await screen.findByRole('button', { name: /enable live stream/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /live stream active/i })).not.toBeInTheDocument()
  })

  it('"Clear" clears the live events without deleting the session (WR-05)', async () => {
    const refetch = vi.fn()
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch,
    } as unknown as ReturnType<typeof useSession>)

    const clearEvents = vi.fn()
    mockedUseEventStream.mockReturnValue({
      events: [],
      isConnected: true,
      error: null,
      clearEvents,
    } as unknown as ReturnType<typeof useEventStream>)

    const deleteMutateAsync = vi.fn()
    mockedUseDeleteSession.mockReturnValue({
      mutateAsync: deleteMutateAsync,
      isPending: false,
    } as unknown as ReturnType<typeof useDeleteSession>)

    const confirmSpy = vi.spyOn(window, 'confirm')

    render(
      <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    await userEvent.click(screen.getByRole('button', { name: /clear/i }))

    // Clear only empties the event list (plus a snapshot refetch)...
    expect(clearEvents).toHaveBeenCalledTimes(1)
    expect(refetch).toHaveBeenCalled()
    // ...and must NOT delete the session or even prompt for it
    expect(deleteMutateAsync).not.toHaveBeenCalled()
    expect(confirmSpy).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    // Restore the default useEventStream mock for subsequent tests
    mockedUseEventStream.mockImplementation(() => ({
      events: [],
      isConnected: false,
      error: null,
      clearEvents: vi.fn(),
    }) as unknown as ReturnType<typeof useEventStream>)
  })
})

describe('SessionDetails - session refetch max-wait under sustained stream (CR-02)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    // Restore the default useEventStream mock implementation for other tests
    mockedUseEventStream.mockImplementation(() => ({
      events: [],
      isConnected: false,
      error: null,
      clearEvents: vi.fn(),
    }))
  })

  it('refetches the session snapshot at least once per max-wait window while events keep arriving', () => {
    const refetch = vi.fn()
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch,
    } as unknown as ReturnType<typeof useSession>)

    // Mutable live-event list driven through the useEventStream mock
    const liveEvents: unknown[] = []
    mockedUseEventStream.mockImplementation(() => ({
      events: [...liveEvents],
      isConnected: true,
      error: null,
      clearEvents: vi.fn(),
    }) as unknown as ReturnType<typeof useEventStream>)

    // scenarioId auto-enables the live stream (streamEnabled -> true)
    const { rerender } = render(
      <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // Sustained stream: a new event every 250ms (below the 500ms debounce
    // window) for 2000ms total — longer than the 1500ms max-wait cap. A pure
    // trailing-edge debounce would be reset forever and never refetch; the
    // max-wait cap must flush at least once before the stream stops.
    for (let i = 0; i < 8; i++) {
      liveEvents.push({ kind: 'CoroutineCreated' })
      rerender(
        <SessionDetails sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      )
      act(() => {
        vi.advanceTimersByTime(250)
      })
    }

    expect(refetch.mock.calls.length).toBeGreaterThanOrEqual(1)
  })
})

describe('SessionDetails - Threads tab wire shape (UAT gap 1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('Threads tab renders thread activity from the real Map wire shape (UAT gap 1)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    // Byte-for-byte the shape curl showed in UAT round 2:
    // Map<threadId, ThreadEvent[]> — NOT {threads, dispatcherInfo}.
    const wireMap: ThreadActivity = {
      '57': [
        { coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' },
        { coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 5000, eventType: 'RELEASED', dispatcherName: 'Default' },
      ],
      '80': [
        { coroutineId: 'c-2', threadId: 80, threadName: 'worker-2', timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'IO' },
      ],
    }
    mockedApiClient.getThreadActivity.mockResolvedValue(wireMap)

    render(<SessionDetails sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    // Activate the Threads tab
    await userEvent.click(screen.getByRole('tab', { name: 'Threads' }))

    // 2. Thread names from the wire map are rendered (values, not test names)
    expect(await screen.findByText('worker-1')).toBeInTheDocument()
    expect(screen.getByText('worker-2')).toBeInTheDocument()

    // 3. At least one ASSIGNED chip is rendered
    expect(screen.getAllByText('ASSIGNED').length).toBeGreaterThanOrEqual(1)

    // 1. The UAT-observed permanent empty state is gone
    expect(screen.queryByText('No thread activity data available yet')).toBeNull()
  })
})

describe('SessionDetails - replay mode (RPLY-01/02/03, D-01..18)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedUseEventStream.mockImplementation(() => ({
      events: [],
      isConnected: false,
      error: null,
      clearEvents: vi.fn(),
    }) as unknown as ReturnType<typeof useEventStream>)
  })

  // A small captured session: two coroutines created+started, one completed.
  // Fed through the stored-events path so replay folds them via projections.
  const replayEvents = [
    {
      kind: 'coroutine.created',
      sessionId: 'session-1',
      seq: 1,
      tsNanos: 1000,
      coroutineId: 'c1',
      jobId: 'j1',
      parentCoroutineId: null,
      scopeId: 's1',
      label: 'root',
    },
    {
      kind: 'coroutine.started',
      sessionId: 'session-1',
      seq: 2,
      tsNanos: 2000,
      coroutineId: 'c1',
      jobId: 'j1',
      parentCoroutineId: null,
      scopeId: 's1',
      label: 'root',
    },
    {
      kind: 'coroutine.created',
      sessionId: 'session-1',
      seq: 3,
      tsNanos: 3000,
      coroutineId: 'c2',
      jobId: 'j2',
      parentCoroutineId: 'c1',
      scopeId: 's1',
      label: 'child',
    },
  ]

  function mountWithStoredEvents() {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    return render(<SessionDetails sessionId="session-1" />, {
      wrapper: createWrapper(),
    })
  }

  it('shows a Replay toggle button always (D-01)', () => {
    mountWithStoredEvents()
    expect(screen.getByRole('button', { name: /^replay$/i })).toBeInTheDocument()
  })

  it('mounts the ExportMenu in the toolbar', () => {
    mountWithStoredEvents()
    expect(screen.getByTestId('export-menu')).toBeInTheDocument()
  })

  it('entering replay seeks to end + shows REPLAY chip + sticky controller (D-03/D-13/D-15)', async () => {
    // Provide stored events so totalEvents > 0
    vi.doMock('@/hooks/use-sessions')
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: replayEvents } as unknown as ReturnType<
      typeof useSessionEvents
    >)

    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    expect(screen.getByText('REPLAY')).toBeInTheDocument()
    const controller = screen.getByTestId('replay-controller')
    expect(controller).toBeInTheDocument()
    // Seeked to end → totalEvents reflects all captured events
    expect(controller).toHaveAttribute('data-total', String(replayEvents.length))
    // Exit toggle now present
    expect(screen.getByRole('button', { name: /exit replay/i })).toBeInTheDocument()
  })

  it('in replay, the Coroutines graph renders projected coroutines from visibleEvents (D-17)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: replayEvents } as unknown as ReturnType<
      typeof useSessionEvents
    >)

    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    // At end: both c1 and c2 projected from the events (not session.coroutines,
    // which has only c-root from makeSession).
    const graph = screen.getByTestId('coroutine-tree-graph')
    expect(graph).toHaveAttribute('data-ids', 'c1,c2')
  })

  it('shows LiveDataNotice on a projection-backed tab while replaying (D-17)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: replayEvents } as unknown as ReturnType<
      typeof useSessionEvents
    >)

    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    // Validation tab is projection-backed and always present.
    await userEvent.click(screen.getByRole('tab', { name: 'Validation' }))
    expect(screen.getByTestId('live-data-notice')).toBeInTheDocument()
  })

  it('shows a clickable new-events badge for events buffered during replay; clicking exits (D-02/D-04)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: replayEvents } as unknown as ReturnType<
      typeof useSessionEvents
    >)

    // Live stream returns more events than the frozen replay snapshot.
    mockedUseEventStream.mockImplementation(() => ({
      events: [
        ...replayEvents,
        { ...replayEvents[2], seq: 4, coroutineId: 'c3', label: 'late' },
        { ...replayEvents[2], seq: 5, coroutineId: 'c4', label: 'later' },
      ],
      isConnected: true,
      error: null,
      clearEvents: vi.fn(),
    }) as unknown as ReturnType<typeof useEventStream>)

    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    // 2 events arrived after the frozen snapshot of 3.
    const badge = await screen.findByRole('button', {
      name: /exit replay and jump to live/i,
    })
    expect(badge).toHaveTextContent('2 new events')

    await userEvent.click(badge)
    // Back to live: REPLAY chip gone, Replay toggle shown again.
    expect(screen.queryByText('REPLAY')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^replay$/i })).toBeInTheDocument()
  })
})

describe('SessionDetails active-only "What\'s running now" view (RCO-06, D-08)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function coro(id: string, state: string) {
    return {
      id,
      jobId: `j-${id}`,
      parentId: null,
      scopeId: 'scope-1',
      label: id,
      state: state as CoroutineState,
    }
  }

  function mountSession(coroutines: ReturnType<typeof coro>[]) {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: coroutines.length, coroutines }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    return render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })
  }

  it('renders only non-terminal coroutines in the graph by default', () => {
    mountSession([
      coro('a-active', 'ACTIVE'),
      coro('b-suspended', 'SUSPENDED'),
      coro('c-completed', 'COMPLETED'),
      coro('d-failed', 'FAILED'),
    ])

    const graph = screen.getByTestId('coroutine-tree-graph')
    // Terminal (COMPLETED/FAILED) filtered out; active set keeps order.
    expect(graph).toHaveAttribute('data-ids', 'a-active,b-suspended')
  })

  it('shows the "What\'s running now" section title', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    expect(screen.getByText("What's running now")).toBeInTheDocument()
  })

  it('shows "Show completed (N)" with the terminal count', () => {
    mountSession([
      coro('a-active', 'ACTIVE'),
      coro('c-completed', 'COMPLETED'),
      coro('d-cancelled', 'CANCELLED'),
    ])
    expect(screen.getByText('Show completed (2)')).toBeInTheDocument()
  })

  it('does not show "Show completed" when nothing has completed', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    expect(screen.queryByText(/Show completed/)).toBeNull()
  })

  it('shows "N more coroutines" when the active set exceeds the node cap', () => {
    const many = Array.from({ length: 205 }, (_, i) => coro(`a-${i}`, 'ACTIVE'))
    mountSession(many)

    // NODE_CAP = 200 → 5 more
    expect(screen.getByText('5 more coroutines')).toBeInTheDocument()
    const graph = screen.getByTestId('coroutine-tree-graph')
    expect(graph).toHaveAttribute('data-count', '200')
  })

  it('mounts the docked metrics panel (LivePill + SessionMetrics) below the live canvas (Delta L1)', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    // The dock lives in the live "Coroutines" tab region (default selected) —
    // no tab switch needed; it is always visible below the canvas.
    const metrics = screen.getByTestId('session-metrics')
    expect(metrics).toBeInTheDocument()
    expect(metrics).toHaveAttribute('data-session', 'session-1')
    // The LIVE/DEMO pill renders in the dock header.
    expect(screen.getByTestId('live-pill')).toBeInTheDocument()
  })

  it('mounts SessionMetrics exactly once (single /metrics poll — no double-mount, T-08.1-04)', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('no longer renders SessionMetrics under the Threads tab (Delta L1 removal)', async () => {
    mountSession([coro('a-active', 'ACTIVE')])
    await userEvent.click(screen.getByRole('tab', { name: /threads/i }))
    // HeroUI renders only the selected tab panel: on Threads, the live-region
    // dock (which now owns SessionMetrics) is unmounted, so no SessionMetrics
    // appears here. The Threads tab itself must NOT add one back.
    expect(screen.queryByTestId('session-metrics')).toBeNull()
    // DispatcherOverview (thread lanes) may remain under Threads.
    expect(screen.getByTestId('dispatcher-overview')).toBeInTheDocument()
  })

  it('shows the "No live coroutines yet" empty state when nothing is active', () => {
    mountSession([coro('c-completed', 'COMPLETED')])
    expect(screen.getByText('No live coroutines yet')).toBeInTheDocument()
  })
})

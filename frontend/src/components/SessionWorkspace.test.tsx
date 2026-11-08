import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import { LOOK_STORAGE_KEY, LookProvider, forgetVisitLook } from '@/looks/look-context'
import { CARDS, LOOKS } from '@/looks/registry'
import type { SessionSnapshot, CoroutineState, ThreadActivity, VizEvent } from '@/types/api'

// Mock all hooks the component depends on
vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [] })),
  useDeleteSession: vi.fn(() => ({
    mutateAsync: vi.fn(),
    isPending: false,
  })),
  useCreateSession: vi.fn(() => ({
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
    droppedCount: 0,
    receivedCount: 0,
    reconnect: vi.fn(),
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
    getCoroutineTimeline: vi.fn(() => new Promise(() => {})),
    getTimelineAt: vi.fn(() => new Promise(() => {})),
    getHierarchyAt: vi.fn(() => Promise.resolve([])),
  },
}))

vi.mock('@/hooks/use-event-categories', async () => ({
  categorizeEvents: (await vi.importActual<typeof import('@/hooks/use-event-categories')>('@/hooks/use-event-categories')).categorizeEvents,
  useEventCategories: vi.fn(() => ({
    hasChannels: false,
    hasFlowOps: false,
    hasSyncPrimitives: false,
    hasJobs: false,
    hasValidation: true,
  })),
}))

// Mock child components to isolate SessionWorkspace. The graph/list/events mocks
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
  CoroutineTreeGraph: ({ coroutines, onSelect }: { coroutines: { id: string }[]; onSelect?: (id: string) => void }) => (
    <div
      data-testid="coroutine-tree-graph"
      data-count={coroutines.length}
      data-ids={coroutines.map((c) => c.id).join(',')}
    >
      CoroutineTreeGraph
      {onSelect &&
        coroutines.map(c => (
          <button key={c.id} type="button" data-testid={`select-${c.id}`} onClick={() => onSelect(c.id)}>
            select {c.id}
          </button>
        ))}
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


vi.mock('./DispatcherOverview', () => ({
  DispatcherOverview: () => <div data-testid="dispatcher-overview" />,
}))

vi.mock('./SessionMetrics', () => ({
  SessionMetrics: ({ sessionId, isLive }: { sessionId: string; isLive?: boolean }) => (
    <div data-testid="session-metrics" data-session={sessionId} data-live={String(!!isLive)} />
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
// the toggle target index, so SessionWorkspace replay wiring can be tested
// without the real framer-motion MotionValue loop.
vi.mock('./replay/ReplayController', () => ({
  ReplayController: ({
    replay,
    startNote,
  }: {
    replay: { totalEvents: number; currentIndex: number }
    startNote?: string
  }) => (
    <div
      data-testid="replay-controller"
      data-total={replay.totalEvents}
      data-index={replay.currentIndex}
      data-start-note={startNote ?? ''}
    />
  ),
}))

// useRecordReplay mocked to an inert shim — the scripted WebM pipeline is unit-
// tested in use-record-replay.test.ts; here we only need SessionWorkspace to mount
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

// RecordConfirmModal mocked to a no-op so SessionWorkspace mounts without HeroUI
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
import { useRunScenario } from '@/hooks/use-scenarios'
import { useEventStream } from '@/hooks/use-event-stream'
import { ApiError, NetworkError, RateLimitedError } from '@/lib/api-errors'
import { apiClient } from '@/lib/api-client'

const mockedUseSession = vi.mocked(useSession)
const mockedUseEventStream = vi.mocked(useEventStream)
const mockedUseDeleteSession = vi.mocked(useDeleteSession)
const mockedApiClient = vi.mocked(apiClient)

const share = { token: 'tok', asOfSeq: 30, cut: 'cut-1', unavailable: null }

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

describe('SessionWorkspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders loading state', () => {
    mockedUseSession.mockReturnValue({
      data: undefined,
      isLoading: true,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionWorkspace sessionId="session-1" />, {
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

    render(<SessionWorkspace sessionId="nonexistent" />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByText('Session not found')).toBeInTheDocument()
  })

  it.each([
    ['404', new ApiError(404, 'Session not found'), 'not-found', /not found/i],
    ['401', new ApiError(401, 'Unauthorized'), 'unauthorized', /sign-in required/i],
    ['429', new RateLimitedError(15_000), 'rate-limited', /rate limited/i],
    ['5xx', new ApiError(500, 'boom'), 'server', /server error/i],
    ['network', new NetworkError(), 'network', /can't reach vizcore/i],
  ])('a %s load failure renders its own truthful state (#139)', (_, error, kind, title) => {
    mockedUseSession.mockReturnValue({
      data: undefined,
      isLoading: false,
      error,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionWorkspace sessionId="s-err" />, { wrapper: createWrapper() })

    const state = screen.getByTestId('error-state')
    expect(state).toHaveAttribute('data-kind', kind)
    expect(state).toHaveTextContent(title)
    if (kind !== 'not-found') {
      expect(screen.queryByText('Session not found')).not.toBeInTheDocument()
    }
    expect(screen.getByRole('button', { name: 'Back to sessions' })).toBeInTheDocument()
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

    render(<SessionWorkspace sessionId="session-abc" />, {
      wrapper: createWrapper(),
    })

    // The heading names the session, not a generic "Session Details" (#145).
    expect(screen.getByRole('heading', { name: 'session-abc' })).toBeInTheDocument()
    expect(screen.queryByText('Session Details')).not.toBeInTheDocument()
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
      <SessionWorkspace
        sessionId="session-1"
        scenarioId="sc-1"
        scenarioName="Producer-Consumer"
      />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByText('Producer-Consumer')).toBeInTheDocument()
  })

  it('offers Resume view while the view is paused', () => {
    const session = makeSession()

    mockedUseSession.mockReturnValue({
      data: session,
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionWorkspace sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByRole('button', { name: 'Resume view' })).toBeInTheDocument()
    expect(screen.getByTestId('view-status')).toHaveTextContent('View paused')
  })

  it('opens with the live stream on for a just-connected app (startLive, #126)', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(<SessionWorkspace sessionId="session-1" startLive />, {
      wrapper: createWrapper(),
    })

    expect(screen.getByRole('button', { name: 'Pause view' })).toBeInTheDocument()
    expect(mockedUseEventStream).toHaveBeenLastCalledWith('session-1', true, false)
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
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // Not-started state: button is "Run Scenario" and enabled
    const runButton = screen.getByRole('button', { name: /run scenario/i })
    expect(runButton).not.toBeDisabled()
  })

  it('offers Run again once a run has finished (S-13)', () => {
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
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // Finished: today's disabled button is replaced by Run again (S-13).
    expect(screen.queryByRole('button', { name: /scenario finished/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run again' })).toBeEnabled()
  })

  it('offers Run again while the run still goes on: the old run keeps running in its session', () => {
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
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    expect(screen.queryByRole('button', { name: /scenario running/i })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run again' })).toBeEnabled()
  })

  it('knows its demo from the session when the address does not name it (S-13)', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ demoId: 'nested' }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    render(<SessionWorkspace sessionId="scenario-Nested-1793993830683" />, { wrapper: createWrapper() })
    expect(screen.getByRole('button', { name: 'Run again' })).toBeEnabled()
  })

  it('offers no run actions for a session without a demo', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ demoId: null }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    render(<SessionWorkspace sessionId="my-app-1793993830683" />, { wrapper: createWrapper() })
    expect(screen.queryByRole('button', { name: 'Run again' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /run scenario/i })).not.toBeInTheDocument()
  })

  it('starts the run itself when Run again opened it, and drops the request from the address', async () => {
    const mutateAsync = vi.fn(() => Promise.resolve({ sessionId: 'session-1' }))
    vi.mocked(useRunScenario).mockReturnValue({ mutateAsync, isPending: false } as unknown as ReturnType<typeof useRunScenario>)
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 0, coroutines: [] }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const onAutoRunConsumed = vi.fn()
    render(
      <SessionWorkspace sessionId="session-1" scenarioId="nested" scenarioName="Nested" autoRun onAutoRunConsumed={onAutoRunConsumed} />,
      { wrapper: createWrapper() },
    )
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith({ scenarioId: 'nested', sessionId: 'session-1' }))
    expect(onAutoRunConsumed).toHaveBeenCalledTimes(1)
  })

  it('lets the user switch the live stream OFF on scenario pages (WR-04: auto-enable only once)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    render(
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    // The scenario auto-enables the stream once on mount
    const toggle = await screen.findByRole('button', { name: 'Pause view' })

    // Disabling must stick — the old auto-enable effect re-armed instantly
    await userEvent.click(toggle)
    expect(await screen.findByRole('button', { name: 'Resume view' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pause view' })).not.toBeInTheDocument()
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
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /clear events/i }))

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

describe('SessionWorkspace - live events do not drive snapshot refetches (#137)', () => {
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
      droppedCount: 0,
      receivedCount: 0,
      reconnect: vi.fn(),
    }))
  })

  it('never refetches the snapshot per live event — the stream\'s throttled invalidation owns that', () => {
    const refetch = vi.fn()
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch,
    } as unknown as ReturnType<typeof useSession>)

    const liveEvents: unknown[] = []
    mockedUseEventStream.mockImplementation(() => ({
      events: [...liveEvents],
      isConnected: true,
      error: null,
      clearEvents: vi.fn(),
      droppedCount: 0,
      receivedCount: liveEvents.length,
      reconnect: vi.fn(),
    }) as unknown as ReturnType<typeof useEventStream>)

    // scenarioId auto-enables the live stream (streamEnabled -> true)
    const { rerender } = render(
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    for (let i = 0; i < 8; i++) {
      liveEvents.push({ kind: 'CoroutineCreated' })
      rerender(
        <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      )
      act(() => {
        vi.advanceTimersByTime(250)
      })
    }

    expect(refetch).not.toHaveBeenCalled()
  })

  it('polls the snapshot only as a fallback while the stream is on but disconnected', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)

    // Stream off: no polling.
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(mockedUseSession).toHaveBeenLastCalledWith('session-1', { pollMs: false })
  })

  it('shows the SSE connection problem and dropped-event count, with a reconnect action (#137)', () => {
    const reconnect = vi.fn()
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    mockedUseEventStream.mockImplementation(() => ({
      events: [],
      isConnected: false,
      error: 'Connection lost',
      clearEvents: vi.fn(),
      droppedCount: 42,
      receivedCount: 0,
      reconnect,
    }) as unknown as ReturnType<typeof useEventStream>)

    render(
      <SessionWorkspace sessionId="session-1" scenarioId="sc-1" scenarioName="Test Scenario" />,
      { wrapper: createWrapper() },
    )

    expect(screen.getByTestId('view-status')).toHaveTextContent('Connection lost')
    expect(screen.getByTestId('dropped-count')).toHaveTextContent('42 events dropped')
    // The stream is on but disconnected -> the snapshot falls back to a slow poll.
    expect(mockedUseSession).toHaveBeenLastCalledWith('session-1', { pollMs: 10_000 })
    act(() => {
      screen.getByRole('button', { name: /reconnect/i }).click()
    })
    expect(reconnect).toHaveBeenCalledTimes(1)
  })
})

describe('SessionWorkspace - threads evidence wire shape (UAT gap 1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('the threads evidence panel renders thread activity from the real Map wire shape (UAT gap 1)', async () => {
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

    render(<SessionWorkspace sessionId="session-1" />, {
      wrapper: createWrapper(),
    })

    // The threads table is in the Evidence tab, under the Threads section.
    await userEvent.click(screen.getByRole('tab', { name: /^Evidence/ }))
    await userEvent.click(await screen.findByRole('button', { name: /Threads · 2 names/ }))
    // 2. Thread names from the wire map are rendered (values, not test names)
    expect(await screen.findByText('worker-1')).toBeVisible()
    expect(screen.getByText('worker-2')).toBeVisible()

    // 3. One assignment observed on each name
    expect(screen.getByRole('row', { name: /worker-1/ })).toHaveTextContent(/worker-1\s*1\s*1\s*1/)

    // 1. The UAT-observed permanent empty state is gone
    expect(screen.queryByText('No thread activity data available yet')).toBeNull()
  })
})

describe('SessionWorkspace - replay mode (RPLY-01/02/03, D-01..18)', () => {
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
    return render(<SessionWorkspace sessionId="session-1" />, {
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

  it('entering replay seeks to end + says Replay in the view pill + controller (D-03/D-13/D-15)', async () => {
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

    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })

    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    expect(screen.getByTestId('view-status')).toHaveTextContent(/^Replay/)
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

    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    // At end: both c1 and c2 projected from the events (not session.coroutines,
    // which has only c-root from makeSession).
    const graph = screen.getByTestId('coroutine-tree-graph')
    expect(graph).toHaveAttribute('data-ids', 'c1,c2')
  })

  it('keeps every Evidence section on the replay cursor, with no live-data notice, and names the moment (Part 8)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: replayEvents } as unknown as ReturnType<
      typeof useSessionEvents
    >)

    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))

    // Every section reads the cursor's events or activity now (Part 8, M-7): none says it shows live data.
    expect(screen.queryByTestId('live-data-notice')).toBeNull()
    expect(screen.getAllByTestId('moment-note').length).toBeGreaterThan(0)
  })

  it('inspects a coroutine at the replay cursor, reading its timeline at that seq (Part 8, S-11)', async () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 2 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: replayEvents } as unknown as ReturnType<typeof useSessionEvents>)

    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    await userEvent.click(screen.getByTestId('select-c1'))
    expect(await screen.findByTestId('inspector')).toBeInTheDocument()
    const last = replayEvents[replayEvents.length - 1]!.seq
    await waitFor(() =>
      expect(mockedApiClient.getTimelineAt).toHaveBeenCalledWith(
        { source: { kind: 'session', sessionId: 'session-1' }, binding: { kind: 'replay', atSeq: last } },
        'c1',
      ),
    )
    expect(mockedApiClient.getCoroutineTimeline).not.toHaveBeenCalled()
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

    // Live stream: 3 events received before replay entry, 2 more after.
    let received = 3
    mockedUseEventStream.mockImplementation(() => ({
      events: [
        ...replayEvents,
        { ...replayEvents[2], seq: 4, coroutineId: 'c3', label: 'late' },
        { ...replayEvents[2], seq: 5, coroutineId: 'c4', label: 'later' },
      ],
      isConnected: true,
      error: null,
      clearEvents: vi.fn(),
      droppedCount: 0,
      receivedCount: received,
      reconnect: vi.fn(),
    }) as unknown as ReturnType<typeof useEventStream>)

    const { rerender } = render(<SessionWorkspace sessionId="session-1" />, {
      wrapper: createWrapper(),
    })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    received = 5
    rerender(<SessionWorkspace sessionId="session-1" />)

    // 2 events arrived after the frozen snapshot of 3.
    const badge = await screen.findByRole('button', {
      name: /exit replay and jump to live/i,
    })
    expect(badge).toHaveTextContent('2 new events')

    await userEvent.click(badge)
    // Back to live: the view pill no longer says Replay, Replay toggle shown again.
    expect(screen.getByTestId('view-status')).not.toHaveTextContent(/^Replay/)
    expect(screen.getByRole('button', { name: /^replay$/i })).toBeInTheDocument()
  })
})

describe('SessionWorkspace active-only "What\'s running now" view (RCO-06, D-08)', () => {
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

  function mountSession(coroutines: ReturnType<typeof coro>[], sessionId = 'session-1') {
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: coroutines.length, coroutines }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    return render(<SessionWorkspace sessionId={sessionId} />, { wrapper: createWrapper() })
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

  it('shows every coroutine of a finished run, not "Nothing running now"', async () => {
    mountSession([
      coro('a-failed', 'FAILED'),
      coro('b-failed', 'FAILED'),
      coro('c-cancelled', 'CANCELLED'),
    ])

    expect(screen.queryByText('Nothing running now')).not.toBeInTheDocument()
    expect(screen.getByText('Everything has finished')).toBeInTheDocument()
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute(
      'data-ids',
      'a-failed,b-failed,c-cancelled',
    )
    // Nothing is live, so there is nothing to hide the completed ones behind.
    expect(screen.queryByText(/Show completed|Hide completed/)).toBeNull()

    // A chip still narrows the finished run.
    await userEvent.click(screen.getByRole('button', { name: 'Failed 2' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'a-failed,b-failed')
  })

  it('keeps the Waiting chip clearable after the last parent stops waiting', async () => {
    const { rerender } = mountSession([
      coro('p-waiting', 'WAITING_FOR_CHILDREN'),
      coro('c-active', 'ACTIVE'),
    ])
    await userEvent.click(screen.getByRole('button', { name: 'Waiting for children 1' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'p-waiting')

    // The children finish, and the parent with them.
    const done = [coro('p-waiting', 'COMPLETED'), coro('c-active', 'COMPLETED')]
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: done.length, coroutines: done }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    rerender(<SessionWorkspace sessionId="session-1" />)

    await userEvent.click(screen.getByRole('button', { name: 'Waiting for children 0' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute(
      'data-ids',
      'p-waiting,c-active',
    )
  })

  it('explains an empty result as a filter miss, not a missing app', async () => {
    mountSession([coro('a-active', 'ACTIVE'), coro('b-failed', 'FAILED')])

    await userEvent.click(screen.getByRole('button', { name: 'Failed 1' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'b-failed')

    // Suspended has no members here, so the canvas is empty for a different
    // reason than "no app connected".
    expect(screen.queryByRole('button', { name: /Suspended/ })).toBeNull()
  })

  it('badges a scenario session as DEMO and locks nothing', () => {
    mountSession([coro('a-active', 'ACTIVE')], 'scenario-Nested Coroutines')
    expect(screen.getByTestId('rung-badge')).toHaveTextContent('DEMO')
    // A demo session already has full fidelity — nothing to invite.
    expect(screen.queryByText('Lock contention')).toBeNull()
  })

  it('badges a real session with no wrapper evidence as ATTACHED', () => {
    mountSession([coro('a-active', 'ACTIVE')], 'checkout-service')
    expect(screen.getByTestId('rung-badge')).toHaveTextContent('ATTACHED')
  })

  it('invites the next rung instead of hiding what is missing', () => {
    mountSession([coro('a-active', 'ACTIVE')], 'checkout-service')
    // The defect this fixes: today these panels silently vanish, so the user
    // never learns a richer view exists.
    expect(screen.getByTestId('evidence-section-Locks and permits')).toHaveTextContent('none captured')
    expect(screen.getByText(/Swap Mutex\(\) for VizMutex\(\)/)).toBeInTheDocument()
  })

  it('renders the state bar with counts over the whole snapshot', () => {
    mountSession([
      coro('a-active', 'ACTIVE'),
      coro('b-suspended', 'SUSPENDED'),
      coro('c-completed', 'COMPLETED'),
      coro('d-failed', 'FAILED'),
    ])
    // Counts cover every coroutine, not just the ones the canvas renders.
    expect(screen.getByRole('button', { name: 'Active 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Suspended 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Completed 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Failed 1' })).toBeInTheDocument()
  })

  it('narrows the canvas to the chosen state, including terminal ones', async () => {
    mountSession([
      coro('a-active', 'ACTIVE'),
      coro('b-suspended', 'SUSPENDED'),
      coro('c-completed', 'COMPLETED'),
      coro('d-failed', 'FAILED'),
    ])

    await userEvent.click(screen.getByRole('button', { name: 'Completed 1' }))
    // A named chip bypasses the active-only default — asking for completed
    // must actually show completed.
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'c-completed')
  })

  it('restores the default view when the active chip is clicked again', async () => {
    mountSession([
      coro('a-active', 'ACTIVE'),
      coro('b-suspended', 'SUSPENDED'),
      coro('c-completed', 'COMPLETED'),
    ])

    const completed = screen.getByRole('button', { name: 'Completed 1' })
    await userEvent.click(completed)
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'c-completed')

    await userEvent.click(screen.getByRole('button', { name: 'Completed 1' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute(
      'data-ids',
      'a-active,b-suspended'
    )
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

  it('says how many coroutines the node cap leaves out (S-12)', () => {
    const many = Array.from({ length: 205 }, (_, i) => coro(`a-${i}`, 'ACTIVE'))
    mountSession(many)

    // NODE_CAP = 200 of 205; no rounds here (older server), so no Find to point at.
    expect(screen.getByText('Showing 200 of 205 coroutines.')).toBeInTheDocument()
    const graph = screen.getByTestId('coroutine-tree-graph')
    expect(graph).toHaveAttribute('data-count', '200')
  })

  it('mounts the metric strip below the live canvas', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    // The dock lives in the live "Coroutines" tab region (default selected) —
    // no tab switch needed; it is always visible below the canvas.
    const metrics = screen.getByTestId('session-metrics')
    expect(metrics).toBeInTheDocument()
    expect(metrics).toHaveAttribute('data-session', 'session-1')
  })

  it('keeps the explanation in Learn, not above or beside the tree', async () => {
    mountSession([coro('a-active', 'ACTIVE')])
    expect(screen.getByTestId('learn-legend')).not.toBeVisible()
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
    expect(screen.getByTestId('learn-legend')).toBeVisible()
  })

  it('mounts SessionMetrics exactly once (single /metrics poll — no double-mount, T-08.1-04)', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('keeps thread lanes and the metric strip on one screen, with a single SessionMetrics (Delta L1)', () => {
    mountSession([coro('a-active', 'ACTIVE')])
    // Threads stopped being a tab, so the lanes are reachable with no
    // navigation — and the workspace body still owns exactly ONE metric strip,
    // which is the guarantee the tabbed version of this test was protecting.
    expect(screen.getByTestId('dispatcher-overview')).toBeInTheDocument()
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('shows the completed coroutines of a finished demo, not "start your app"', () => {
    mountSession([coro('c-completed', 'COMPLETED')])
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'c-completed')
    expect(screen.queryByText(/Start your instrumented app/)).not.toBeInTheDocument()
  })

  it('keeps the active-only default while anything is still live', () => {
    mountSession([coro('a-active', 'ACTIVE'), coro('c-completed', 'COMPLETED')])
    expect(screen.getByText("What's running now")).toBeInTheDocument()
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'a-active')
  })

  it('caps a finished run at the node cap and counts the rest', () => {
    mountSession(Array.from({ length: 205 }, (_, i) => coro(`c-${i}`, 'COMPLETED')))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-count', '200')
    expect(screen.getByText('Showing 200 of 205 coroutines.')).toBeInTheDocument()
  })

  it('counts "more" from what the chip selects, not from the whole run', async () => {
    mountSession([
      ...Array.from({ length: 205 }, (_, i) => coro(`c-${i}`, 'COMPLETED')),
      coro('f-1', 'FAILED'),
    ])
    await userEvent.click(screen.getByRole('button', { name: 'Failed 1' }))
    expect(screen.getByTestId('coroutine-tree-graph')).toHaveAttribute('data-ids', 'f-1')
    expect(screen.queryByText(/^Showing \d+ of/)).toBeNull()
  })

  it('tells an app session with no coroutines to start the instrumented app', () => {
    mountSession([], 'order-service-1791897636047')
    expect(screen.getByText('No live coroutines yet')).toBeInTheDocument()
    expect(screen.getByText(/Start your instrumented app/)).toBeInTheDocument()
  })

  it('tells a demo session with no coroutines to run the scenario', () => {
    mountSession([], 'scenario-order-processing-1791897636047')
    expect(screen.getByText('No coroutines yet')).toBeInTheDocument()
    expect(screen.getByText('Run the scenario to see its coroutines here.')).toBeInTheDocument()
    expect(screen.queryByText(/Start your instrumented app/)).not.toBeInTheDocument()
  })
})

describe('SessionWorkspace — the dock tabs (S-4, S-5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 1 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('offers Inspector, Evidence, Events and Learn — Problems stays hidden until Part 7', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getAllByRole('tab').map(t => t.textContent)).toEqual(['Inspector', 'Evidence', 'Events', 'Learn'])
    expect(screen.queryByRole('tab', { name: /problems/i })).toBeNull()
  })

  it('keeps the same tabs in the shared view and in replay', async () => {
    const { unmount } = render(<SessionWorkspace sessionId="session-1" readOnly />, { wrapper: createWrapper() })
    expect(screen.getAllByRole('tab')).toHaveLength(4)
    unmount()
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    expect(screen.getAllByRole('tab')).toHaveLength(4)
  })

  it('opens an attached app on the Inspector, with the session summary', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Select a coroutine to inspect it')).toBeVisible()
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('opens a demo started with its scenario on Learn while it runs', () => {
    render(<SessionWorkspace sessionId="session-1" scenarioId="basics/launch" scenarioName="Launch" />, {
      wrapper: createWrapper(),
    })
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveAttribute('aria-selected', 'true')
  })

  it('opens a failed demo on the Inspector', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({
        coroutineCount: 1,
        coroutines: [{ id: 'c-1', jobId: 'j', parentId: null, scopeId: 's', label: 'x', state: 'FAILED' as CoroutineState }],
      }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    render(<SessionWorkspace sessionId="session-1" scenarioId="basics/launch" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
  })

  it('remembers the tab the user picked for this session', async () => {
    const { unmount } = render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    unmount()
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Events' })).toHaveAttribute('aria-selected', 'true')
  })

  it('names what Evidence holds', async () => {
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({
      data: [
        { sessionId: 's', seq: 1, tsNanos: 1, kind: 'MutexCreated', mutexId: 'm-1', mutexLabel: null, ownerCoroutineId: null },
        { sessionId: 's', seq: 2, tsNanos: 2, kind: 'SemaphoreCreated', semaphoreId: 'p-1', semaphoreLabel: null, totalPermits: 2 },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('tab', { name: 'Evidence · 1 lock, 1 semaphore' })).toBeInTheDocument()
  })

  it('unfolds a folded dock when a tab is chosen', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'Fold the dock' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
    expect(screen.getByRole('button', { name: 'Fold the dock' })).toBeInTheDocument()
    expect(screen.getByTestId('learn-legend')).toBeVisible()
  })

  it('keeps a selection created before a trimmed replay while the summaries are read (Part 8, M-5, M-9)', async () => {
    mockedUseSession.mockReturnValue({ data: makeSession(), isLoading: false, refetch: vi.fn() } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({
      data: [
        { kind: 'coroutine.created', sessionId: 'session-1', seq: 21, tsNanos: 1000, coroutineId: 'c1', jobId: 'j1', parentCoroutineId: 'c-root', scopeId: 's1', label: 'child' },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)
    vi.mocked(mockedApiClient.getHierarchyAt).mockReturnValue(new Promise(() => {}))
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByTestId('select-c-root'))
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    const pending = await screen.findByTestId('inspector-pending')
    expect(pending).toHaveTextContent('root · updating to this moment…')
    expect(within(pending).getByText('root', { selector: 'span' })).toBeInTheDocument()
  })

  it('qualifies a shared link’s peak and throughput when its early events were trimmed (Part 8, M-1)', async () => {
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({
      data: [
        { kind: 'coroutine.created', sessionId: 'session-1', seq: 21, tsNanos: 1000, coroutineId: 'c1', jobId: 'j1', parentCoroutineId: null, scopeId: 's1', label: 'root' },
      ],
    } as unknown as ReturnType<typeof useSessionEvents>)
    render(<SessionWorkspace sessionId="session-1" readOnly />, { wrapper: createWrapper() })
    expect(within(screen.getByTestId('moment-summary')).getByText(/in the kept events only/i)).toBeInTheDocument()
  })

  it('offers the summary of the moment in replay and in a shared link, instead of a paused Inspector (Part 8)', async () => {
    const { unmount } = render(<SessionWorkspace sessionId="session-1" readOnly share={share} />, {
      wrapper: createWrapper(),
    })
    expect(screen.getByTestId('moment-summary')).toBeInTheDocument()
    expect(within(screen.getByTestId('moment-summary')).getByTestId('moment-note')).toHaveTextContent('As loaded · event 30')
    expect(screen.queryByText(/not included in a shared link/i)).toBeNull()
    unmount()

    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    expect(screen.queryByText(/paused during replay/i)).toBeNull()
    expect(screen.getByTestId('moment-summary')).toBeInTheDocument()
  })

  it('reads a shared link’s coroutine through its token, bound to the loaded cut (Part 8, M-11)', async () => {
    render(<SessionWorkspace sessionId="session-1" readOnly share={share} />, { wrapper: createWrapper() })
    const [first] = screen.getAllByRole('button', { name: /^select / })
    await userEvent.click(first!)
    await waitFor(() =>
      expect(mockedApiClient.getTimelineAt).toHaveBeenCalledWith(
        { source: { kind: 'shared', sessionId: 'session-1', token: 'tok' }, binding: { kind: 'bound', asOfSeq: 30, asOfCut: 'cut-1' } },
        expect.any(String),
      ),
    )
  })

  it('says a shared link without a health has no details, rather than reading newer data (Part 8, M-11)', async () => {
    render(<SessionWorkspace sessionId="session-1" readOnly share={{ ...share, asOfSeq: null, cut: null, unavailable: 'history-trimmed' }} />, {
      wrapper: createWrapper(),
    })
    const [first] = screen.getAllByRole('button', { name: /^select / })
    await userEvent.click(first!)
    expect(await screen.findByText(/details unavailable: this link's health could not be read \(history-trimmed\)/i)).toBeInTheDocument()
    expect(mockedApiClient.getTimelineAt).not.toHaveBeenCalled()
  })

  it('reaches the dispatchers in replay through Evidence', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    await userEvent.click(screen.getByRole('tab', { name: /^Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Dispatchers/ }))
    expect(screen.getByTestId('dispatcher-overview')).toBeInTheDocument()
  })
})

describe('SessionWorkspace — header, main view and dock (S-1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mockedUseSession.mockReturnValue({
      data: makeSession({ coroutineCount: 1 }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('stacks the header, the main view and the dock, in that order', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    const header = screen.getByRole('banner')
    const main = screen.getByRole('region', { name: 'Main view' })
    const dock = screen.getByRole('region', { name: 'Dock' })
    expect(header.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(main.compareDocumentPosition(dock) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('opens the dock at 40 % of the window', () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    expect(screen.getByRole('region', { name: 'Dock' })).toHaveStyle({
      height: `${Math.round(window.innerHeight * 0.4)}px`,
    })
  })

  it('opens the dock at each look’s own starting share, and at 40 % without one', () => {
    for (const look of LOOKS) {
      localStorage.clear()
      forgetVisitLook()
      localStorage.setItem(LOOK_STORAGE_KEY, look.id)
      const { unmount } = render(
        <LookProvider>
          <SessionWorkspace sessionId="session-1" />
        </LookProvider>,
        { wrapper: createWrapper() }
      )
      const share = CARDS[look.id].dockShare ?? 0.4
      expect(screen.getByRole('region', { name: 'Dock' }), look.id).toHaveStyle({
        height: `${Math.round(window.innerHeight * share)}px`,
      })
      unmount()
    }
  })

  it('resizes the dock from the keyboard and remembers it', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    const start = Math.round(window.innerHeight * 0.4)
    screen.getByRole('separator', { name: 'Resize the dock' }).focus()
    await userEvent.keyboard('{ArrowUp}')
    expect(screen.getByRole('region', { name: 'Dock' })).toHaveStyle({ height: `${start + 24}px` })
    expect(JSON.parse(localStorage.getItem('vizcore.dock')!)).toEqual({ height: start + 24, folded: false })
  })

  it('folds the dock to its bar', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'Fold the dock' }))
    expect(screen.getByRole('tabpanel', { name: 'Inspector', hidden: true })).not.toBeVisible()
    expect(screen.getByRole('button', { name: 'Unfold the dock' })).toBeInTheDocument()
  })

  it('maximise hides the dock; restoring brings it back', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'Maximise' }))
    expect(screen.queryByRole('region', { name: 'Dock' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Restore dock' }))
    expect(screen.getByRole('region', { name: 'Dock' })).toBeInTheDocument()
  })

  it('switches the main view between graph and list from its toolbar', async () => {
    render(<SessionWorkspace sessionId="session-1" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    expect(screen.getByTestId('coroutine-tree')).toBeInTheDocument()
  })

  it('says Failed in the header for a demo with a failed coroutine', () => {
    mockedUseSession.mockReturnValue({
      data: makeSession({
        coroutineCount: 1,
        coroutines: [{ id: 'c-1', jobId: 'j', parentId: null, scopeId: 's', label: 'x', state: 'FAILED' as CoroutineState }],
      }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    render(<SessionWorkspace sessionId="session-1" scenarioId="demo" scenarioName="Demo" />, { wrapper: createWrapper() })
    expect(screen.getByTestId('run-outcome')).toHaveTextContent('Failed · 1 coroutine failed')
  })
})

describe('SessionWorkspace — story mode (Part 9, S-9)', () => {
  let n = 0
  const e = (kind: string, id: string, ms: number, extra: Record<string, unknown> = {}) =>
    ({ seq: ++n, tsNanos: ms * 1e6, sessionId: 's', kind, coroutineId: id, jobId: `j-${id}`, parentCoroutineId: null, scopeId: 'sc', label: id, ...extra }) as unknown as VizEvent
  // Nested's first moment: fetch-product's second suspension, while the page waits for it.
  const story = () => {
    n = 0
    return [
      e('coroutine.created', 'load-product-page', 0),
      e('coroutine.started', 'load-product-page', 0),
      e('coroutine.created', 'fetch-product', 0, { parentCoroutineId: 'load-product-page' }),
      e('coroutine.started', 'fetch-product', 0),
      e('coroutine.suspended', 'fetch-product', 0),
      e('coroutine.resumed', 'fetch-product', 1500),
      e('coroutine.suspended', 'fetch-product', 1500),
      e('coroutine.resumed', 'fetch-product', 2500),
      e('coroutine.completed', 'fetch-product', 2500),
    ]
  }

  async function mountNested(events: VizEvent[]) {
    mockedUseSession.mockReturnValue({
      data: makeSession({ demoId: 'nested' }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: events } as unknown as ReturnType<typeof useSessionEvents>)
    render(<SessionWorkspace sessionId="scenario-Nested-1793993830683" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
  }

  it('tells the demo\'s story in Learn, first chapter first', async () => {
    await mountNested(story())
    const learn = screen.getByRole('region', { name: /Story/ })
    expect(within(learn).getByRole('article', { name: /The product comes first/ })).toBeInTheDocument()
    expect(within(learn).getByText('3 more chapters')).toBeInTheDocument()
  })

  it('Reveal moves the page to the anchor: replay at that event, its coroutine selected, the whole tree shown', async () => {
    const events = story()
    await mountNested(events)
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    const controller = screen.getByTestId('replay-controller')
    expect(controller).toHaveAttribute('data-index', '6')
    expect(controller).toHaveAttribute('data-total', String(events.length))
    // The dock stays on Learn; the tree shows completed coroutines too.
    expect(screen.getByRole('tab', { name: 'Learn' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('coroutine-tree-graph').getAttribute('data-ids')).toContain('fetch-product')
  })

  it('a replay that starts at the session\'s first held event is not called incomplete (seq 1 and 2 hold no event)', async () => {
    const events = story().map((ev, i) => ({ ...ev, seq: i + 3 }) as VizEvent)
    mockedUseSession.mockReturnValue({
      data: makeSession({ demoId: 'nested', oldestRetainedSeq: 3, trimmed: false }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: events } as unknown as ReturnType<typeof useSessionEvents>)
    render(<SessionWorkspace sessionId="scenario-Nested-1793993830683" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    expect(screen.getByTestId('replay-controller')).toHaveAttribute('data-start-note', '')
  })

  it('still says so when the server trimmed the session\'s history', async () => {
    const events = story().map((ev, i) => ({ ...ev, seq: i + 40 }) as VizEvent)
    mockedUseSession.mockReturnValue({
      data: makeSession({ demoId: 'nested', oldestRetainedSeq: 40, trimmed: true }),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: events } as unknown as ReturnType<typeof useSessionEvents>)
    render(<SessionWorkspace sessionId="scenario-Nested-1793993830683" />, { wrapper: createWrapper() })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    expect(screen.getByTestId('replay-controller').getAttribute('data-start-note')).toMatch(/starts at event 40/)
  })

  it('re-freezes on Reveal when the page holds newer events than the frozen replay, so the tree matches the story\'s facts', async () => {
    const events = story()
    await mountNested(events.slice(0, 7))
    // Freeze a replay of the first seven events, then the page learns of more (a refetch).
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    expect(screen.getByTestId('replay-controller')).toHaveAttribute('data-total', '7')
    const { useSessionEvents } = await import('@/hooks/use-sessions')
    vi.mocked(useSessionEvents).mockReturnValue({ data: events } as unknown as ReturnType<typeof useSessionEvents>)
    // Any render now reads the newer history.
    await userEvent.click(screen.getByRole('tab', { name: 'Inspector' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Learn' }))
    await userEvent.click(screen.getByRole('button', { name: 'Reveal' }))
    expect(screen.getByTestId('replay-controller')).toHaveAttribute('data-total', String(events.length))
    expect(screen.getByTestId('replay-controller')).toHaveAttribute('data-index', '6')
  })
})

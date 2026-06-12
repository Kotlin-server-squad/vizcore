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

// Mock child components to isolate SessionDetails
vi.mock('./CoroutineTree', () => ({
  CoroutineTree: () => <div data-testid="coroutine-tree">CoroutineTree</div>,
}))

vi.mock('./CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: () => <div data-testid="coroutine-tree-graph">CoroutineTreeGraph</div>,
}))

vi.mock('./EventsList', () => ({
  EventsList: () => <div data-testid="events-list">EventsList</div>,
}))

vi.mock('./StructuredConcurrencyInfo', () => ({
  StructuredConcurrencyInfo: () => <div data-testid="structured-concurrency-info" />,
}))

vi.mock('./DispatcherOverview', () => ({
  DispatcherOverview: () => <div data-testid="dispatcher-overview" />,
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

import { useSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'
import { apiClient } from '@/lib/api-client'

const mockedUseSession = vi.mocked(useSession)
const mockedUseEventStream = vi.mocked(useEventStream)
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

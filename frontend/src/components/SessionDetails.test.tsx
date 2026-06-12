import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionDetails } from './SessionDetails'
import type { SessionSnapshot, CoroutineState } from '@/types/api'

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

vi.mock('@/hooks/use-thread-activity', () => ({
  useThreadActivity: vi.fn(() => ({ data: undefined })),
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

vi.mock('./ThreadTimeline', () => ({
  ThreadTimeline: () => <div data-testid="thread-timeline" />,
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

const mockedUseSession = vi.mocked(useSession)

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
    // Instead, an explicit completed indicator should appear
    expect(screen.getByText(/scenario completed/i)).toBeInTheDocument()
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
})

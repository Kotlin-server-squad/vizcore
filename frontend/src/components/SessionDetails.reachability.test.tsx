import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionDetails } from './SessionDetails'
import type { SessionSnapshot, CoroutineState, CoroutineTimeline } from '@/types/api'

// RCO-06 reachability proof. Unlike the sibling SessionDetails.test.tsx (which
// stubs CoroutineTree/Graph + the drawer to isolate wiring), this file renders
// the REAL live CoroutineTree, the REAL CoroutineSourceDrawer + CoroutineSourceStack,
// and only mocks the per-coroutine timeline fetch — so it proves the genuine
// click → drawer-open → file:line pipeline end-to-end (NOT a compile-only check).
// Module-scoped vi.mock() makes this impossible to express in the stub-mocked
// sibling file, hence a dedicated reachability test file.

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
  },
}))

// Toast is fired by jump-to-code; mock it so HeroUI's ToastProvider is not needed.
vi.mock('@/lib/toast', () => ({ toastSuccess: vi.fn(), toastError: vi.fn() }))

// Heavy/irrelevant panels stubbed so SessionDetails mounts in jsdom without
// pulling in html2canvas / MediaRecorder / framer MotionValue loops. The live
// CoroutineTree, CoroutineSourceDrawer and CoroutineSourceStack are LEFT REAL.
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

const mockedUseSession = vi.mocked(useSession)
const mockedApiClient = vi.mocked(apiClient)

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
    state: 'SUSPENDED' as CoroutineState,
    parentId: null,
    childrenIds: [],
    totalDuration: 0,
    activeTime: 0,
    suspendedTime: 0,
    events: [
      {
        seq: 1,
        timestamp: 1000,
        kind: 'coroutine.suspended',
        suspensionPoint: {
          function: 'com.acme.app.UserService.loadUser',
          fileName: 'UserService.kt',
          lineNumber: 42,
          reason: 'delay',
          timestamp: 1000,
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
    mockedUseSession.mockReturnValue({
      data: makeSession(),
      isLoading: false,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useSession>)
  })

  it('clicking a live node opens the drawer rendering a real file:line frame', async () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    // Switch to the list view so the real CoroutineTree (not the mocked graph) renders.
    await userEvent.click(screen.getByRole('button', { name: /list view/i }))

    // Click the live coroutine node — additive onSelect wiring (Task 1).
    const node = await screen.findByRole('button', { name: 'Open source for LiveWorker' })
    await userEvent.click(node)

    // Drawer opens with its title (proves the drawer is mounted + user-reachable).
    const heading = await screen.findByText(/Coroutine source — LiveWorker/)
    expect(heading).toBeInTheDocument()

    // And it renders the real file:line frame from the fetched timeline — a
    // user-code jump target, NOT a compile-only mount. (The single suspension
    // frame backs both the derived "Created at" and the "Suspended at" rows, so
    // the jump target legitimately appears more than once.)
    const jumpTargets = await screen.findAllByRole('button', {
      name: 'Jump to code: UserService.kt:42',
    })
    expect(jumpTargets.length).toBeGreaterThanOrEqual(1)
    expect(jumpTargets[0]).toHaveTextContent('UserService.kt:42')

    // The timeline was actually fetched for the selected coroutine.
    expect(mockedApiClient.getCoroutineTimeline).toHaveBeenCalledWith('session-1', 'c-live')
  })

  it('highlights the selected node with ring-2 ring-primary while the drawer is open', async () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    await userEvent.click(screen.getByRole('button', { name: /list view/i }))

    // Capture the node reference BEFORE opening — the open Drawer's focus trap
    // marks background content inert/aria-hidden, so role queries no longer reach
    // it; the element itself persists and still carries its className.
    const node = await screen.findByRole('button', { name: 'Open source for LiveWorker' })
    await userEvent.click(node)

    // Drawer is open…
    expect(await screen.findByText(/Coroutine source — LiveWorker/)).toBeInTheDocument()

    // …and the selected node carries the persistent selection ring.
    expect(node.className).toContain('ring-2')
    expect(node.className).toContain('ring-primary')
  })

  it('does not open the drawer until a node is clicked (no eager fetch)', () => {
    render(<SessionDetails sessionId="session-1" />, { wrapper: createWrapper() })

    // Before any selection the drawer title is absent and no timeline is fetched
    // (the enabled-guard keeps the query disabled while coroutineId is null, D-08).
    expect(screen.queryByText(/Coroutine source —/)).toBeNull()
    expect(mockedApiClient.getCoroutineTimeline).not.toHaveBeenCalled()
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import type { CoroutineState, SessionSnapshot, VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [], isPending: false, isError: false })),
  useDeleteSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
}))
vi.mock('@/hooks/use-event-stream', () => ({ useEventStream: vi.fn() }))
vi.mock('@/hooks/use-scenarios', () => ({ useRunScenario: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })) }))
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(() => Promise.resolve({})),
    getCoroutineTimeline: vi.fn(() => new Promise(() => {})),
    getMetrics: vi.fn(() => new Promise(() => {})),
    getSessionEvents: vi.fn(() => Promise.resolve([])),
  },
}))
vi.mock('./DispatcherOverview', () => ({ DispatcherOverview: () => null }))
vi.mock('./SessionMetrics', () => ({ SessionMetrics: () => null }))
vi.mock('./export/ExportMenu', () => ({ ExportMenu: () => null }))
vi.mock('./replay/RecordConfirmModal', () => ({ RecordConfirmModal: () => null }))
// A thin controller: replay's cursor is moved by the real useReplay, through this button.
vi.mock('./replay/ReplayController', () => ({
  ReplayController: ({ replay }: { replay: { seekTo: (i: number) => void } }) => (
    <button type="button" onClick={() => replay.seekTo(0)}>
      Seek to the first event
    </button>
  ),
}))
vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn(() => vi.fn()) }))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))
vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<typeof import('framer-motion')>('framer-motion')
  const pass = ({ children, ...p }: Record<string, unknown>) => <div {...(p as object)}>{children as ReactNode}</div>
  return { ...actual, motion: { div: pass, span: pass, button: pass, circle: () => null }, AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>, LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</> }
})

import { useSession, useSessionEvents } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'

const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
let seq = 0
const ev = (kind: string, f: Record<string, unknown>) => ({ sessionId: 'session-1', seq: ++seq, tsNanos: seq * 1_000_000, kind, ...f }) as unknown as VizEvent
const co = (kind: string, id: string, f: Record<string, unknown> = {}) =>
  ev(kind, { coroutineId: id, jobId: `j-${id}`, parentCoroutineId: null, scopeId: 's', label: id === 'c-1' ? 'order-1' : 'order-2', ...f })

function session(states: [CoroutineState, CoroutineState]): SessionSnapshot {
  return {
    sessionId: 'session-1', coroutineCount: 2, eventCount: 8,
    coroutines: [
      { id: 'c-1', jobId: 'j-c-1', parentId: null, scopeId: 's', label: 'order-1', state: states[0] },
      { id: 'c-2', jobId: 'j-c-2', parentId: null, scopeId: 's', label: 'order-2', state: states[1] },
    ],
  }
}

function contended() {
  seq = 0
  return [
    co('coroutine.created', 'c-1'),
    co('coroutine.created', 'c-2'),
    ev('MutexCreated', { ...lock, ownerCoroutineId: null }),
    co('coroutine.started', 'c-1'),
    ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
    co('coroutine.started', 'c-2'),
    ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: ['c-2'], waitingLabels: ['order-2'] }),
    co('coroutine.suspended', 'c-2', { waitKind: 'MUTEX', resourceId: 'm-1', reason: 'mutex' }),
  ]
}

function stream(events: VizEvent[], droppedCount = 0) {
  vi.mocked(useEventStream).mockReturnValue({
    events, isConnected: true, error: null, clearEvents: vi.fn(), droppedCount, receivedCount: events.length, reconnect: vi.fn(),
  } as unknown as ReturnType<typeof useEventStream>)
}

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

describe('SessionWorkspace — the Timeline (Part 10, S-10)', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(useSessionEvents).mockReturnValue({ data: [], isPending: false, isError: false } as unknown as ReturnType<typeof useSessionEvents>)
    vi.mocked(useSession).mockReturnValue({ data: session(['ACTIVE' as CoroutineState, 'SUSPENDED' as CoroutineState]), isLoading: false, refetch: vi.fn() } as unknown as ReturnType<typeof useSession>)
  })

  it('is the third view: a row per coroutine the tree shows, with its state, and the lock lane', async () => {
    stream(contended())
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'Timeline' }))
    expect(screen.getByRole('button', { name: 'order-1, Active' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'order-2, Suspended' })).toBeInTheDocument()
    expect(screen.getByTestId('timeline-lane-m-1-0')).toHaveTextContent('order-1')
    expect(screen.queryByText(/Some intervals may be missing/)).toBeNull()
  })

  it('selects from a bar, and the Inspector sums the row up in one sentence', async () => {
    stream(contended())
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'Timeline' }))
    await userEvent.click(screen.getByRole('button', { name: 'order-2, Suspended' }))
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('inspector-timeline-sentence')).toHaveTextContent(
      'order-2 was active for 2.0 ms, and is suspended, waiting for inventory-lock.',
    )
  })

  it('leaves the Inspector without a sentence in the graph', async () => {
    stream(contended())
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'List' }))
    await userEvent.click(screen.getByRole('button', { name: 'Open source for order-2' }))
    expect(screen.queryByTestId('inspector-timeline-sentence')).toBeNull()
  })

  it('says some intervals may be missing after the stream lost events', async () => {
    stream(contended(), 3)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'Timeline' }))
    expect(screen.getByText('Some intervals may be missing: 3 events were dropped under load.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'order-2, Suspended' }))
    expect(screen.getByTestId('inspector-timeline-sentence')).toHaveTextContent(/^In the observed events, order-2/)
  })

  it('in replay draws the cursor, and the sentence is told up to it', async () => {
    stream(contended())
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'Timeline' }))
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Seek to the first event' }))
    expect(screen.getByTestId('timeline-cursor')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'order-1, Created' }))
    expect(screen.getByTestId('inspector-timeline-sentence')).toHaveTextContent(/^By 0.0 s, order-1 is created, not started yet/)
  })
})

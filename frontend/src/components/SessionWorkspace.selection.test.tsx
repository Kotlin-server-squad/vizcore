import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
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

import { useSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'

const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
let seq = 0
const ev = (kind: string, f: Record<string, unknown>) => ({ sessionId: 'session-1', seq: ++seq, tsNanos: seq * 1000, kind, ...f }) as unknown as VizEvent
const co = (kind: string, id: string, f: Record<string, unknown> = {}) =>
  ev(kind, { coroutineId: id, jobId: `j-${id}`, parentCoroutineId: null, scopeId: 's', label: id === 'c-1' ? 'order-1' : 'order-2', ...f })

function session(states: [CoroutineState, CoroutineState]): SessionSnapshot {
  return {
    sessionId: 'session-1', coroutineCount: 2, eventCount: 6,
    coroutines: [
      { id: 'c-1', jobId: 'j-c-1', parentId: null, scopeId: 's', label: 'order-1', state: states[0] },
      { id: 'c-2', jobId: 'j-c-2', parentId: null, scopeId: 's', label: 'order-2', state: states[1] },
    ],
  }
}

const contended = [
  ev('MutexCreated', { ...lock, ownerCoroutineId: null }),
  co('coroutine.started', 'c-1'),
  ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
  co('coroutine.started', 'c-2'),
  ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: ['c-2'], waitingLabels: ['order-2'] }),
  co('coroutine.suspended', 'c-2', { waitKind: 'MUTEX', resourceId: 'm-1', reason: 'mutex' }),
]
const handedOver = [
  ...contended,
  ev('MutexUnlocked', { ...lock, releaserId: 'c-1', releaserLabel: 'order-1', nextWaiterId: 'c-2', holdDurationNanos: 1 }),
  co('coroutine.resumed', 'c-2'),
  ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2', acquirerLabel: 'order-2', waitDurationNanos: 1 }),
  ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: [], waitingLabels: [] }),
]

function stream(events: VizEvent[]) {
  vi.mocked(useEventStream).mockReturnValue({
    events, isConnected: true, error: null, clearEvents: vi.fn(), droppedCount: 0, receivedCount: events.length, reconnect: vi.fn(),
  } as unknown as ReturnType<typeof useEventStream>)
}

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

async function openList() {
  await userEvent.click(screen.getByRole('button', { name: 'List' }))
}
const card = (name: string) => screen.getByRole('button', { name: `Open source for ${name}` })

describe('SessionWorkspace — one selection, live facts (S-2, S-6)', () => {
  beforeEach(() => {
    localStorage.clear()
    seq = 0
    vi.mocked(useSession).mockReturnValue({ data: session(['ACTIVE' as CoroutineState, 'SUSPENDED' as CoroutineState]), isLoading: false, refetch: vi.fn() } as unknown as ReturnType<typeof useSession>)
  })

  it('each node says what it waits for or holds, and the lines follow new events without a reload', async () => {
    stream(contended)
    const { rerender } = render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    expect(within(card('order-1')).getByTestId('activity-line')).toHaveTextContent('holds inventory-lock')
    expect(within(card('order-2')).getByTestId('activity-line')).toHaveTextContent('waits for inventory-lock · 1st')

    stream(handedOver)
    rerender(<SessionWorkspace sessionId="session-1" startLive />)
    expect(within(card('order-1')).queryByTestId('activity-line')).toBeNull()
    expect(within(card('order-2')).getByTestId('activity-line')).toHaveTextContent('holds inventory-lock')
  })

  it('Evidence lock facts follow new events too', async () => {
    stream(contended)
    const { rerender } = render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    expect(screen.getByTestId('mutex-state')).toHaveTextContent('order-1')
    stream(handedOver)
    rerender(<SessionWorkspace sessionId="session-1" startLive />)
    expect(within(screen.getByTestId('mutex-state')).getByRole('button', { name: 'order-2' })).toBeInTheDocument()
  })

  it('an event row selects its coroutine everywhere: the tree, the Events scope, the Inspector', async () => {
    stream(contended)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await userEvent.click(screen.getByRole('button', { name: /Select order-1 · inventory-lock/ }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for order-1')
    expect(card('order-1').className).toContain('ring-2')
    await userEvent.click(screen.getByRole('tab', { name: 'Inspector' }))
    expect(screen.getByRole('heading', { name: /order-1/ })).toBeInTheDocument()
    expect(screen.getByTestId('inspector-activity')).toHaveTextContent('holds inventory-lock')
  })

  it('a resource-only event selects the lock and opens its Evidence section', async () => {
    stream(contended)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await userEvent.click(screen.getByRole('button', { name: /Select inventory-lock \(event #1\)/ }))
    expect(screen.getByRole('tab', { name: /Evidence/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('button', { name: /Locks and permits/ })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByTestId('resource-m-1')).toHaveAttribute('data-selected', 'true')
  })

  it('a node click selects the coroutine and opens the Inspector; a holder name in Evidence selects it too', async () => {
    stream(contended)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    await userEvent.click(card('order-2'))
    expect(screen.getByRole('tab', { name: 'Inspector' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('inspector-activity')).toHaveTextContent('waits for inventory-lock · 1st')
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Locks and permits/ }))
    await userEvent.click(within(screen.getByTestId('mutex-state')).getByRole('button', { name: 'order-1' }))
    expect(card('order-1').className).toContain('ring-2')
    expect(screen.getByRole('tab', { name: /Evidence/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('a selected lock that leaves the page\'s events is no longer selected', async () => {
    stream(contended)
    const { rerender } = render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await userEvent.click(screen.getByRole('button', { name: /Select inventory-lock \(event #1\)/ }))
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about inventory-lock')
    // The view's events cleared (Clear events, or the history trimmed past it).
    stream([co('coroutine.started', 'c-1')])
    rerender(<SessionWorkspace sessionId="session-1" startLive />)
    expect(screen.getByTestId('events-scope')).toHaveTextContent('All 1 events in this session')
  })

  it('a channel picked in Evidence becomes the page\'s selection', async () => {
    const channels = [
      ev('ChannelCreated', { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      ev('ChannelCreated', { channelId: 'ch-2', name: 'invoices', capacity: 0, channelType: 'RENDEZVOUS' }),
      co('coroutine.suspended', 'c-1', { waitKind: 'CHANNEL_SEND', resourceId: 'ch-2', reason: 'channel' }),
    ]
    stream(channels)
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await openList()
    expect(within(card('order-1')).getByTestId('activity-line')).toHaveTextContent('waits to send to invoices')
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    await userEvent.click(screen.getByRole('button', { name: /Channels/ }))
    await userEvent.click(screen.getByTestId('channel-selector-ch-2'))
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events about invoices · 2')
  })

  it('in replay, Evidence says what was captured by the cursor, not later', async () => {
    stream([co('coroutine.started', 'c-1'), ...contended])
    render(<SessionWorkspace sessionId="session-1" startLive />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: /^replay$/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Seek to the first event' }))
    await userEvent.click(screen.getByRole('tab', { name: /Evidence/ }))
    expect(screen.getByTestId('evidence-section-Locks and permits')).toHaveTextContent('none captured')
  })
})


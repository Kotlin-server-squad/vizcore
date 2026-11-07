import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import { CoroutineState, type CoroutineNode, type RoundEntry, type RoundsPage, type SessionSnapshot } from '@/types/api'

// Group by name (dock S-12): siblings fold into a group card; its summary in the Inspector; Find opens the path.

vi.mock('@/hooks/use-sessions', () => ({
  useSession: vi.fn(),
  useSessionEvents: vi.fn(() => ({ data: [], isPending: false, isError: false })),
  useDeleteSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateSession: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
}))
vi.mock('@/hooks/use-event-stream', () => ({
  useEventStream: vi.fn(() => ({
    events: [], isConnected: true, error: null, clearEvents: vi.fn(), droppedCount: 0, receivedCount: 0, reconnect: vi.fn(),
  })),
}))
vi.mock('@/hooks/use-scenarios', () => ({ useRunScenario: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })) }))
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(() => Promise.resolve({})),
    getCoroutineTimeline: vi.fn(() => new Promise(() => {})),
    getMetrics: vi.fn(() => new Promise(() => {})),
    getSessionEvents: vi.fn(() => Promise.resolve([])),
    getRounds: vi.fn(),
    getRoundHierarchy: vi.fn(() => Promise.resolve([])),
  },
}))
// The graph lists what it is given, and the reveal it was asked for.
vi.mock('./CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: ({
    coroutines,
    reveal,
    onSelect,
    onToggleGroup,
    groups,
  }: {
    coroutines: CoroutineNode[]
    reveal?: { id: string }
    onSelect?: (id: string) => void
    onToggleGroup?: (id: string) => void
    groups?: ReadonlyMap<string, unknown>
  }) => (
    <div data-testid="graph" data-reveal={reveal?.id ?? ''}>
      <span data-testid="graph-ids">{coroutines.map(c => c.id).join(',')}</span>
      {coroutines.map(c => (
        <button key={c.id} type="button" onClick={() => onSelect?.(c.id)}>{`select ${c.label}`}</button>
      ))}
      {[...(groups?.keys() ?? [])].map(id => (
        <button key={id} type="button" onClick={() => onToggleGroup?.(id)}>{`toggle ${id}`}</button>
      ))}
    </div>
  ),
}))
vi.mock('./DispatcherOverview', () => ({ DispatcherOverview: () => null }))
vi.mock('./SessionMetrics', () => ({ SessionMetrics: () => null }))
vi.mock('./export/ExportMenu', () => ({ ExportMenu: () => null }))
vi.mock('./replay/RecordConfirmModal', () => ({ RecordConfirmModal: () => null }))
vi.mock('@tanstack/react-router', () => ({ useNavigate: vi.fn(() => vi.fn()) }))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))

import { useSession } from '@/hooks/use-sessions'
import { apiClient } from '@/lib/api-client'

const getRounds = vi.mocked(apiClient.getRounds)

const node = (id: string, over: Partial<CoroutineNode> = {}): CoroutineNode => ({
  id, jobId: `j-${id}`, parentId: null, scopeId: 's', label: id, state: CoroutineState.ACTIVE,
  roundId: `round:${id}`, origin: 'APP', ...over,
})
const entry = (anchorId: string, over: Partial<RoundEntry> = {}): RoundEntry => ({
  roundId: `round:${anchorId}`, anchorId, anchorName: anchorId, anchorOrigin: 'APP', anchorCreatedNanos: 0,
  anchorCreatedSeq: 1, inProgress: true, countsByState: {}, memberCount: 1, problem: false, problemKinds: [], ...over,
})
const page = (items: RoundEntry[], over: Partial<RoundsPage> = {}): RoundsPage => ({
  asOfSeq: 10, total: items.length, items, nextCursor: null, unknownAtSeq: 0, asOfAppNanos: 5_000_000_000, ...over,
})

function snapshot(coroutines: CoroutineNode[], over: Partial<SessionSnapshot> = {}): SessionSnapshot {
  return {
    sessionId: 'app-1', coroutineCount: coroutines.length, eventCount: 1, coroutines,
    kind: 'ATTACHED', origin: 'REPORTED', captureStartAppNanos: 0, ...over,
  }
}

function serve(s: SessionSnapshot) {
  vi.mocked(useSession).mockReturnValue({
    data: s, isLoading: false, isPending: false, refetch: vi.fn(), dataUpdatedAt: 1,
  } as unknown as ReturnType<typeof useSession>)
}

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

const graphIds = () => screen.getByTestId('graph-ids').textContent!.split(',').filter(Boolean)
const picker = () => screen.getByRole('combobox', { name: 'Round' })


const fetches = (n: number, parent = 'req', round = 'round:req') =>
  Array.from({ length: n }, (_, i) =>
    node(`f${String(i + 1).padStart(3, '0')}`, { parentId: parent, roundId: round, label: `fetch-${i + 1}`, state: CoroutineState.ACTIVE }),
  )
const groupId = (parent = 'req', round = 'round:req') => `group:${parent}\u241f${round}\u241ffetch-`

describe('SessionWorkspace — group by name (S-12)', () => {
  beforeEach(() => {
    localStorage.clear()
    getRounds.mockReset()
    getRounds.mockImplementation((_id, q) =>
      Promise.resolve(
        q?.q
          ? page([entry('req', { matches: { items: ['deep'], total: 1 } })])
          : page([entry('req'), entry('other')]),
      ),
    )
  })

  const appWith = (extra: CoroutineNode[]) => snapshot([node('req'), ...fetches(3), node('other'), ...extra])

  it('folds siblings into one group card by default in an attached app’s round, with the switch on', async () => {
    serve(appWith([]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req', groupId()]))
    expect(screen.getByRole('checkbox', { name: 'Group by name' })).toBeChecked()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Group by name' }))
    expect(graphIds()).toEqual(['req', 'f001', 'f002', 'f003'])
  })

  it('offers no grouping in All rounds, and starts it off for a demo', async () => {
    serve(appWith([]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req'))
    await userEvent.selectOptions(picker(), 'All rounds')
    expect(screen.queryByRole('checkbox', { name: 'Group by name' })).toBeNull()
  })

  it('expanding draws the members under the group; selecting it opens its summary in the Inspector', async () => {
    serve(appWith([]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req', groupId()]))
    await userEvent.click(screen.getByRole('button', { name: `toggle ${groupId()}` }))
    expect(graphIds()).toEqual(['req', groupId(), 'f001', 'f002', 'f003'])
    await userEvent.click(screen.getByRole('button', { name: 'select fetch-*' }))
    expect(await screen.findByTestId('group-summary')).toHaveTextContent('fetch-* group of 3 siblings')
    expect(screen.getByText('Under req')).toBeInTheDocument()
  })

  it('the Events tab shows only the group’s members’ events', async () => {
    serve(appWith([]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req', groupId()]))
    await userEvent.click(screen.getByRole('button', { name: 'select fetch-*' }))
    await userEvent.click(screen.getByRole('tab', { name: 'Events' }))
    expect(screen.getByTestId('events-scope')).toHaveTextContent('Events for fetch-* · 3 coroutines')
  })

  it('Find opens every collapsed group on the way to a coroutine nested below a member, and selects it', async () => {
    serve(appWith([node('deep', { parentId: 'f002', roundId: 'round:req', label: 'db-query' })]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req', groupId()]))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'db{Enter}')
    const results = await screen.findByRole('region', { name: 'Find results' })
    await userEvent.click(await within(results).findByRole('button', { name: /^db-query/ }))
    expect(graphIds()).toEqual(expect.arrayContaining([groupId(), 'f002', 'deep']))
    expect(screen.getByTestId('graph')).toHaveAttribute('data-reveal', 'deep')
  })

  it('the cap counts cards: a group of 300 is one card', async () => {
    getRounds.mockResolvedValue(page([entry('req')]))
    serve(snapshot([node('req'), ...fetches(300)]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req', groupId()]))
    expect(screen.queryByText(/^Showing \d/)).toBeNull()
  })

  it('a selected group that is gone (grouping turned off) is no longer selected', async () => {
    serve(appWith([]))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req', groupId()]))
    await userEvent.click(screen.getByRole('button', { name: 'select fetch-*' }))
    expect(await screen.findByTestId('group-summary')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: 'Group by name' }))
    await waitFor(() => expect(screen.queryByTestId('group-summary')).toBeNull())
  })
})

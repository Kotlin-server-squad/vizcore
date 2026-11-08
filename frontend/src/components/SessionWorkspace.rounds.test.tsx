import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import { CoroutineState, type CoroutineNode, type RoundEntry, type RoundsPage, type SessionSnapshot } from '@/types/api'

// Big apps (dock S-12, triage T-4): one round at a time, find in all rounds past the cap, the vizcore toggle.

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
  },
}))
// The graph lists what it is given, and the reveal it was asked for.
vi.mock('./CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: ({ coroutines, reveal }: { coroutines: CoroutineNode[]; reveal?: { id: string } }) => (
    <div data-testid="graph" data-reveal={reveal?.id ?? ''}>
      {coroutines.map(c => c.id).join(',')}
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

/** An app: two request rounds, a vizcore root, a big round of 250 members (c240 finished). */
const big = Array.from({ length: 250 }, (_, i) =>
  // Names without a trailing number: these siblings do not group by name (that is SessionWorkspace.groups.test.tsx).
  node(`c${String(i).padStart(3, '0')}`, {
    parentId: 'big',
    roundId: 'round:big',
    label: `c${String(i).padStart(3, '0')}-task`,
    state: i === 240 ? CoroutineState.COMPLETED : CoroutineState.ACTIVE,
  }),
)
const appNodes = [
  node('req-2'),
  node('req-2-db', { parentId: 'req-2', roundId: 'round:req-2' }),
  node('req-1'),
  node('aa-cio', { origin: 'VIZCORE' }),
  node('big'),
  ...big,
]

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

const graphIds = () => screen.getByTestId('graph').textContent!.split(',').filter(Boolean)
const picker = () => screen.getByRole('combobox', { name: 'Round' })

describe('SessionWorkspace — big apps (S-12)', () => {
  beforeEach(() => {
    localStorage.clear()
    getRounds.mockReset()
    getRounds.mockImplementation((_id, q) =>
      Promise.resolve(
        q?.q
          ? page([entry('big', { matches: { items: ['c240'], total: 1 } })])
          : page([entry('req-2'), entry('req-1'), entry('big'), ...(q?.includeVizcore ? [entry('aa-cio', { anchorOrigin: 'VIZCORE' })] : [])]),
      ),
    )
  })

  it('opens an attached app on the first listed round, and the state bar counts that round', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(graphIds()).toEqual(['req-2', 'req-2-db']))
    expect(picker()).toHaveValue('r|round:req-2')
    expect(within(picker()).getByRole('option', { name: 'req-2 · at 0 ms · 2 coroutines · in progress' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Active 2' })).toBeInTheDocument()
  })

  it('a demo opens on All rounds, and a one-round demo shows no round bar', async () => {
    getRounds.mockResolvedValue(page([entry('demo-root')]))
    // The server's kind is not trusted over the page's own knowledge: it calls some demo runs ATTACHED (sync-… ids).
    serve(snapshot([node('demo-root'), node('step', { parentId: 'demo-root', roundId: 'round:demo-root' })], { kind: 'ATTACHED' }))
    render(<SessionWorkspace sessionId="sync-e-commerce-1" scenarioId="x" startLive />, { wrapper })
    await waitFor(() => expect(getRounds).toHaveBeenCalled())
    expect(graphIds()).toEqual(['demo-root', 'step'])
    expect(screen.queryByRole('combobox', { name: 'Round' })).toBeNull()
  })

  it('switching rounds shows another round; All rounds leaves vizcore out until the toggle is on', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    await userEvent.selectOptions(picker(), 'All rounds')
    expect(graphIds()).not.toContain('aa-cio')
    await userEvent.click(screen.getByRole('checkbox', { name: 'vizcore connection (1)' }))
    await waitFor(() => expect(graphIds()).toContain('aa-cio'))
    expect(getRounds).toHaveBeenLastCalledWith('app-1', { limit: 50, includeVizcore: true })
  })

  it('Find reaches a coroutine past the cap: its round, the filters cleared, drawn, selected and brought into view', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'c240{Enter}')
    const results = await screen.findByRole('region', { name: 'Find results' })
    await userEvent.click(await within(results).findByRole('button', { name: /^c240/ }))

    expect(picker()).toHaveValue('r|round:big')
    // c240 is finished and past the first 200: shown anyway, with the members up to the cap.
    expect(graphIds()).toContain('c240')
    expect(screen.getByTestId('graph')).toHaveAttribute('data-reveal', 'c240')
    expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent(
      'Showing 201 of 251 coroutines in this round: the first 200, and the one you found.',
    )
    expect(screen.getByRole('heading', { name: /c240/ })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Find results' })).toBeNull()
  })

  it('a newer round arriving does not move the view', async () => {
    serve(snapshot(appNodes))
    const { rerender } = render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    getRounds.mockResolvedValue(page([entry('req-3'), entry('req-2'), entry('req-1')]))
    serve(snapshot([node('req-3'), ...appNodes]))
    rerender(<SessionWorkspace sessionId="app-1" startLive />)
    await userEvent.click(picker())
    expect(picker()).toHaveValue('r|round:req-2')
    expect(graphIds()).toEqual(['req-2', 'req-2-db'])
  })

  it('the paused view does not read the round list again when the picker gets focus', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    const reads = getRounds.mock.calls.length
    await userEvent.click(picker())
    expect(getRounds).toHaveBeenCalledTimes(reads)
  })

  it('without rounds, today’s page; an attached app says rounds are unavailable', async () => {
    getRounds.mockRejectedValue(new Error('503'))
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    expect(await screen.findByText('Rounds unavailable for this session')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Round' })).toBeNull()
    expect(graphIds()).toHaveLength(200)
  })

  it('a state chip chosen after a find filters again: the found coroutine is no longer forced', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'c240{Enter}')
    const results = await screen.findByRole('region', { name: 'Find results' })
    await userEvent.click(await within(results).findByRole('button', { name: /^c240/ }))
    expect(graphIds()).toContain('c240')
    await userEvent.click(screen.getByRole('button', { name: 'Active 250' }))
    expect(graphIds()).not.toContain('c240')
  })

  it('an open search keeps the vizcore rule it was submitted with: the toggle does not search again', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'c240{Enter}')
    await screen.findByRole('region', { name: 'Find results' })
    await userEvent.click(screen.getByRole('checkbox', { name: 'vizcore connection (1)' }))
    await waitFor(() => expect(getRounds).toHaveBeenLastCalledWith('app-1', { limit: 50, includeVizcore: true }))
    expect(getRounds.mock.calls.filter(([, q]) => q?.q)).toHaveLength(1)
  })

  it('find results stay bound to the vizcore rule they were searched with', async () => {
    getRounds.mockImplementation((_id, q) =>
      Promise.resolve(
        q?.q
          ? page([entry('aa-cio', { anchorOrigin: 'VIZCORE', matches: { items: ['aa-cio'], total: 1 } })])
          : page([entry('req-2'), entry('req-1')]),
      ),
    )
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'vizcore connection (1)' }))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'aa-cio{Enter}')
    const results = await screen.findByRole('region', { name: 'Find results' })
    expect(await within(results).findByRole('button', { name: /^aa-cio/ })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: 'vizcore connection (1)' }))
    expect(within(results).getByRole('button', { name: /^aa-cio/ })).toBeInTheDocument()
    expect(within(results).getByText(/^Found 1 in 1 round/)).toBeInTheDocument()
  })

  it('a capped one-round demo offers Find, which its cap note points to', async () => {
    const many = Array.from({ length: 300 }, (_, i) =>
      node(`s${String(i).padStart(3, '0')}`, { parentId: 'demo-root', roundId: 'round:demo-root' }),
    )
    getRounds.mockResolvedValue(page([entry('demo-root')]))
    serve(snapshot([node('demo-root'), ...many], { kind: 'DEMO' }))
    render(<SessionWorkspace sessionId="scenario-x-1" scenarioId="x" startLive />, { wrapper })
    expect(await screen.findByRole('searchbox', { name: 'Find in all rounds' })).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'Round' })).toBeNull()
    expect(screen.getByText(/Find reaches one by its name or creation site/)).toBeInTheDocument()
  })

  it('a failed round list comes back on Refresh', async () => {
    getRounds.mockRejectedValueOnce(new Error('503'))
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" />, { wrapper })
    expect(await screen.findByText('Rounds unavailable for this session')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /refresh/i }))
    await waitFor(() => expect(picker()).toBeInTheDocument())
  })

  it('a closed search reads nothing more when the vizcore toggle changes', async () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(picker()).toHaveValue('r|round:req-2'))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'c240{Enter}')
    const results = await screen.findByRole('region', { name: 'Find results' })
    await userEvent.click(within(results).getByRole('button', { name: 'Close' }))
    const finds = () => getRounds.mock.calls.filter(([, q]) => q?.q).length
    expect(finds()).toBe(1)
    await userEvent.click(screen.getByRole('checkbox', { name: 'vizcore connection (1)' }))
    await waitFor(() => expect(getRounds).toHaveBeenLastCalledWith('app-1', { limit: 50, includeVizcore: true }))
    expect(finds()).toBe(1)
  })

  it('a listed round the snapshot holds nothing of counts zero, as the canvas does', async () => {
    getRounds.mockResolvedValue(page([entry('req-2'), entry('gone', { memberCount: 7 })]))
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(within(picker()).getByRole('option', { name: /^gone/ })).toHaveTextContent('gone · at 0 ms · 0 coroutines'))
  })

  it('the shared view keeps today’s page and reads no rounds', () => {
    serve(snapshot(appNodes))
    render(<SessionWorkspace sessionId="app-1" readOnly />, { wrapper })
    expect(screen.queryByRole('combobox', { name: 'Round' })).toBeNull()
    expect(getRounds).not.toHaveBeenCalled()
  })
})

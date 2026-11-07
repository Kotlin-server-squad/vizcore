import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { SessionWorkspace } from './SessionWorkspace'
import { CoroutineState, type CoroutineNode, type RoundEntry, type RoundsPage, type SessionSnapshot } from '@/types/api'

// Find on a very deep path, with the REAL graph (S-12): only the zoom library and animation are stubbed, so the graph's
// own layout and walk run on what Find draws.

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
vi.mock('react-zoom-pan-pinch', async () => {
  const React = await import('react')
  const TransformWrapper = React.forwardRef(function TransformWrapper(
    props: Record<string, unknown>,
    ref: React.ForwardedRef<unknown>,
  ) {
    const api = { setTransform: () => {}, state: { positionX: 0, positionY: 0, scale: 1 } }
    React.useImperativeHandle(ref, () => api)
    const { children } = props
    return <div>{typeof children === 'function' ? children(api) : (children as ReactNode)}</div>
  })
  return { TransformWrapper, TransformComponent: ({ children }: { children: ReactNode }) => <div>{children}</div> }
})
vi.mock('framer-motion', async () => {
  const actual = await vi.importActual<typeof import('framer-motion')>('framer-motion')
  const pass = ({ children, ...p }: Record<string, unknown>) => <div {...(p as object)}>{children as ReactNode}</div>
  return { ...actual, motion: { div: pass, span: pass, button: pass, circle: () => null }, AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>, LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</> }
})
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




describe('Find on a path thousands of levels deep (real graph)', () => {
  beforeEach(() => {
    localStorage.clear()
    getRounds.mockReset()
  })

  it('draws the found coroutine with its nearest ancestors, says the rest of the path is not drawn, and does not crash', async () => {
    // 4,000 levels, each a pair of siblings that group by name, under the first sibling of the level above.
    const cs = [node('root', { roundId: 'round:root' })]
    let parent = 'root'
    for (let level = 0; level < 4_000; level++) {
      cs.push(
        node(`a${level}`, { parentId: parent, roundId: 'round:root', label: `a-${level}-1` }),
        node(`b${level}`, { parentId: parent, roundId: 'round:root', label: `a-${level}-2` }),
      )
      parent = `a${level}`
    }
    getRounds.mockImplementation((_id, q) =>
      Promise.resolve(q?.q ? page([entry('root', { matches: { items: ['a3999'], total: 1 } })]) : page([entry('root')])),
    )
    serve(snapshot(cs))
    render(<SessionWorkspace sessionId="app-1" startLive />, { wrapper })
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Round' })).toHaveValue('r|round:root'))
    await userEvent.type(screen.getByRole('searchbox', { name: 'Find in all rounds' }), 'a-3999-1{Enter}')
    const results = await screen.findByRole('region', { name: 'Find results' })
    await userEvent.click(await within(results).findByRole('button', { name: /^a-3999-1/ }))

    expect(await screen.findByTestId('graph-node-a3999')).toBeInTheDocument()
    expect(screen.getByTestId('graph-node-a3949')).toBeInTheDocument()
    expect(screen.queryByTestId('graph-node-a3948')).toBeNull()
    expect(screen.getByRole('region', { name: 'Main view' })).toHaveTextContent(
      'The coroutine you found is 4,000 levels below its round’s root: it is drawn with its nearest 50 ancestors only.',
    )
  }, 30_000)
})

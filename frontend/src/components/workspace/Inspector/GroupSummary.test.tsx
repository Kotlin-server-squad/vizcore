import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { GroupSummary } from './GroupSummary'
import { apiClient } from '@/lib/api-client'
import type { NameGroup } from '@/lib/name-groups'

vi.mock('@/lib/api-client', () => ({ apiClient: { getRoundHierarchy: vi.fn() } }))
const getRoundHierarchy = vi.mocked(apiClient.getRoundHierarchy)

const group: NameGroup = {
  id: 'group:p', label: 'fetch-*', parentId: 'p', roundId: 'round:p', memberIds: ['a', 'b', 'c'],
  counts: { COMPLETED: 2, FAILED: 1 }, below: 30, expanded: false,
}
const site = { function: 'fetch', fileName: 'App.kt', lineNumber: 42, reason: 'launch' }
const hierarchyNode = (id: string, creationPoint: typeof site | null) =>
  ({ id, parentId: 'p', children: [], name: id, scopeId: 's', state: 'COMPLETED', createdAtNanos: 0, jobId: 'j', creationPoint }) as never

// One client per test: a client made on every render would drop a running query and leave its rejection unhandled.
let client: QueryClient
function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

const props = (over: Partial<Parameters<typeof GroupSummary>[0]> = {}) => ({
  sessionId: 's',
  group,
  parentLabel: 'request-1',
  memberLabel: (id: string) => `fetch-${id}`,
  onRevealMember: vi.fn(),
  onToggle: vi.fn(),
  onClearSelection: vi.fn(),
  ...over,
})

describe('GroupSummary (S-12)', () => {
  beforeEach(() => {
    getRoundHierarchy.mockReset()
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  })

  it('sums the group up: its siblings, parent, states in words and what is below them', async () => {
    getRoundHierarchy.mockResolvedValue([hierarchyNode('a', site), hierarchyNode('b', site), hierarchyNode('c', site)])
    render(<GroupSummary {...props()} />, { wrapper })
    expect(screen.getByRole('heading', { name: /fetch-\*/ })).toHaveTextContent('fetch-* group of 3 siblings')
    expect(screen.getByText('Under request-1')).toBeInTheDocument()
    expect(screen.getByText('1 Failed · 2 Completed')).toBeInTheDocument()
    expect(screen.getByText('+30 coroutines below them')).toBeInTheDocument()
    expect(await screen.findByText('All created at App.kt:42 (fetch)')).toBeInTheDocument()
    expect(getRoundHierarchy).toHaveBeenCalledTimes(1)
    expect(getRoundHierarchy).toHaveBeenCalledWith('s', 'round:p')
  })

  it('says loading, then partial knowledge honestly', async () => {
    getRoundHierarchy.mockResolvedValue([hierarchyNode('a', site)])
    render(<GroupSummary {...props()} />, { wrapper })
    expect(screen.getByText('Loading creation sites…')).toBeInTheDocument()
    expect(await screen.findByText('At App.kt:42 (fetch) for 1; unavailable for 2')).toBeInTheDocument()
  })

  it('says when the read failed', async () => {
    getRoundHierarchy.mockImplementation(() => Promise.reject(new Error('503')))
    render(<GroupSummary {...props()} />, { wrapper })
    expect(await screen.findByText('Creation sites unavailable (the request failed)')).toBeInTheDocument()
  })

  it('reads nothing for siblings without a round, and says their site is unavailable', () => {
    render(<GroupSummary {...props({ group: { ...group, roundId: null } })} />, { wrapper })
    expect(screen.getByText('Creation site unavailable')).toBeInTheDocument()
    expect(getRoundHierarchy).not.toHaveBeenCalled()
  })

  it('reads again when the members change', async () => {
    getRoundHierarchy.mockResolvedValue([])
    const { rerender } = render(<GroupSummary {...props()} />, { wrapper })
    await waitFor(() => expect(getRoundHierarchy).toHaveBeenCalledTimes(1))
    rerender(<GroupSummary {...props({ group: { ...group, memberIds: ['a', 'b', 'c', 'd'] } })} />)
    await waitFor(() => expect(getRoundHierarchy).toHaveBeenCalledTimes(2))
  })

  it('does not read again when the same members are only listed in another order', async () => {
    getRoundHierarchy.mockResolvedValue([])
    const { rerender } = render(<GroupSummary {...props()} />, { wrapper })
    await waitFor(() => expect(getRoundHierarchy).toHaveBeenCalledTimes(1))
    rerender(<GroupSummary {...props({ group: { ...group, memberIds: ['c', 'b', 'a'] } })} />)
    await new Promise(r => setTimeout(r, 50))
    expect(getRoundHierarchy).toHaveBeenCalledTimes(1)
  })

  it('reveals a member, expands the group and goes back to the session summary', async () => {
    getRoundHierarchy.mockResolvedValue([])
    const p = props()
    render(<GroupSummary {...p} />, { wrapper })
    await userEvent.click(screen.getByRole('button', { name: 'fetch-b' }))
    expect(p.onRevealMember).toHaveBeenCalledWith('b')
    await userEvent.click(screen.getByRole('button', { name: 'Expand the group' }))
    expect(p.onToggle).toHaveBeenCalledWith('group:p')
    await userEvent.click(screen.getByRole('button', { name: 'Session summary' }))
    expect(p.onClearSelection).toHaveBeenCalled()
  })
})

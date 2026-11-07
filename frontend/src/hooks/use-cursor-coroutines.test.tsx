import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useCursorCoroutines } from './use-cursor-coroutines'
import { indexReplay } from '@/lib/replay-index'
import { CoroutineState, type CoroutineNode, type VizEvent } from '@/types/api'

const getHierarchyAt = vi.fn()
vi.mock('@/lib/api-client', () => ({ apiClient: { getHierarchyAt: (...a: unknown[]) => getHierarchyAt(...a) } }))

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
)

const projected: CoroutineNode[] = [{ id: 'a', jobId: 'j', parentId: 'root', scopeId: 's', label: 'a', state: CoroutineState.ACTIVE }]
const events = [{ seq: 41, tsNanos: 1, sessionId: 's', kind: 'coroutine.created', coroutineId: 'a' }] as unknown as VizEvent[]
const read = { source: { kind: 'session', sessionId: 's' }, binding: { kind: 'replay', atSeq: 41 } } as const

beforeEach(() => vi.clearAllMocks())

describe('useCursorCoroutines', () => {
  it('reads nothing when the replay starts at the beginning, and returns the projection as it is', () => {
    const { result } = renderHook(
      () => useCursorCoroutines({ read, projected, cursorSeq: 41, index: indexReplay(events), incomplete: false, settleMs: 5, hold: false }),
      { wrapper },
    )
    expect(result.current.coroutines).toBe(projected)
    expect(result.current.status).toBe('off')
    expect(getHierarchyAt).not.toHaveBeenCalled()
  })

  it('adds the coroutines created before the replay from the server’s summaries at the cursor', async () => {
    getHierarchyAt.mockResolvedValue([
      { id: 'root', parentId: null, name: 'root', scopeId: null, jobId: null, state: 'SUSPENDED', summaryState: 'SUSPENDED', createdAtNanos: 0 },
      { id: 'a', parentId: 'root', name: 'a', scopeId: null, jobId: null, state: 'COMPLETED', summaryState: 'COMPLETED', createdAtNanos: 1 },
    ])
    const { result } = renderHook(
      () => useCursorCoroutines({ read, projected, cursorSeq: 41, index: indexReplay(events), incomplete: true, settleMs: 5, hold: false }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.coroutines.map(c => [c.id, c.state])).toEqual([
      ['root', CoroutineState.SUSPENDED],
      ['a', CoroutineState.ACTIVE],
    ])
    expect(getHierarchyAt).toHaveBeenCalledWith(read)
  })

  it('keeps the projection and says so when the read fails', async () => {
    getHierarchyAt.mockRejectedValue(new Error('down'))
    const { result } = renderHook(
      () => useCursorCoroutines({ read, projected, cursorSeq: 41, index: indexReplay(events), incomplete: true, settleMs: 5, hold: false }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.status).toBe('error'))
    expect(result.current.coroutines).toBe(projected)
  })
})

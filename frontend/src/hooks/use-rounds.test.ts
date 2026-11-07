import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { useRounds, useRoundSearch } from './use-rounds'
import { apiClient } from '@/lib/api-client'
import type { RoundsPage } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: { getRounds: vi.fn() },
}))

const getRounds = vi.mocked(apiClient.getRounds)

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)
}

const page = (ids: string[], nextCursor: string | null = null): RoundsPage => ({
  asOfSeq: 1,
  total: 3,
  items: ids.map(id => ({
    roundId: `round:${id}`,
    anchorId: id,
    anchorName: id,
    anchorOrigin: 'APP',
    anchorCreatedNanos: 0,
    anchorCreatedSeq: 1,
    inProgress: false,
    countsByState: {},
    memberCount: 1,
    problem: false,
    problemKinds: [],
  })),
  nextCursor,
  unknownAtSeq: 0,
})

describe('useRounds', () => {
  beforeEach(() => vi.clearAllMocks())

  it('reads one page, and more rounds by raising the limit up to 200, one request each time', async () => {
    getRounds.mockResolvedValueOnce(page(['a', 'b'], 'cur-1')).mockResolvedValue(page(['a', 'b', 'c'], 'cur-2'))
    const { result } = renderHook(() => useRounds('s', { includeVizcore: false, enabled: true }), { wrapper: wrapper() })

    await waitFor(() => expect(result.current.rounds.map(r => r.anchorId)).toEqual(['a', 'b']))
    expect(getRounds).toHaveBeenCalledWith('s', { limit: 50, includeVizcore: false })
    expect(result.current.hasMore).toBe(true)
    expect(result.current.total).toBe(3)

    act(() => result.current.loadMore())
    await waitFor(() => expect(result.current.rounds.map(r => r.anchorId)).toEqual(['a', 'b', 'c']))
    expect(getRounds).toHaveBeenLastCalledWith('s', { limit: 100, includeVizcore: false })
    act(() => result.current.loadMore())
    await waitFor(() => expect(getRounds).toHaveBeenLastCalledWith('s', { limit: 150, includeVizcore: false }))
    act(() => result.current.loadMore())
    await waitFor(() => expect(getRounds).toHaveBeenLastCalledWith('s', { limit: 200, includeVizcore: false }))
    expect(result.current.canLoadMore).toBe(false)
    const calls = getRounds.mock.calls.length
    act(() => result.current.loadMore())
    expect(getRounds).toHaveBeenCalledTimes(calls)
  })

  it('asks again with the vizcore toggle', async () => {
    getRounds.mockResolvedValue(page(['a']))
    const { result, rerender } = renderHook(
      ({ includeVizcore }) => useRounds('s', { includeVizcore, enabled: true }),
      { wrapper: wrapper(), initialProps: { includeVizcore: false } },
    )
    await waitFor(() => expect(result.current.status).toBe('success'))
    rerender({ includeVizcore: true })
    await waitFor(() => expect(getRounds).toHaveBeenLastCalledWith('s', expect.objectContaining({ includeVizcore: true })))
  })

  it('reads nothing while disabled', () => {
    renderHook(() => useRounds('s', { includeVizcore: false, enabled: false }), { wrapper: wrapper() })
    expect(getRounds).not.toHaveBeenCalled()
  })

  it('reports a failure', async () => {
    getRounds.mockRejectedValue(new Error('503'))
    const { result } = renderHook(() => useRounds('s', { includeVizcore: false, enabled: true }), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.status).toBe('error'))
    expect(result.current.rounds).toEqual([])
  })
})

describe('useRoundSearch', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sends nothing until a query is submitted, then one request per submit', async () => {
    getRounds.mockResolvedValue(page(['a']))
    const { result, rerender } = renderHook(
      ({ q }) => useRoundSearch('s', { q }),
      { wrapper: wrapper(), initialProps: { q: null as { text: string; at: number; includeVizcore: boolean } | null } },
    )
    expect(getRounds).not.toHaveBeenCalled()

    rerender({ q: { text: 'fetch-2', at: 1, includeVizcore: false } })
    await waitFor(() => expect(result.current.data?.items).toHaveLength(1))
    expect(getRounds).toHaveBeenCalledWith('s', { limit: 200, q: 'fetch-2', includeVizcore: false })

    rerender({ q: { text: 'fetch-2', at: 2, includeVizcore: false } })
    await waitFor(() => expect(getRounds).toHaveBeenCalledTimes(2))
  })
})

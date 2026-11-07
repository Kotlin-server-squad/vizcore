import { describe, it, expect, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useMomentQuery, type MomentQueryArgs } from './use-moment-query'
import { ApiError } from '@/lib/api-errors'

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

/** A read whose value is the seq it was fetched at; "x" changes at seqs 5 and 9. */
function args(over: Partial<MomentQueryArgs<string>> = {}): MomentQueryArgs<string> {
  const changes = [5, 9]
  return {
    enabled: true,
    identity: 'x',
    keyAt: seq => ['test', 'x', seq],
    fetchAt: vi.fn(async (seq: number) => `x@${seq}`),
    cursorSeq: 1,
    changesBetween: (a, b) => changes.some(s => s > Math.min(a, b) && s <= Math.max(a, b)),
    settleMs: 20,
    ...over,
  }
}

describe('useMomentQuery', () => {
  it('reads the cursor at once when it first shows', async () => {
    const a = args({ cursorSeq: 3 })
    const { result } = renderHook(() => useMomentQuery(a), { wrapper: wrapper() })
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.data).toBe('x@3')
    expect(a.fetchAt).toHaveBeenCalledTimes(1)
  })

  it('reuses a read across seqs where nothing about it changed, without a request', async () => {
    const fetchAt = vi.fn(async (seq: number) => `x@${seq}`)
    const { result, rerender } = renderHook(({ cursorSeq }) => useMomentQuery(args({ fetchAt, cursorSeq })), {
      wrapper: wrapper(),
      initialProps: { cursorSeq: 1 },
    })
    await waitFor(() => expect(result.current.data).toBe('x@1'))
    rerender({ cursorSeq: 4 })
    expect(result.current.status).toBe('ready')
    expect(result.current.data).toBe('x@1')
    expect(fetchAt).toHaveBeenCalledTimes(1)
  })

  it('never shows a read from the other side of a change: it says updating, then reads the cursor once it settles', async () => {
    const fetchAt = vi.fn(async (seq: number) => `x@${seq}`)
    const { result, rerender } = renderHook(({ cursorSeq }) => useMomentQuery(args({ fetchAt, cursorSeq })), {
      wrapper: wrapper(),
      initialProps: { cursorSeq: 7 },
    })
    await waitFor(() => expect(result.current.data).toBe('x@7'))
    // Back across the change at 5: the read at 7 must not stand for 3.
    rerender({ cursorSeq: 3 })
    expect(result.current.status).toBe('updating')
    expect(result.current.data).toBeUndefined()
    expect(fetchAt).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(result.current.data).toBe('x@3'))
    expect(fetchAt).toHaveBeenCalledTimes(2)
  })

  it('waits while held (a shared replay that is playing), and reads once released', async () => {
    const fetchAt = vi.fn(async (seq: number) => `x@${seq}`)
    const { result, rerender } = renderHook(
      ({ cursorSeq, hold }) => useMomentQuery(args({ fetchAt, cursorSeq, hold })),
      { wrapper: wrapper(), initialProps: { cursorSeq: 1, hold: false } },
    )
    await waitFor(() => expect(result.current.data).toBe('x@1'))
    rerender({ cursorSeq: 6, hold: true })
    await act(() => new Promise(r => setTimeout(r, 100)))
    expect(fetchAt).toHaveBeenCalledTimes(1)
    expect(result.current.status).toBe('updating')
    rerender({ cursorSeq: 6, hold: false })
    await waitFor(() => expect(result.current.data).toBe('x@6'))
  })

  it('never shows another identity’s read (a different coroutine)', async () => {
    const { result, rerender } = renderHook(
      ({ identity }) =>
        useMomentQuery(args({ identity, keyAt: seq => ['test', identity, seq], fetchAt: async seq => `${identity}@${seq}` })),
      { wrapper: wrapper(), initialProps: { identity: 'x' } },
    )
    await waitFor(() => expect(result.current.data).toBe('x@1'))
    rerender({ identity: 'y' })
    expect(result.current.data).not.toBe('x@1')
    await waitFor(() => expect(result.current.data).toBe('y@1'))
  })

  it('shares one read between two users of the same key (the tree and the Summary card read one hierarchy)', async () => {
    const fetchAt = vi.fn(async (seq: number) => `h@${seq}`)
    const { result } = renderHook(
      () => ({
        tree: useMomentQuery(args({ identity: 'hierarchy', keyAt: seq => ['h', seq], fetchAt })),
        card: useMomentQuery(args({ identity: 'summary:c', keyAt: seq => ['h', seq], fetchAt })),
      }),
      { wrapper: wrapper() },
    )
    await waitFor(() => expect(result.current.card.data).toBe('h@1'))
    expect(result.current.tree.data).toBe('h@1')
  })

  it('reports a failed read with its error', async () => {
    const error = new ApiError(409, 'moved on', 'stale')
    const { result } = renderHook(() => useMomentQuery(args({ fetchAt: async () => Promise.reject(error) })), {
      wrapper: wrapper(),
    })
    await waitFor(() => expect(result.current.status).toBe('error'))
    expect(result.current.error).toBe(error)
  })

  it('reads nothing while held from the start, then reads the cursor once released', async () => {
    const fetchAt = vi.fn(async (seq: number) => `x@${seq}`)
    const { result, rerender } = renderHook(({ hold }) => useMomentQuery(args({ fetchAt, cursorSeq: 4, hold })), {
      wrapper: wrapper(),
      initialProps: { hold: true },
    })
    await act(() => new Promise(r => setTimeout(r, 50)))
    expect(fetchAt).not.toHaveBeenCalled()
    rerender({ hold: false })
    await waitFor(() => expect(result.current.data).toBe('x@4'))
    expect(fetchAt).toHaveBeenCalledTimes(1)
  })

  it('reads the cursor, not an old seq, when it is enabled later', async () => {
    const fetchAt = vi.fn(async (seq: number) => `x@${seq}`)
    const { result, rerender } = renderHook(
      ({ enabled, cursorSeq }) => useMomentQuery(args({ fetchAt, enabled, cursorSeq })),
      { wrapper: wrapper(), initialProps: { enabled: false, cursorSeq: 0 } },
    )
    rerender({ enabled: false, cursorSeq: 41 })
    rerender({ enabled: true, cursorSeq: 41 })
    await waitFor(() => expect(result.current.data).toBe('x@41'))
    expect(fetchAt.mock.calls.map(c => c[0])).toEqual([41])
  })

  it('reads nothing while disabled', () => {
    const a = args({ enabled: false })
    const { result } = renderHook(() => useMomentQuery(a), { wrapper: wrapper() })
    expect(a.fetchAt).not.toHaveBeenCalled()
    expect(result.current.data).toBeUndefined()
  })
})

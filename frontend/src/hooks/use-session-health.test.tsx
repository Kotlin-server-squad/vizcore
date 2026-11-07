import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useSessionHealth, sessionHealthKey } from './use-session-health'
import { apiClient } from '@/lib/api-client'
import type { MomentView } from './use-moment'
import type { SessionHealth } from '@/types/health'
import { ApiError } from '@/lib/api-errors'

vi.mock('@/lib/api-client', () => ({ apiClient: { getHealthAt: vi.fn() } }))

const getHealthAt = vi.mocked(apiClient.getHealthAt)
const H = (asOfSeq: number) => ({ state: 'NO_PROBLEMS', asOfSeq }) as unknown as SessionHealth

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
  return { client, wrapper }
}

function replayView(atSeq: number, shared = false, hold = false): MomentView {
  return {
    read: {
      source: shared ? { kind: 'shared', sessionId: 's', token: 't' } : { kind: 'session', sessionId: 's' },
      binding: { kind: 'replay', atSeq },
    },
    cursorSeq: atSeq,
    changesBetween: () => false,
    settleMs: 10,
    hold,
    moment: { kind: 'replay', atNanos: 0, index: 0, total: 1 },
  }
}

beforeEach(() => {
  getHealthAt.mockReset()
})

describe('useSessionHealth (P-1)', () => {
  it('live: reads the session health once, under its own key, and does not poll', async () => {
    getHealthAt.mockResolvedValue(H(7))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'live' } }), { wrapper })
    await waitFor(() => expect(result.current.health?.asOfSeq).toBe(7))
    expect(getHealthAt).toHaveBeenCalledWith({ source: { kind: 'session', sessionId: 's' }, binding: { kind: 'current' } })
    expect(client.getQueryState(sessionHealthKey('s'))).toBeDefined()
    expect(client.getQueryCache().find({ queryKey: sessionHealthKey('s') })?.options).not.toHaveProperty('refetchInterval', expect.any(Number))
  })

  it('replay: reads the health at the cursor, bound to it', async () => {
    getHealthAt.mockImplementation(async read => H(read.binding.kind === 'replay' ? read.binding.atSeq : -1))
    const { wrapper } = setup()
    const { result } = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'moment', view: replayView(12) } }), { wrapper })
    await waitFor(() => expect(result.current.health?.asOfSeq).toBe(12))
    expect(getHealthAt.mock.calls[0]![0]).toEqual({ source: { kind: 'session', sessionId: 's' }, binding: { kind: 'replay', atSeq: 12 } })
  })

  it('a shared replay reads through the token, and not while it plays', async () => {
    getHealthAt.mockResolvedValue(H(3))
    const { wrapper } = setup()
    const { result, rerender } = renderHook(({ hold }) => useSessionHealth({ sessionId: 's', mode: { kind: 'moment', view: replayView(3, true, hold) } }), {
      wrapper,
      initialProps: { hold: true },
    })
    await new Promise(r => setTimeout(r, 30))
    expect(getHealthAt).not.toHaveBeenCalled()
    rerender({ hold: false })
    await waitFor(() => expect(result.current.health?.asOfSeq).toBe(3))
    expect(getHealthAt.mock.calls[0]![0].source).toEqual({ kind: 'shared', sessionId: 's', token: 't' })
  })

  it('a shared link not replaying uses the loaded health, with no request', () => {
    const { wrapper } = setup()
    const { result } = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'loaded', health: H(40), unavailable: null } }), { wrapper })
    expect(result.current).toMatchObject({ status: 'ready', health: { asOfSeq: 40 } })
    expect(getHealthAt).not.toHaveBeenCalled()
  })

  it('a shared link without a health says why', () => {
    const { wrapper } = setup()
    const { result } = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'loaded', health: null, unavailable: 'history-trimmed' } }), { wrapper })
    expect(result.current.status).toBe('error')
    expect(result.current.error).toBeInstanceOf(ApiError)
    expect((result.current.error as ApiError).code).toBe('history-trimmed')
  })

  it('keeps a result whose refresh failed, and says the refresh failed (never a silent green)', async () => {
    getHealthAt.mockResolvedValueOnce(H(7))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'live' } }), { wrapper })
    await waitFor(() => expect(result.current.health?.asOfSeq).toBe(7))
    getHealthAt.mockRejectedValueOnce(new ApiError(503, 'x', 'summaries-unavailable'))
    await client.refetchQueries({ queryKey: sessionHealthKey('s') })
    await waitFor(() => expect(result.current.refreshError).toBeInstanceOf(ApiError))
    expect(result.current.health?.asOfSeq).toBe(7)
  })

  it('reads the health again on every visit to the page', async () => {
    getHealthAt.mockResolvedValue(H(7))
    const { wrapper } = setup()
    const first = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'live' } }), { wrapper })
    await waitFor(() => expect(first.result.current.health?.asOfSeq).toBe(7))
    first.unmount()
    getHealthAt.mockResolvedValue(H(9))
    const second = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'live' } }), { wrapper })
    await waitFor(() => expect(second.result.current.health?.asOfSeq).toBe(9))
  })

  it('reports a failed read as an error', async () => {
    getHealthAt.mockRejectedValue(new ApiError(503, 'x', 'summaries-unavailable'))
    const { wrapper } = setup()
    const { result } = renderHook(() => useSessionHealth({ sessionId: 's', mode: { kind: 'live' } }), { wrapper })
    await waitFor(() => expect(result.current.status).toBe('error'))
  })
})

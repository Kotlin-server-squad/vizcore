/**
 * Heartbeat watchdog: a live stream whose socket dies silently (laptop sleep,
 * a proxy that keeps the browser side open) fires no EventSource error, so the
 * view kept claiming "connected" forever. The backend sends `event: heartbeat`
 * every 15 s; once one has been seen, HEARTBEAT_TIMEOUT_MS of silence sends the
 * stream through the fatal-error retry path. Servers that never send a
 * heartbeat must be unaffected.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { HEARTBEAT_TIMEOUT_MS, useEventStream } from './use-event-stream'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    createEventSource: vi.fn(),
  },
}))

vi.mock('@/lib/utils', () => ({
  normalizeEvent: vi.fn((e: unknown) => e),
}))

import { apiClient } from '@/lib/api-client'

const mockedApiClient = vi.mocked(apiClient)

class MockEventSource {
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  readyState: number | undefined = undefined
  listeners = new Map<string, ((e: Event) => void)[]>()

  addEventListener(type: string, handler: (e: Event) => void) {
    if (!this.listeners.has(type)) this.listeners.set(type, [])
    this.listeners.get(type)!.push(handler)
  }

  close = vi.fn()

  simulateEvent(type: string, data: string) {
    const handlers = this.listeners.get(type) || []
    handlers.forEach((h) => h({ data } as unknown as Event))
  }
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

const heartbeat = () => JSON.stringify({ ts: Date.now() })

const eventPayload = (seq: number) =>
  JSON.stringify({
    type: 'CoroutineCreated',
    sessionId: 'session-1',
    seq,
    tsNanos: 1000 + seq,
    coroutineId: `c${seq}`,
    jobId: `j${seq}`,
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: `test-${seq}`,
  })

describe('useEventStream - heartbeat watchdog', () => {
  let sources: MockEventSource[]
  let queryClient: QueryClient

  const latest = (): MockEventSource => {
    const source = sources.at(-1)
    if (!source) throw new Error('no EventSource has been created yet')
    return source
  }

  /** Open the current source and deliver its first heartbeat (arms the watchdog). */
  const openWithHeartbeat = () => {
    act(() => {
      latest().onopen?.()
      latest().simulateEvent('heartbeat', heartbeat())
    })
  }

  const advance = (ms: number) => {
    act(() => {
      vi.advanceTimersByTime(ms)
    })
  }

  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    sources = []
    mockedApiClient.createEventSource.mockImplementation(() => {
      const source = new MockEventSource()
      sources.push(source)
      return source as unknown as EventSource
    })
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('stays idle when the server never sends a heartbeat', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    act(() => {
      latest().onopen?.()
    })

    advance(5 * 60_000)

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
    expect(result.current.isConnected).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('treats silence after a heartbeat as a lost connection and reconnects', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()
    const first = latest()

    // Still inside the window: nothing happens.
    advance(HEARTBEAT_TIMEOUT_MS - 1000)
    expect(result.current.isConnected).toBe(true)
    expect(first.close).not.toHaveBeenCalled()

    // Past the window (the watchdog checks every 5 s).
    advance(6000)
    expect(first.close).toHaveBeenCalled()
    expect(result.current.isConnected).toBe(false)
    expect(result.current.error).toBe('Connection lost — retrying')

    // The fatal-error backoff then opens a new connection.
    advance(1000)
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(2)
  })

  it('keeps a connection alive while heartbeats arrive every 15 s', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()

    for (let i = 0; i < 20; i++) {
      advance(15_000)
      act(() => {
        latest().simulateEvent('heartbeat', heartbeat())
      })
    }

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBeNull()
    // Heartbeats never reach the event list.
    expect(result.current.events).toHaveLength(0)
  })

  it('counts ordinary events as signs of life', () => {
    renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()

    for (let seq = 1; seq <= 12; seq++) {
      advance(10_000)
      act(() => {
        latest().simulateEvent('CoroutineCreated', eventPayload(seq))
      })
    }

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
  })

  it('stops after the retry budget when every new connection stays silent', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()

    // Each attempt hangs silently: watchdog fires, backoff, new attempt (no
    // open, so the retry budget is not restored).
    advance(10 * 60_000)

    // One initial connection plus the five budgeted retries, then it gives up.
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(6)
    expect(result.current.error).toBe('Connection lost')
  })

  it('stops after the retry budget when every new connection opens but stays silent', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()

    // Each replacement connection opens (a proxy accepting it, say) but never
    // delivers a frame: the open must not restore the budget.
    const opened = new Set<MockEventSource>([latest()])
    for (let step = 0; step < 120; step++) {
      advance(5000)
      const current = latest()
      if (!opened.has(current)) {
        opened.add(current)
        act(() => {
          current.onopen?.()
        })
      }
    }

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(6)
    expect(result.current.error).toBe('Connection lost')
  })

  it('recovers through Reconnect when the server stops sending heartbeats', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()

    // From now on the server sends none: automatic retries treat each quiet
    // connection as dead and the budget runs out.
    const opened = new Set<MockEventSource>([latest()])
    for (let step = 0; step < 120; step++) {
      advance(5000)
      const current = latest()
      if (!opened.has(current)) {
        opened.add(current)
        act(() => {
          current.onopen?.()
        })
      }
    }
    expect(result.current.error).toBe('Connection lost')

    // A manual reconnect forgets the old heartbeat, so a quiet connection stays up.
    act(() => {
      result.current.reconnect()
    })
    act(() => {
      latest().onopen?.()
    })
    const created = mockedApiClient.createEventSource.mock.calls.length
    advance(5 * 60_000)
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(created)
    expect(result.current.isConnected).toBe(true)
    expect(result.current.error).toBeNull()
  })

  it('clears the watchdog on unmount', () => {
    const { unmount } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    openWithHeartbeat()
    unmount()

    advance(5 * 60_000)

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
    expect(vi.getTimerCount()).toBe(0)
  })
})

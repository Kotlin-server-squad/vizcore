/**
 * Request-budget tests for the live stream (#124 / #137): SSE-driven cache
 * refreshes are throttled, exact-keyed (never the full /events history), and
 * live events are appended in bounded, batched state updates.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider, QueryObserver } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useEventStream, LIVE_EVENTS_MAX, LIVE_REFRESH_MIN_INTERVAL_MS } from './use-event-stream'

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

/**
 * Build an event payload with a unique monotonic seq. Real backend streams
 * have monotonically increasing seqs; the hook's replay-dedup guard (01-15)
 * correctly drops literal seq duplicates, so every simulated event must carry
 * a fresh seq.
 */
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

/** Count invalidateQueries calls whose queryKey starts with the given root. */
function invalidationCount(queryClient: QueryClient, keyRoot: string): number {
  return vi
    .mocked(queryClient.invalidateQueries)
    .mock.calls.filter(([arg]) => {
      const key = (arg as { queryKey?: unknown[] } | undefined)?.queryKey
      return Array.isArray(key) && key[0] === keyRoot
    }).length
}

describe('useEventStream - throttled, exact-keyed refresh (#124/#137)', () => {
  let mockEventSource: MockEventSource
  let queryClient: QueryClient

  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mockEventSource = new MockEventSource()
    mockedApiClient.createEventSource.mockReturnValue(
      mockEventSource as unknown as EventSource,
    )
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    })
    vi.spyOn(queryClient, 'invalidateQueries')
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('coalesces a burst into one refresh, shortly after its first event', () => {
    renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      for (let i = 0; i < 5; i++) {
        mockEventSource.simulateEvent('CoroutineCreated', eventPayload(i + 1))
      }
      vi.advanceTimersByTime(100)
    })
    expect(queryClient.invalidateQueries).toHaveBeenCalledTimes(0)

    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(invalidationCount(queryClient, 'sessions')).toBe(1)
  })

  it('refreshes only the exact snapshot, threads and metrics keys — never the /events history', () => {
    renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', eventPayload(1))
      vi.advanceTimersByTime(600)
    })

    const calls = vi.mocked(queryClient.invalidateQueries).mock.calls.map(([arg]) => arg)
    expect(calls).toEqual([
      { queryKey: ['sessions', 'session-1'], exact: true },
      { queryKey: ['thread-activity', 'session-1'], exact: true },
      { queryKey: ['session-metrics', 'session-1'], exact: true },
    ])
  })

  it('does not refetch an /events query while SSE is connected (#137)', async () => {
    const eventsFetch = vi.fn().mockResolvedValue([])
    const snapshotFetch = vi.fn().mockResolvedValue({ sessionId: 'session-1' })
    // Active observers for both keys, exactly as the workspace mounts them.
    const unsubscribe = [
      new QueryObserver(queryClient, { queryKey: ['sessions', 'session-1'], queryFn: snapshotFetch }),
      new QueryObserver(queryClient, {
        queryKey: ['sessions', 'session-1', 'events'],
        queryFn: eventsFetch,
      }),
    ].map(observer => observer.subscribe(() => {}))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(eventsFetch).toHaveBeenCalledTimes(1)
    eventsFetch.mockClear()
    snapshotFetch.mockClear()

    renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    act(() => {
      mockEventSource.onopen?.()
    })

    for (let i = 0; i < 20; i++) {
      act(() => {
        mockEventSource.simulateEvent('CoroutineCreated', eventPayload(i + 1))
        vi.advanceTimersByTime(1000)
      })
    }

    expect(eventsFetch).not.toHaveBeenCalled()
    // ...while the snapshot itself was refreshed (throttled, not per event).
    expect(snapshotFetch.mock.calls.length).toBeGreaterThanOrEqual(3)
    expect(snapshotFetch.mock.calls.length).toBeLessThanOrEqual(5)
    unsubscribe.forEach(unsub => unsub())
  })

  it('refreshes at a bounded rate under a sustained stream (at most one per interval)', () => {
    renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    // One event every 200 ms for a full minute.
    const minuteMs = 60_000
    act(() => {
      for (let t = 0, seq = 1; t < minuteMs; t += 200, seq++) {
        mockEventSource.simulateEvent('CoroutineCreated', eventPayload(seq))
        vi.advanceTimersByTime(200)
      }
    })

    const flushes = invalidationCount(queryClient, 'sessions')
    const maxFlushes = Math.ceil(minuteMs / LIVE_REFRESH_MIN_INTERVAL_MS) + 1
    // Bounded (the old ~1/s debounce flushed ~60 times a minute)...
    expect(flushes).toBeLessThanOrEqual(maxFlushes)
    // ...but never starved: the stream keeps refreshing throughout.
    expect(flushes).toBeGreaterThanOrEqual(maxFlushes - 2)
    // Every flush is paired across the three read models (CR-01).
    expect(invalidationCount(queryClient, 'thread-activity')).toBe(flushes)
    expect(invalidationCount(queryClient, 'session-metrics')).toBe(flushes)
  })

  it('a quiet stream costs nothing', () => {
    renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    act(() => {
      mockEventSource.onopen?.()
      vi.advanceTimersByTime(5 * 60_000)
    })
    expect(queryClient.invalidateQueries).not.toHaveBeenCalled()
  })
})

describe('useEventStream - bounded, batched event buffer (#137)', () => {
  let mockEventSource: MockEventSource
  let queryClient: QueryClient

  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mockEventSource = new MockEventSource()
    mockedApiClient.createEventSource.mockReturnValue(
      mockEventSource as unknown as EventSource,
    )
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('applies a batch frame in a single state update', () => {
    let renders = 0
    const { result } = renderHook(
      () => {
        renders++
        return useEventStream('session-1', true)
      },
      { wrapper: createWrapper(queryClient) },
    )
    const before = renders

    const batch = Array.from({ length: 50 }, (_, i) => JSON.parse(eventPayload(i + 1)))
    act(() => {
      mockEventSource.simulateEvent('batch', JSON.stringify(batch))
    })

    expect(result.current.events).toHaveLength(50)
    expect(renders - before).toBe(1)
  })

  it('caps buffered live events at the backend ring size, keeping the newest', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    const total = LIVE_EVENTS_MAX + 500
    const batch = Array.from({ length: total }, (_, i) => JSON.parse(eventPayload(i + 1)))
    act(() => {
      mockEventSource.simulateEvent('batch', JSON.stringify(batch))
    })

    expect(result.current.events).toHaveLength(LIVE_EVENTS_MAX)
    const last = result.current.events[result.current.events.length - 1] as unknown as {
      seq: number
    }
    expect(last.seq).toBe(total)
    // The received counter keeps counting past the cap (drives "N new events").
    expect(result.current.receivedCount).toBe(total)
  })

  it('reconnect() opens a fresh EventSource after the retry budget is spent', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)

    act(() => {
      result.current.reconnect()
    })

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(2)
  })
})

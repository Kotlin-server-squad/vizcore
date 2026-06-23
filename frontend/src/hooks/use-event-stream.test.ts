import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useEventStream } from './use-event-stream'

// Mock api-client
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    createEventSource: vi.fn(),
  },
}))

// Mock utils
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

  /** Helper to simulate SSE events in tests. */
  simulateEvent(type: string, data: string) {
    const handlers = this.listeners.get(type) || []
    handlers.forEach((h) => h({ data } as unknown as Event))
  }
}

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        gcTime: 0,
      },
    },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useEventStream', () => {
  let mockEventSource: MockEventSource

  beforeEach(() => {
    vi.clearAllMocks()
    mockEventSource = new MockEventSource()
    mockedApiClient.createEventSource.mockReturnValue(
      mockEventSource as unknown as EventSource,
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns disconnected state when sessionId is undefined', () => {
    const { result } = renderHook(() => useEventStream(undefined), {
      wrapper: createWrapper(),
    })

    expect(result.current.isConnected).toBe(false)
    expect(result.current.events).toEqual([])
    expect(result.current.error).toBeNull()
    expect(mockedApiClient.createEventSource).not.toHaveBeenCalled()
  })

  it('creates EventSource when sessionId provided', () => {
    renderHook(() => useEventStream('session-1'), {
      wrapper: createWrapper(),
    })

    expect(mockedApiClient.createEventSource).toHaveBeenCalledWith('session-1')
  })

  it('sets connected state on open', async () => {
    const { result } = renderHook(() => useEventStream('session-1'), {
      wrapper: createWrapper(),
    })

    act(() => {
      mockEventSource.onopen?.()
    })

    await waitFor(() => {
      expect(result.current.isConnected).toBe(true)
    })
    expect(result.current.error).toBeNull()
  })

  it('sets error state on connection error', async () => {
    const { result } = renderHook(() => useEventStream('session-1'), {
      wrapper: createWrapper(),
    })

    act(() => {
      mockEventSource.onerror?.()
    })

    await waitFor(() => {
      expect(result.current.isConnected).toBe(false)
      expect(result.current.error).toBe('Connection lost')
    })
  })

  it('closes EventSource on cleanup', () => {
    const { unmount } = renderHook(() => useEventStream('session-1'), {
      wrapper: createWrapper(),
    })

    unmount()

    expect(mockEventSource.close).toHaveBeenCalled()
  })

  it('clearEvents resets event list', async () => {
    const { result } = renderHook(() => useEventStream('session-1'), {
      wrapper: createWrapper(),
    })

    // Simulate an incoming event to populate the list
    act(() => {
      mockEventSource.simulateEvent(
        'CoroutineCreated',
        JSON.stringify({
          kind: 'CoroutineCreated',
          sessionId: 'session-1',
          seq: 1,
          tsNanos: 1000,
          coroutineId: 'c1',
          jobId: 'j1',
          parentCoroutineId: null,
          scopeId: 'scope-1',
          label: 'test',
        }),
      )
    })

    await waitFor(() => {
      expect(result.current.events.length).toBe(1)
    })

    // Clear events
    act(() => {
      result.current.clearEvents()
    })

    await waitFor(() => {
      expect(result.current.events).toEqual([])
    })
  })

  it('does not create EventSource when disabled', () => {
    const { result } = renderHook(() => useEventStream('session-1', false), {
      wrapper: createWrapper(),
    })

    expect(mockedApiClient.createEventSource).not.toHaveBeenCalled()
    expect(result.current.isConnected).toBe(false)
  })

  it('clears buffered events when sessionId changes (WR-16: no cross-session mixing)', async () => {
    // One MockEventSource per connection so each session gets its own stream.
    const perSessionSources: MockEventSource[] = []
    mockedApiClient.createEventSource.mockImplementation(() => {
      const source = new MockEventSource()
      perSessionSources.push(source)
      return source as unknown as EventSource
    })

    const eventFor = (sessionId: string, seq: number) =>
      JSON.stringify({
        kind: 'CoroutineCreated',
        sessionId,
        seq,
        tsNanos: 1000 + seq,
        coroutineId: `c${seq}`,
        jobId: `j${seq}`,
        parentCoroutineId: null,
        scopeId: 'scope-1',
        label: 'test',
      })

    const { result, rerender } = renderHook(
      ({ sessionId }: { sessionId: string }) => useEventStream(sessionId, true),
      { wrapper: createWrapper(), initialProps: { sessionId: 'session-A' } },
    )

    // Session A accumulates events
    act(() => {
      perSessionSources[0]!.simulateEvent('CoroutineCreated', eventFor('session-A', 1))
      perSessionSources[0]!.simulateEvent('CoroutineCreated', eventFor('session-A', 2))
    })
    await waitFor(() => expect(result.current.events.length).toBe(2))

    // Param-only navigation to session B (component instance reused)
    rerender({ sessionId: 'session-B' })

    // Session A's events must be gone immediately — not interleaved with B's
    await waitFor(() => expect(result.current.events).toEqual([]))

    // Session B's replay (seqs overlap with A's) is accepted fresh, exactly once
    act(() => {
      perSessionSources[1]!.simulateEvent('CoroutineCreated', eventFor('session-B', 1))
    })
    await waitFor(() => expect(result.current.events.length).toBe(1))
    expect((result.current.events[0] as { sessionId?: string }).sessionId).toBe('session-B')
  })
})

describe('useEventStream - replay gate (D-02/D-04)', () => {
  let mockEventSource: MockEventSource
  let queryClient: QueryClient

  const eventFor = (seq: number) =>
    JSON.stringify({
      kind: 'CoroutineCreated',
      sessionId: 'session-1',
      seq,
      tsNanos: 1000 + seq,
      coroutineId: `c${seq}`,
      jobId: `j${seq}`,
      parentCoroutineId: null,
      scopeId: 'scope-1',
      label: 'test',
    })

  function wrapper(qc: QueryClient) {
    return function Wrapper({ children }: { children: ReactNode }) {
      return createElement(QueryClientProvider, { client: qc }, children)
    }
  }

  function invalidationCount(qc: QueryClient, keyRoot: string): number {
    return vi
      .mocked(qc.invalidateQueries)
      .mock.calls.filter(([arg]) => {
        const key = (arg as { queryKey?: unknown[] } | undefined)?.queryKey
        return Array.isArray(key) && key[0] === keyRoot
      }).length
  }

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

  it('appends events but does NOT invalidate while replayActive is true', () => {
    const { result } = renderHook(
      () => useEventStream('session-1', true, true),
      { wrapper: wrapper(queryClient) },
    )

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', eventFor(1))
      mockEventSource.simulateEvent('CoroutineCreated', eventFor(2))
      vi.advanceTimersByTime(2000)
    })

    // Events still buffer (the "● N new events" badge counts these)...
    expect(result.current.events.length).toBe(2)
    // ...but no cache invalidation fires while replay is active (D-02).
    expect(invalidationCount(queryClient, 'sessions')).toBe(0)
    expect(invalidationCount(queryClient, 'thread-activity')).toBe(0)
  })

  it('flushes a single invalidation when replayActive flips back to false (D-04)', () => {
    const { rerender } = renderHook(
      ({ replayActive }: { replayActive: boolean }) =>
        useEventStream('session-1', true, replayActive),
      { wrapper: wrapper(queryClient), initialProps: { replayActive: true } },
    )

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', eventFor(1))
      mockEventSource.simulateEvent('CoroutineCreated', eventFor(2))
      vi.advanceTimersByTime(2000)
    })
    expect(invalidationCount(queryClient, 'sessions')).toBe(0)

    // Exit replay → buffered events apply via exactly one flush.
    act(() => {
      rerender({ replayActive: false })
      vi.advanceTimersByTime(2000)
    })

    expect(invalidationCount(queryClient, 'sessions')).toBe(1)
    expect(invalidationCount(queryClient, 'thread-activity')).toBe(1)
  })

  it('invalidates normally when replayActive is false (no regression)', () => {
    renderHook(() => useEventStream('session-1', true, false), {
      wrapper: wrapper(queryClient),
    })

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', eventFor(1))
      vi.advanceTimersByTime(2000)
    })

    expect(invalidationCount(queryClient, 'sessions')).toBe(1)
  })
})

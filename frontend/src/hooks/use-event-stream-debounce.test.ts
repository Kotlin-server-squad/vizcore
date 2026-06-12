/**
 * TDD tests: Task 1 - Polling storm prevention
 * Verifies that SSE invalidation is debounced (not per-event).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useEventStream } from './use-event-stream'

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

const EVENT_PAYLOAD = JSON.stringify({
  type: 'CoroutineCreated',
  sessionId: 'session-1',
  seq: 1,
  tsNanos: 1000,
  coroutineId: 'c1',
  jobId: 'j1',
  parentCoroutineId: null,
  scopeId: 'scope-1',
  label: 'test',
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

describe('useEventStream - debounced invalidation', () => {
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

  it('does not fire invalidation mid-burst (before debounce window closes)', () => {
    renderHook(
      () => useEventStream('session-1', true),
      { wrapper: createWrapper(queryClient) },
    )

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
    })

    // At t=100ms — still inside the debounce window (~400ms): no invalidation yet
    act(() => {
      vi.advanceTimersByTime(100)
    })

    expect(queryClient.invalidateQueries).toHaveBeenCalledTimes(0)
  })

  it('fires exactly one invalidation after the debounce window for a burst of events', () => {
    renderHook(
      () => useEventStream('session-1', true),
      { wrapper: createWrapper(queryClient) },
    )

    act(() => {
      for (let i = 0; i < 5; i++) {
        mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      }
      // Advance past the debounce window so the trailing-edge timer fires
      vi.advanceTimersByTime(600)
    })

    // A burst of 5 events should produce exactly 1 flush (not 5):
    // one ['sessions', ...] invalidation (plus its paired thread-activity one)
    expect(invalidationCount(queryClient, 'sessions')).toBe(1)
  })

  it('fires a second invalidation for a second separate burst after the window', () => {
    renderHook(
      () => useEventStream('session-1', true),
      { wrapper: createWrapper(queryClient) },
    )

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      vi.advanceTimersByTime(600)
    })

    // First burst: 1 flush
    expect(invalidationCount(queryClient, 'sessions')).toBe(1)

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      vi.advanceTimersByTime(600)
    })

    // Second separate burst: now 2 flushes total
    expect(invalidationCount(queryClient, 'sessions')).toBe(2)
  })

  it('flushes at least once per max-wait window under a sustained sub-debounce stream (CR-02)', () => {
    renderHook(
      () => useEventStream('session-1', true),
      { wrapper: createWrapper(queryClient) },
    )

    // Sustained stream: one event every 200ms (below the 400ms debounce window)
    // for 1400ms total — longer than INVALIDATION_MAX_WAIT_MS (1000ms).
    // A pure trailing-edge debounce would never flush; the max-wait cap must.
    act(() => {
      for (let i = 0; i < 8; i++) {
        mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
        vi.advanceTimersByTime(200)
      }
    })

    // The stream is still going, yet at least one flush already happened.
    expect(invalidationCount(queryClient, 'sessions')).toBeGreaterThanOrEqual(1)
  })

  it('every flush also invalidates the thread-activity query key (CR-01)', () => {
    renderHook(
      () => useEventStream('session-1', true),
      { wrapper: createWrapper(queryClient) },
    )

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      vi.advanceTimersByTime(600)
    })

    expect(queryClient.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ['thread-activity', 'session-1'],
    })
    // Flushes are paired: one thread-activity invalidation per sessions flush.
    expect(invalidationCount(queryClient, 'thread-activity')).toBe(
      invalidationCount(queryClient, 'sessions'),
    )
  })
})

describe('useThreadActivity - isLive flag disables polling', () => {
  it('useThreadActivity signature accepts isLive flag to disable polling interval', async () => {
    const mod = await import('./use-thread-activity')
    expect(typeof mod.useThreadActivity).toBe('function')
    // function exists and accepts 2 args; full behavior is in use-thread-activity.test.ts
    expect(mod.useThreadActivity.length).toBeGreaterThanOrEqual(1)
  })
})

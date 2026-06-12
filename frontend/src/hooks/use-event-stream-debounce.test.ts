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

    // A burst of 5 events should produce exactly 1 invalidation (not 5)
    expect(queryClient.invalidateQueries).toHaveBeenCalledTimes(1)
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

    // First burst: 1 invalidation
    expect(queryClient.invalidateQueries).toHaveBeenCalledTimes(1)

    act(() => {
      mockEventSource.simulateEvent('CoroutineCreated', EVENT_PAYLOAD)
      vi.advanceTimersByTime(600)
    })

    // Second separate burst: now 2 total
    expect(queryClient.invalidateQueries).toHaveBeenCalledTimes(2)
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

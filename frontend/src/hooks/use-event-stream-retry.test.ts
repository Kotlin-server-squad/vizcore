/**
 * TDD tests: 01-15 Task 2 — bounded exponential-backoff reconnect for fatal
 * EventSource errors + seq high-water-mark replay dedup.
 *
 * UAT gap 2: a non-200 SSE response is FATAL per the EventSource spec (no
 * native auto-reconnect), leaving the live view dead until a page reload.
 * The hook must retry fatal errors itself (bounded, exponential backoff) and,
 * because the backend replays FULL history on every new connection (REVIEW
 * WR-01), must not duplicate already-received events on reconnect.
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
  /**
   * EventSource.readyState — settable so tests can mark a source CLOSED.
   * Defaults to undefined: the hook must treat an unset readyState as a
   * TRANSIENT error (native auto-reconnect still alive), matching the
   * pre-existing contract in use-event-stream.test.ts.
   */
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

/** Build a backend-shaped event payload with a unique monotonic seq. */
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

describe('useEventStream - fatal-error retry with backoff and replay dedup', () => {
  let sources: MockEventSource[]
  let queryClient: QueryClient

  /** The most recently created EventSource (the live connection attempt). */
  const latest = () => sources[sources.length - 1]

  /** Simulate a FATAL EventSource failure: readyState CLOSED (2) + onerror. */
  const fatalError = (source: MockEventSource) => {
    source.readyState = 2
    source.onerror?.()
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

  it('retries after a fatal error: new EventSource created after the base delay', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)

    act(() => {
      fatalError(latest())
    })
    expect(result.current.error).toBe('Connection lost — retrying')

    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(2)
  })

  it('doubles the backoff, caps it at 8000ms, and stops after the retry budget', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)

    const delays = [1000, 2000, 4000, 8000, 8000]
    delays.forEach((delay, i) => {
      act(() => {
        fatalError(latest())
      })
      // Just before the boundary: no reconnect yet
      act(() => {
        vi.advanceTimersByTime(delay - 1)
      })
      expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(i + 1)
      // Crossing the boundary: exactly one new connection
      act(() => {
        vi.advanceTimersByTime(1)
      })
      expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(i + 2)
    })

    // The 5th retry's connection also fails — budget exhausted, no more attempts
    act(() => {
      fatalError(latest())
    })
    act(() => {
      vi.advanceTimersByTime(60_000)
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(6)
    expect(result.current.error).toBe('Connection lost')
  })

  it('a successful open resets the retry budget', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      fatalError(latest())
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(2)

    // The reconnect succeeds — budget restored
    act(() => {
      latest().onopen?.()
    })
    expect(result.current.isConnected).toBe(true)
    expect(result.current.error).toBeNull()

    // A later fatal error retries again from the BASE delay (budget was reset)
    act(() => {
      fatalError(latest())
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(3)
  })

  it('does not duplicate replayed history after reconnect (seq high-water mark)', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(1))
      latest().simulateEvent('CoroutineCreated', eventPayload(2))
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([1, 2])

    act(() => {
      fatalError(latest())
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(2)

    // The backend replays FULL history on the new connection (REVIEW WR-01),
    // then continues with new events.
    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(1))
      latest().simulateEvent('CoroutineCreated', eventPayload(2))
      latest().simulateEvent('CoroutineCreated', eventPayload(3))
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([1, 2, 3])
  })

  it('transient errors (readyState not CLOSED) do not create a second EventSource', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    // readyState left undefined — the browser's native auto-reconnect is alive
    act(() => {
      latest().onerror?.()
    })
    act(() => {
      vi.advanceTimersByTime(60_000)
    })

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBe('Connection lost')
    expect(result.current.isConnected).toBe(false)
  })

  it('unmount cancels a pending retry timer', () => {
    const { unmount } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      fatalError(latest())
    })
    unmount()
    act(() => {
      vi.advanceTimersByTime(60_000)
    })

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
  })
})

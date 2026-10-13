/**
 * TDD tests: 01-15 Task 2 — bounded exponential-backoff reconnect for fatal
 * EventSource errors + seq seen-set replay dedup (WR-13: membership dedup,
 * not a high-water mark, so out-of-order seqs are never dropped).
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
  const latest = (): MockEventSource => {
    const source = sources.at(-1)
    if (!source) throw new Error('no EventSource has been created yet')
    return source
  }

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

  it('a reconnect that delivers a frame resets the retry budget', () => {
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

    // The reconnect succeeds and the server sends a frame — budget restored
    act(() => {
      latest().onopen?.()
      latest().simulateEvent('heartbeat', '{"ts":1}')
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

  it('an open without any frame does not reset the retry budget', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    // Every attempt opens and then fails before the server sends anything.
    for (let attempt = 0; attempt < 10; attempt++) {
      act(() => {
        latest().onopen?.()
        fatalError(latest())
      })
      act(() => {
        vi.advanceTimersByTime(8000)
      })
    }

    // One initial connection plus the five budgeted retries, then it gives up.
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(6)
    expect(result.current.error).toBe('Connection lost')
  })

  it('a replay of already-seen history does not reset the retry budget', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    // The first connection delivers events 1..3 (new data: budget is full).
    act(() => {
      latest().onopen?.()
      for (let seq = 1; seq <= 3; seq++) latest().simulateEvent('CoroutineCreated', eventPayload(seq))
    })

    // Every later attempt replays the same history, then fails.
    for (let attempt = 0; attempt < 10; attempt++) {
      act(() => {
        fatalError(latest())
      })
      act(() => {
        vi.advanceTimersByTime(8000)
      })
      act(() => {
        latest().onopen?.()
        for (let seq = 1; seq <= 3; seq++) latest().simulateEvent('CoroutineCreated', eventPayload(seq))
      })
    }

    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(6)
    expect(result.current.error).toBe('Connection lost')
    expect(result.current.events).toHaveLength(3)
  })

  it('stops for good when the server says the session is gone', () => {
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    const source = latest()
    act(() => {
      source.onopen?.()
      source.simulateEvent('error', JSON.stringify({ error: 'Session not found' }))
      // The server then closes the stream; the browser would report that too.
      source.onerror?.()
    })

    expect(source.close).toHaveBeenCalled()
    expect(result.current.isConnected).toBe(false)
    expect(result.current.error).toBe('Session not found')
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['sessions', 'session-1'], exact: true })

    // No retry loop: nothing reconnects afterwards.
    act(() => {
      vi.advanceTimersByTime(5 * 60_000)
    })
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(1)
    expect(result.current.error).toBe('Session not found')
  })

  it('ignores callbacks from a connection that has been replaced', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })
    const first = latest()
    act(() => {
      fatalError(first)
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    act(() => {
      latest().onopen?.()
    })
    expect(result.current.isConnected).toBe(true)

    // A late error from the first, already-closed source must not mark the new
    // connection lost or schedule another retry, nor may a late server error frame.
    act(() => {
      first.onerror?.()
      first.simulateEvent('error', JSON.stringify({ error: 'stale' }))
    })
    act(() => {
      vi.advanceTimersByTime(60_000)
    })
    expect(result.current.isConnected).toBe(true)
    expect(result.current.error).toBeNull()
    expect(mockedApiClient.createEventSource).toHaveBeenCalledTimes(2)
  })

  it('does not duplicate replayed history after reconnect (seq seen-set dedup)', () => {
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

  it('does not drop legitimately out-of-order seqs (WR-13: seen-set, not watermark)', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    // seq is allocated at event construction but store-append happens later
    // without a common lock (backend WR-12), so seq 2 can reach the client
    // BEFORE seq 1 — on first connect, not just on reconnect. A high-water
    // mark would jump to 2 and silently drop seq 1 forever.
    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(2))
      latest().simulateEvent('CoroutineCreated', eventPayload(1))
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([2, 1])

    // True duplicates are still dropped...
    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(2))
      latest().simulateEvent('CoroutineCreated', eventPayload(1))
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([2, 1])

    // ...and the stream continues normally afterwards.
    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(3))
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([2, 1, 3])
  })

  it('still dedups an out-of-order interleaving across a reconnect replay', () => {
    const { result } = renderHook(() => useEventStream('session-1', true), {
      wrapper: createWrapper(queryClient),
    })

    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(3))
      latest().simulateEvent('CoroutineCreated', eventPayload(1))
    })

    act(() => {
      fatalError(latest())
    })
    act(() => {
      vi.advanceTimersByTime(1000)
    })

    // Replay arrives seq-ordered this time and includes the missed seq 2:
    // only 2 is new, 1 and 3 are membership-deduped.
    act(() => {
      latest().simulateEvent('CoroutineCreated', eventPayload(1))
      latest().simulateEvent('CoroutineCreated', eventPayload(2))
      latest().simulateEvent('CoroutineCreated', eventPayload(3))
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([3, 1, 2])
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

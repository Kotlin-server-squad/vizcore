import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { APPEND_FLUSH_MS, LIVE_EVENTS_MAX, LIVE_REFRESH_MIN_INTERVAL_MS, useEventStream } from './use-event-stream'

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

function createWrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

const eventFor = (seq: number) => ({
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

/**
 * Opening a session at the event cap replays its whole stored history, one SSE frame per event
 * (#268). Each frame is its own browser task, so an update per frame meant a list copy and a
 * render per event: 10,000 of them, which froze the tab on a slow CPU.
 */
describe('useEventStream - replay of a full session (#268)', () => {
  let source: MockEventSource

  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    source = new MockEventSource()
    mockedApiClient.createEventSource.mockReturnValue(source as unknown as EventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders about once per flush window during a replay, not once per frame', () => {
    let renders = 0
    const { result } = renderHook(
      () => {
        renders++
        return useEventStream('session-1')
      },
      { wrapper: createWrapper() },
    )
    act(() => source.onopen?.())
    const rendersBeforeReplay = renders

    // One act per frame, as each SSE frame is its own browser task, with time moving between
    // frames: an update per frame (even a deferred one) would render ~10,000 times here.
    for (let seq = 1; seq <= LIVE_EVENTS_MAX; seq++) {
      act(() => {
        source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(seq)))
        vi.advanceTimersByTime(1)
      })
    }
    act(() => {
      vi.advanceTimersByTime(APPEND_FLUSH_MS)
    })

    expect(result.current.events).toHaveLength(LIVE_EVENTS_MAX)
    expect(result.current.isConnected).toBe(true)
    // 10,000 ms of frames in 50 ms windows: ~200 flushes.
    expect(renders - rendersBeforeReplay).toBeLessThanOrEqual(LIVE_EVENTS_MAX / APPEND_FLUSH_MS + 10)
  })

  it('applies an event when its flush window ends, not before', () => {
    const { result } = renderHook(() => useEventStream('session-1'), { wrapper: createWrapper() })
    act(() => source.onopen?.())

    act(() => source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(1))))
    act(() => {
      vi.advanceTimersByTime(APPEND_FLUSH_MS - 1)
    })
    expect(result.current.events).toHaveLength(0)

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([1])
  })

  it('does not bring back events received before clearEvents', () => {
    const { result } = renderHook(() => useEventStream('session-1'), { wrapper: createWrapper() })
    act(() => source.onopen?.())

    act(() => source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(1))))
    act(() => result.current.clearEvents())
    act(() => {
      vi.advanceTimersByTime(APPEND_FLUSH_MS)
    })

    expect(result.current.events).toHaveLength(0)
    // It was received, so it still counts.
    expect(result.current.receivedCount).toBe(1)
  })

  it('still refreshes threads and metrics when the events are cleared before their flush', () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
    const { result } = renderHook(() => useEventStream('session-1'), { wrapper })
    act(() => source.onopen?.())

    act(() => source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(1))))
    act(() => result.current.clearEvents())
    act(() => {
      vi.advanceTimersByTime(LIVE_REFRESH_MIN_INTERVAL_MS)
    })

    const refreshed = invalidate.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey))
    expect(refreshed).toContain(JSON.stringify(['thread-activity', 'session-1']))
    expect(refreshed).toContain(JSON.stringify(['session-metrics', 'session-1']))
  })

  it('applies a full buffer at once instead of letting it grow while timers are throttled', () => {
    const { result } = renderHook(() => useEventStream('session-1'), { wrapper: createWrapper() })
    act(() => source.onopen?.())
    const total = LIVE_EVENTS_MAX + 500

    // A background tab: frames keep arriving, the flush timer does not fire.
    for (let seq = 1; seq <= total; seq++) {
      act(() => source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(seq))))
    }
    expect(result.current.events).toHaveLength(LIVE_EVENTS_MAX)

    act(() => {
      vi.advanceTimersByTime(APPEND_FLUSH_MS)
    })
    expect(result.current.events).toHaveLength(LIVE_EVENTS_MAX)
    expect((result.current.events.at(-1) as { seq?: number }).seq).toBe(total)
    expect(result.current.receivedCount).toBe(total)
  })

  it('still shows a single live event shortly after it arrives', () => {
    const { result } = renderHook(() => useEventStream('session-1'), { wrapper: createWrapper() })
    act(() => source.onopen?.())

    act(() => source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(1))))
    act(() => {
      vi.advanceTimersByTime(100)
    })

    expect(result.current.events.map((e) => (e as { seq?: number }).seq)).toEqual([1])
  })
})

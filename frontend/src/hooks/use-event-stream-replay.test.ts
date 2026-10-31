import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { LIVE_EVENTS_MAX, useEventStream } from './use-event-stream'

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

  it('renders a bounded number of times for a 10,000-event replay, not once per event', () => {
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

    // One act per frame: each SSE frame is a separate browser task.
    for (let seq = 1; seq <= LIVE_EVENTS_MAX; seq++) {
      act(() => source.simulateEvent('CoroutineCreated', JSON.stringify(eventFor(seq))))
    }
    act(() => {
      vi.advanceTimersByTime(1_000)
    })

    expect(result.current.events).toHaveLength(LIVE_EVENTS_MAX)
    expect(result.current.isConnected).toBe(true)
    expect(renders - rendersBeforeReplay).toBeLessThan(50)
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

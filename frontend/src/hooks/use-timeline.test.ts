import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useCoroutineTimeline, useTimelineStats, useTimelineVisualizationData } from './use-timeline'
import { apiClient } from '@/lib/api-client'
import type { CoroutineTimeline, TimelineEvent } from '@/types/api'
import { CoroutineState } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getCoroutineTimeline: vi.fn(),
  },
}))

const mockedApiClient = vi.mocked(apiClient)

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

function makeTimelineEvent(overrides: Partial<TimelineEvent> & { seq: number; kind: string }): TimelineEvent {
  return {
    tsNanos: 1000 + overrides.seq * 100,
    threadName: null,
    dispatcherName: null,
    reason: null,
    suspensionPoint: null,
    ...overrides,
    kind: overrides.kind as TimelineEvent['kind'],
  }
}

function makeTimeline(overrides: Partial<CoroutineTimeline> = {}): CoroutineTimeline {
  return {
    coroutineId: 'c1',
    name: 'test-coroutine',
    state: CoroutineState.ACTIVE,
    parentId: null,
    childrenIds: [],
    totalDuration: 1000,
    activeDuration: 600,
    suspendedDuration: 400,
    events: [],
    ...overrides,
  }
}

describe('useTimelineStats', () => {
  it('returns zero stats for undefined timeline', () => {
    const { result } = renderHook(() => useTimelineStats(undefined))

    expect(result.current).toEqual({
      totalDuration: 0,
      activeTime: 0,
      suspendedTime: 0,
      activePercent: 0,
      suspendedPercent: 0,
      suspensionCount: 0,
      dispatcherSwitches: 0,
      threadSwitches: 0,
      avgSuspensionDuration: 0,
    })
  })

  it('calculates correct percentages', () => {
    const timeline = makeTimeline({
      totalDuration: 1000,
      activeDuration: 600,
      suspendedDuration: 400,
      events: [
        makeTimelineEvent({ seq: 1, kind: 'coroutine.suspended' }),
        makeTimelineEvent({ seq: 2, kind: 'coroutine.suspended' }),
        makeTimelineEvent({ seq: 3, kind: 'DispatcherSelected' }),
        makeTimelineEvent({ seq: 4, kind: 'thread.assigned' }),
        makeTimelineEvent({ seq: 5, kind: 'thread.assigned' }),
      ],
    })

    const { result } = renderHook(() => useTimelineStats(timeline))

    expect(result.current.activePercent).toBe(60)
    expect(result.current.suspendedPercent).toBe(40)
    expect(result.current.suspensionCount).toBe(2)
    expect(result.current.dispatcherSwitches).toBe(1)
    expect(result.current.threadSwitches).toBe(2)
  })

  it('reports zero avg suspension duration (per-event durations are a deferred stub)', () => {
    // The source-only DTO carries no per-event duration (D-02), so
    // avgSuspensionDuration is always 0 — we count suspensions, never fabricate
    // durations.
    const timeline = makeTimeline({
      events: [
        makeTimelineEvent({ seq: 1, kind: 'coroutine.suspended' }),
        makeTimelineEvent({ seq: 2, kind: 'coroutine.suspended' }),
        makeTimelineEvent({ seq: 3, kind: 'coroutine.suspended' }),
      ],
    })

    const { result } = renderHook(() => useTimelineStats(timeline))

    expect(result.current.avgSuspensionDuration).toBe(0)
    expect(result.current.suspensionCount).toBe(3)
  })

  it('counts suspensions regardless of duration data', () => {
    const timeline = makeTimeline({
      events: [
        makeTimelineEvent({ seq: 1, kind: 'coroutine.suspended' }),
        makeTimelineEvent({ seq: 2, kind: 'coroutine.suspended' }),
      ],
    })

    const { result } = renderHook(() => useTimelineStats(timeline))

    expect(result.current.suspensionCount).toBe(2)
    // Durations are a deferred stub on the source-only timeline.
    expect(result.current.avgSuspensionDuration).toBe(0)
  })

  it('returns zero percentages when totalDuration is 0', () => {
    const timeline = makeTimeline({
      totalDuration: 0,
      activeDuration: 0,
      suspendedDuration: 0,
      events: [],
    })

    const { result } = renderHook(() => useTimelineStats(timeline))

    expect(result.current.activePercent).toBe(0)
    expect(result.current.suspendedPercent).toBe(0)
  })
})

describe('useTimelineVisualizationData', () => {
  it('returns empty array for undefined timeline', () => {
    const { result } = renderHook(() => useTimelineVisualizationData(undefined))

    expect(result.current).toEqual([])
  })

  it('returns empty array for timeline with no events', () => {
    const timeline = makeTimeline({ events: [] })

    const { result } = renderHook(() => useTimelineVisualizationData(timeline))

    expect(result.current).toEqual([])
  })

  it('formats events with relative time and state', () => {
    const timeline = makeTimeline({
      events: [
        makeTimelineEvent({ seq: 1, kind: 'coroutine.started', tsNanos: 1000 }),
        makeTimelineEvent({ seq: 2, kind: 'coroutine.suspended', tsNanos: 1500 }),
        makeTimelineEvent({ seq: 3, kind: 'coroutine.resumed', tsNanos: 1700 }),
        makeTimelineEvent({ seq: 4, kind: 'DispatcherSelected', tsNanos: 1800 }),
      ],
    })

    const { result } = renderHook(() => useTimelineVisualizationData(timeline))

    expect(result.current).toHaveLength(4)

    // First event: relative time is 0 (base), state is active
    expect(result.current[0]!.relativeTime).toBe(0)
    expect(result.current[0]!.kind).toBe('coroutine.started')
    expect(result.current[0]!.state).toBe('active')

    // Second event: suspended
    expect(result.current[1]!.relativeTime).toBe(500)
    expect(result.current[1]!.kind).toBe('coroutine.suspended')
    expect(result.current[1]!.state).toBe('suspended')
    // Per-event duration is a deferred stub on the source-only timeline (D-02).
    expect(result.current[1]!.duration).toBeUndefined()

    // Third event: resumed -> active
    expect(result.current[2]!.relativeTime).toBe(700)
    expect(result.current[2]!.state).toBe('active')

    // Fourth event: DispatcherSelected -> transition
    expect(result.current[3]!.relativeTime).toBe(800)
    expect(result.current[3]!.state).toBe('transition')
  })

  it('includes metadata with thread and dispatcher info', () => {
    const timeline = makeTimeline({
      events: [
        makeTimelineEvent({
          seq: 1,
          kind: 'coroutine.started',
          tsNanos: 1000,
          threadName: 'worker-1',
          dispatcherName: 'Default',
        }),
      ],
    })

    const { result } = renderHook(() => useTimelineVisualizationData(timeline))

    expect(result.current[0]!.metadata).toEqual({
      threadName: 'worker-1',
      dispatcherName: 'Default',
      suspensionPoint: null,
    })
  })
})

describe('useCoroutineTimeline', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches timeline data', async () => {
    const mockTimeline = makeTimeline({
      coroutineId: 'c1',
      events: [
        makeTimelineEvent({ seq: 1, kind: 'coroutine.started', tsNanos: 1000 }),
      ],
    })
    mockedApiClient.getCoroutineTimeline.mockResolvedValue(mockTimeline)

    const { result } = renderHook(
      () => useCoroutineTimeline('session-1', 'c1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toEqual(mockTimeline)
    expect(mockedApiClient.getCoroutineTimeline).toHaveBeenCalledWith('session-1', 'c1')
  })

  it('does not fetch when sessionId is undefined', () => {
    const { result } = renderHook(
      () => useCoroutineTimeline(undefined, 'c1'),
      { wrapper: createWrapper() }
    )

    expect(result.current.isFetching).toBe(false)
    expect(mockedApiClient.getCoroutineTimeline).not.toHaveBeenCalled()
  })

  it('does not fetch when coroutineId is undefined', () => {
    const { result } = renderHook(
      () => useCoroutineTimeline('session-1', undefined),
      { wrapper: createWrapper() }
    )

    expect(result.current.isFetching).toBe(false)
    expect(mockedApiClient.getCoroutineTimeline).not.toHaveBeenCalled()
  })
})

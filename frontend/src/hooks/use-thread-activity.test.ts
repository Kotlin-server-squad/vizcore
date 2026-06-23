import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import {
  useThreadActivity,
  useThreadLanesByDispatcher,
  useThreadUtilizationStats,
  useActiveCoroutinesPerThread,
} from './use-thread-activity'
import { apiClient } from '@/lib/api-client'
import { buildThreadLanes } from '@/lib/thread-lanes'
import type { ThreadActivity, ThreadEvent } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getThreadActivity: vi.fn(),
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

function evt(overrides: Partial<ThreadEvent> & Pick<ThreadEvent, 'coroutineId' | 'threadId' | 'timestamp' | 'eventType'>): ThreadEvent {
  return {
    threadName: `worker-${overrides.threadId}`,
    dispatcherName: null,
    ...overrides,
  }
}

/**
 * Canonical wire fixture — the EXACT shape the backend serializes for
 * GET /sessions/{id}/threads (Map<threadId, ThreadEvent[]>), mirroring
 * src/lib/thread-lanes.test.ts so expected derived values are computable
 * by hand:
 *   span = 5000 - 1000 = 4000
 *   thread 57 (Default): closed segment 1000..5000 -> utilization 1
 *   thread 80 (IO): open segment @2000 -> busy 3000 -> utilization 0.75
 */
function wireFixture(): ThreadActivity {
  return {
    '57': [
      evt({ coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
      evt({ coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 5000, eventType: 'RELEASED', dispatcherName: 'Default' }),
    ],
    '80': [
      evt({ coroutineId: 'c-2', threadId: 80, threadName: 'worker-2', timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'IO' }),
    ],
  }
}

describe('useThreadActivity', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches the wire-shape thread map and returns it untransformed', async () => {
    const fixture = wireFixture()
    mockedApiClient.getThreadActivity.mockResolvedValue(fixture)

    const { result } = renderHook(
      () => useThreadActivity('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toEqual(fixture)
    expect(mockedApiClient.getThreadActivity).toHaveBeenCalledWith('session-1')
  })

  it('does not fetch when sessionId is undefined', () => {
    const { result } = renderHook(
      () => useThreadActivity(undefined),
      { wrapper: createWrapper() }
    )

    expect(result.current.isFetching).toBe(false)
    expect(mockedApiClient.getThreadActivity).not.toHaveBeenCalled()
  })
})

describe('useThreadLanesByDispatcher', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('derives dispatcher-grouped lanes with exact values from the wire shape', async () => {
    mockedApiClient.getThreadActivity.mockResolvedValue(wireFixture())

    const { result } = renderHook(
      () => useThreadLanesByDispatcher('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // Exactly the two dispatcher groups from the fixture
    expect(Array.from(result.current.data.keys())).toEqual(['Default', 'IO'])
    expect(result.current.dispatcherInfo).toEqual([
      { id: 'Default', name: 'Default', threadIds: [57], queueDepth: null },
      { id: 'IO', name: 'IO', threadIds: [80], queueDepth: null },
    ])

    // Exact derived lane values (mirrors thread-lanes.test.ts fixture math)
    const defaultLanes = result.current.data.get('Default')!
    expect(defaultLanes).toEqual([
      {
        threadId: 57,
        threadName: 'worker-1',
        dispatcherId: 'Default',
        dispatcherName: 'Default',
        segments: [
          {
            coroutineId: 'c-1',
            coroutineName: null,
            startNanos: 1000,
            endNanos: 5000,
            state: 'ACTIVE',
          },
        ],
        utilization: 1,
      },
    ])

    const ioLanes = result.current.data.get('IO')!
    expect(ioLanes[0]!.threadId).toBe(80)
    expect(ioLanes[0]!.utilization).toBe(0.75)
    expect(ioLanes[0]!.segments).toEqual([
      {
        coroutineId: 'c-2',
        coroutineName: null,
        startNanos: 2000,
        endNanos: null,
        state: 'ACTIVE',
      },
    ])
  })
})

describe('live-mode polling interval (WR-15)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('all observers on the query key honor the live 5s fallback — the 2s poll never fires while live', async () => {
    mockedApiClient.getThreadActivity.mockResolvedValue(wireFixture())

    // Mount EVERY observer of ['thread-activity', sessionId] the Threads tab
    // creates while live: the direct hook (ThreadTimeline path) AND the lane
    // hooks (DispatcherOverview / active-coroutine paths). TanStack Query
    // refetches a key at the SMALLEST interval among observers, so a single
    // observer left at the legacy default (isLive=false -> 2s) would re-arm
    // the 2s poll and defeat the live-mode slow-poll design.
    renderHook(
      () => {
        useThreadActivity('session-live', true)
        useThreadLanesByDispatcher('session-live', true)
        useActiveCoroutinesPerThread('session-live', true)
      },
      { wrapper: createWrapper() },
    )

    // Initial fetch
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(mockedApiClient.getThreadActivity).toHaveBeenCalledTimes(1)

    // Two full legacy 2s windows pass without a refetch (no 2s observer wins)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4900)
    })
    expect(mockedApiClient.getThreadActivity).toHaveBeenCalledTimes(1)

    // The single effective interval is the 5s live fallback
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200)
    })
    expect(mockedApiClient.getThreadActivity).toHaveBeenCalledTimes(2)
  })

  it('keeps the legacy 2s poll when not live', async () => {
    mockedApiClient.getThreadActivity.mockResolvedValue(wireFixture())

    renderHook(
      () => {
        useThreadActivity('session-idle', false)
        useThreadLanesByDispatcher('session-idle', false)
      },
      { wrapper: createWrapper() },
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(mockedApiClient.getThreadActivity).toHaveBeenCalledTimes(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100)
    })
    expect(mockedApiClient.getThreadActivity).toHaveBeenCalledTimes(2)
  })
})

describe('useThreadUtilizationStats', () => {
  it('returns zeros for undefined activity', () => {
    const { result } = renderHook(() => useThreadUtilizationStats(undefined))

    expect(result.current).toEqual({
      avgUtilization: 0,
      maxUtilization: 0,
      minUtilization: 0,
      byDispatcher: new Map(),
    })
  })

  it('returns zeros for an empty wire map', () => {
    const activity = buildThreadLanes({})

    const { result } = renderHook(() => useThreadUtilizationStats(activity))

    expect(result.current).toEqual({
      avgUtilization: 0,
      maxUtilization: 0,
      minUtilization: 0,
      byDispatcher: new Map(),
    })
  })

  it('calculates exact min/max/avg utilization from a wire fixture', () => {
    // span = 8000 - 0 = 8000
    // t1 (Default): busy 2000 -> 0.25
    // t2 (Default): busy 4000 -> 0.5
    // t3 (IO):      busy 6000 -> 0.75
    const wire: ThreadActivity = {
      '1': [
        evt({ coroutineId: 'a', threadId: 1, timestamp: 0, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
        evt({ coroutineId: 'a', threadId: 1, timestamp: 2000, eventType: 'RELEASED', dispatcherName: 'Default' }),
      ],
      '2': [
        evt({ coroutineId: 'b', threadId: 2, timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
        evt({ coroutineId: 'b', threadId: 2, timestamp: 5000, eventType: 'RELEASED', dispatcherName: 'Default' }),
      ],
      '3': [
        evt({ coroutineId: 'c', threadId: 3, timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'IO' }),
        evt({ coroutineId: 'c', threadId: 3, timestamp: 8000, eventType: 'RELEASED', dispatcherName: 'IO' }),
      ],
    }
    const activity = buildThreadLanes(wire)

    const { result } = renderHook(() => useThreadUtilizationStats(activity))

    expect(result.current.minUtilization).toBe(0.25)
    expect(result.current.maxUtilization).toBe(0.75)
    // avg = (0.25 + 0.5 + 0.75) / 3 = 0.5 exactly
    expect(result.current.avgUtilization).toBe(0.5)
    // Default: (0.25 + 0.5) / 2 = 0.375 exactly; IO: 0.75
    expect(result.current.byDispatcher.get('Default')).toBe(0.375)
    expect(result.current.byDispatcher.get('IO')).toBe(0.75)
    expect(result.current.byDispatcher.size).toBe(2)
  })

  it('handles a single thread derived from the wire shape', () => {
    // span = 4000; busy = 5000 - 2000 = 3000 -> utilization 0.75
    const wire: ThreadActivity = {
      '9': [
        evt({ coroutineId: 'solo', threadId: 9, timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
        evt({ coroutineId: 'other', threadId: 9, timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
        evt({ coroutineId: 'solo', threadId: 9, timestamp: 5000, eventType: 'RELEASED', dispatcherName: 'Default' }),
      ],
    }
    // Use only the closed 'other'-free portion: rely on exact derivation.
    // busy = (5000-1000) + (5000-2000 open->max) = 4000 + 3000 = 7000 capped at span -> 1
    const activity = buildThreadLanes(wire)

    const { result } = renderHook(() => useThreadUtilizationStats(activity))

    expect(result.current.avgUtilization).toBe(1)
    expect(result.current.minUtilization).toBe(1)
    expect(result.current.maxUtilization).toBe(1)
  })
})

describe('useActiveCoroutinesPerThread', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reports only coroutines with open segments (ASSIGNED without RELEASED)', async () => {
    const wire: ThreadActivity = {
      '5': [
        evt({ coroutineId: 'c-open', threadId: 5, timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
      ],
      '6': [
        evt({ coroutineId: 'c-closed', threadId: 6, timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'IO' }),
        evt({ coroutineId: 'c-closed', threadId: 6, timestamp: 2000, eventType: 'RELEASED', dispatcherName: 'IO' }),
      ],
    }
    mockedApiClient.getThreadActivity.mockResolvedValue(wire)

    const { result } = renderHook(
      () => useActiveCoroutinesPerThread('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => expect(result.current.size).toBe(1))

    // Exactly the open segment's coroutine under its threadId — nothing else
    expect(result.current.get(5)).toEqual(['c-open'])
    expect(result.current.has(6)).toBe(false)
  })
})

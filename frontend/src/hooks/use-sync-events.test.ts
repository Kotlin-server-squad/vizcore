import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import type { VizEvent } from '@/types/api'

vi.mock('@/hooks/use-sessions', () => ({
  useSessionEvents: vi.fn(),
}))

import { useSyncEvents } from './use-sync-events'
import { useSessionEvents } from '@/hooks/use-sessions'

const mockedUseSessionEvents = vi.mocked(useSessionEvents)

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

function makeEvent(kind: string, seq: number, overrides: Record<string, unknown> = {}): VizEvent {
  return {
    sessionId: 's1',
    seq,
    tsNanos: seq * 1_000_000,
    kind: kind as VizEvent['kind'],
    ...overrides,
  } as unknown as VizEvent
}

describe('useSyncEvents', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads the events it is given, not the stored history, when given some', () => {
    mockedUseSessionEvents.mockReturnValue({ data: [] } as unknown as ReturnType<typeof useSessionEvents>)
    const live = [makeEvent('MutexCreated', 1, { mutexId: 'm1', mutexLabel: 'L', ownerCoroutineId: null })]

    const { result } = renderHook(() => useSyncEvents('s1', live), { wrapper: createWrapper() })

    expect(result.current.mutexes.map(m => m.mutexId)).toEqual(['m1'])
    // Still subscribed to the history, so the page's request profile is unchanged (#124).
    expect(mockedUseSessionEvents).toHaveBeenCalledWith('s1')
  })

  it('returns empty result when no events', () => {
    mockedUseSessionEvents.mockReturnValue({
      data: [],
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    expect(result.current.syncEvents).toEqual([])
    expect(result.current.mutexes).toEqual([])
    expect(result.current.semaphores).toEqual([])
    expect(result.current.deadlocks).toEqual([])
    expect(result.current.warnings).toEqual([])
    expect(result.current.hasDeadlock).toBe(false)
  })

  it('tracks mutex creation', () => {
    const events = [
      makeEvent('MutexCreated', 1, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        ownerCoroutineId: null,
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    expect(result.current.mutexes).toHaveLength(1)
    expect(result.current.mutexes[0]!).toMatchObject({
      mutexId: 'm1',
      label: 'testMutex',
      isLocked: false,
      currentHolder: null,
      lockAcquisitions: 0,
    })
  })

  it('tracks mutex lock/unlock cycle', () => {
    const events = [
      makeEvent('MutexCreated', 1, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        ownerCoroutineId: null,
      }),
      makeEvent('MutexLockAcquired', 2, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        acquirerId: 'c1',
        acquirerLabel: 'Worker-1',
        waitDurationNanos: 0,
      }),
      makeEvent('MutexUnlocked', 3, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        releaserId: 'c1',
        releaserLabel: 'Worker-1',
        nextWaiterId: null,
        holdDurationNanos: 5000,
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    expect(result.current.mutexes).toHaveLength(1)
    const mutex = result.current.mutexes[0]!
    // After unlock, the mutex should be unlocked
    expect(mutex.isLocked).toBe(false)
    expect(mutex.currentHolder).toBeNull()
    expect(mutex.lockAcquisitions).toBe(1)
  })

  it('tracks mutex contention', () => {
    const events = [
      makeEvent('MutexCreated', 1, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        ownerCoroutineId: null,
      }),
      makeEvent('MutexLockAcquired', 2, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        acquirerId: 'c1',
        acquirerLabel: 'Worker-1',
        waitDurationNanos: 0,
      }),
      makeEvent('MutexLockRequested', 3, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        requesterId: 'c2',
        requesterLabel: 'Worker-2',
        isLocked: true,
        queuePosition: 1,
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    const mutex = result.current.mutexes[0]!
    expect(mutex.contentionCount).toBe(1)
    expect(mutex.isLocked).toBe(true)
    expect(mutex.currentHolder).toBe('c1')
  })

  it('tracks mutex wait queue', () => {
    const events = [
      makeEvent('MutexCreated', 1, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        ownerCoroutineId: null,
      }),
      makeEvent('MutexQueueChanged', 2, {
        mutexId: 'm1',
        mutexLabel: 'testMutex',
        waitingCoroutineIds: ['c2', 'c3'],
        waitingLabels: ['Worker-2', 'Worker-3'],
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    const mutex = result.current.mutexes[0]!
    expect(mutex.waitQueue).toHaveLength(2)
    expect(mutex.waitQueue[0]!).toEqual({ id: 'c2', label: 'Worker-2' })
    expect(mutex.waitQueue[1]!).toEqual({ id: 'c3', label: 'Worker-3' })
  })

  it('tracks semaphore creation and permits', () => {
    const events = [
      makeEvent('SemaphoreCreated', 1, {
        semaphoreId: 'sem1',
        semaphoreLabel: 'Pool',
        totalPermits: 5,
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    expect(result.current.semaphores).toHaveLength(1)
    expect(result.current.semaphores[0]!).toMatchObject({
      semaphoreId: 'sem1',
      label: 'Pool',
      totalPermits: 5,
      availablePermits: 5,
      activeHolders: [],
      waitQueue: [],
    })
  })

  it('updates semaphore state', () => {
    const events = [
      makeEvent('SemaphoreCreated', 1, {
        semaphoreId: 'sem1',
        semaphoreLabel: 'Pool',
        totalPermits: 5,
      }),
      makeEvent('SemaphoreStateChanged', 2, {
        semaphoreId: 'sem1',
        semaphoreLabel: 'Pool',
        availablePermits: 3,
        totalPermits: 5,
        activeHolders: ['c1', 'c2'],
        activeHolderLabels: ['Worker-1', 'Worker-2'],
        waitingCoroutines: ['c3'],
        waitingLabels: ['Worker-3'],
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    const semaphore = result.current.semaphores[0]!
    expect(semaphore.availablePermits).toBe(3)
    expect(semaphore.totalPermits).toBe(5)
    expect(semaphore.activeHolders).toEqual([
      { id: 'c1', label: 'Worker-1', permits: 1 },
      { id: 'c2', label: 'Worker-2', permits: 1 },
    ])
    expect(semaphore.waitQueue).toEqual([{ id: 'c3', label: 'Worker-3' }])
  })

  it('detects deadlocks', () => {
    const events = [
      makeEvent('DeadlockDetected', 1, {
        involvedCoroutines: ['c1', 'c2'],
        involvedCoroutineLabels: ['Worker-A', 'Worker-B'],
        involvedMutexes: ['mx-1', 'mx-2'],
        involvedMutexLabels: ['Lock-1', 'Lock-2'],
        waitGraph: { c1: 'mx-2', c2: 'mx-1' },
        holdGraph: { 'mx-1': 'c1', 'mx-2': 'c2' },
        cycleDescription:
          'c1 holds mx-1, waits for mx-2; c2 holds mx-2, waits for mx-1',
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    expect(result.current.deadlocks).toHaveLength(1)
    expect(result.current.hasDeadlock).toBe(true)
    expect(result.current.deadlocks[0]!).toMatchObject({
      kind: 'DeadlockDetected',
      involvedCoroutines: ['c1', 'c2'],
      cycleDescription:
        'c1 holds mx-1, waits for mx-2; c2 holds mx-2, waits for mx-1',
    })
  })

  it('tracks potential deadlock warnings', () => {
    const events = [
      makeEvent('PotentialDeadlockWarning', 1, {
        coroutineId: 'c1',
        coroutineLabel: 'Worker-A',
        holdingMutex: 'mx-1',
        holdingMutexLabel: 'Lock-1',
        requestingMutex: 'mx-2',
        requestingMutexLabel: 'Lock-2',
        recommendation: 'Consider using a consistent lock ordering',
      }),
    ]

    mockedUseSessionEvents.mockReturnValue({
      data: events,
    } as unknown as ReturnType<typeof useSessionEvents>)

    const { result } = renderHook(() => useSyncEvents('s1'), {
      wrapper: createWrapper(),
    })

    expect(result.current.warnings).toHaveLength(1)
    expect(result.current.hasDeadlock).toBe(false)
    expect(result.current.warnings[0]!).toMatchObject({
      kind: 'PotentialDeadlockWarning',
      coroutineId: 'c1',
      holdingMutex: 'mx-1',
      requestingMutex: 'mx-2',
      recommendation: 'Consider using a consistent lock ordering',
    })
  })
})

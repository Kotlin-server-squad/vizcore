import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useEventCategories } from './use-event-categories'
import { apiClient } from '@/lib/api-client'
import type { VizEvent } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getSessionEvents: vi.fn(),
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

function makeEvent(kind: string, seq: number): VizEvent {
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: Date.now() * 1_000_000,
    kind: kind as VizEvent['kind'],
    coroutineId: 'c1',
    jobId: 'j1',
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: null,
  }
}

describe('useEventCategories', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('returns all false (except validation) when no events', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasValidation).toBe(true)
    })

    expect(result.current.hasChannels).toBe(false)
    expect(result.current.hasFlowOps).toBe(false)
    expect(result.current.hasSyncPrimitives).toBe(false)
    expect(result.current.hasJobs).toBe(false)
    expect(result.current.hasValidation).toBe(true)
  })

  it('detects channel events', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('ChannelCreated', 1),
      makeEvent('ChannelSendStarted', 2),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasChannels).toBe(true)
    })

    expect(result.current.hasChannels).toBe(true)
    expect(result.current.hasFlowOps).toBe(false)
    expect(result.current.hasSyncPrimitives).toBe(false)
    expect(result.current.hasJobs).toBe(false)
  })

  it('detects flow events', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('FlowCreated', 1),
      makeEvent('FlowValueEmitted', 2),
      makeEvent('FlowOperatorApplied', 3),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasFlowOps).toBe(true)
    })

    expect(result.current.hasChannels).toBe(false)
    expect(result.current.hasFlowOps).toBe(true)
    expect(result.current.hasSyncPrimitives).toBe(false)
    expect(result.current.hasJobs).toBe(false)
  })

  it('detects sync primitive events', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('MutexCreated', 1),
      makeEvent('MutexLockAcquired', 2),
      makeEvent('SemaphoreCreated', 3),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasSyncPrimitives).toBe(true)
    })

    expect(result.current.hasChannels).toBe(false)
    expect(result.current.hasFlowOps).toBe(false)
    expect(result.current.hasSyncPrimitives).toBe(true)
    expect(result.current.hasJobs).toBe(false)
  })

  it('detects job events', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('JobStateChanged', 1),
      makeEvent('JobJoinRequested', 2),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasJobs).toBe(true)
    })

    expect(result.current.hasChannels).toBe(false)
    expect(result.current.hasFlowOps).toBe(false)
    expect(result.current.hasSyncPrimitives).toBe(false)
    expect(result.current.hasJobs).toBe(true)
  })

  it('detects multiple categories in a mixed event stream', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('coroutine.created', 1),
      makeEvent('ChannelCreated', 2),
      makeEvent('FlowCreated', 3),
      makeEvent('MutexCreated', 4),
      makeEvent('JobStateChanged', 5),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasChannels).toBe(true)
    })

    expect(result.current.hasChannels).toBe(true)
    expect(result.current.hasFlowOps).toBe(true)
    expect(result.current.hasSyncPrimitives).toBe(true)
    expect(result.current.hasJobs).toBe(true)
    expect(result.current.hasValidation).toBe(true)
  })

  it('always returns hasValidation as true', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('coroutine.created', 1),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasValidation).toBe(true)
    })

    expect(result.current.hasValidation).toBe(true)
  })

  it('detects deadlock events as sync primitives', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('DeadlockDetected', 1),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasSyncPrimitives).toBe(true)
    })

    expect(result.current.hasSyncPrimitives).toBe(true)
  })

  it('detects WaitingForChildren as a job event', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([
      makeEvent('WaitingForChildren', 1),
    ])

    const { result } = renderHook(
      () => useEventCategories('session-1'),
      { wrapper: createWrapper() }
    )

    await waitFor(() => {
      expect(result.current.hasJobs).toBe(true)
    })

    expect(result.current.hasJobs).toBe(true)
  })

  it('also scans live-streamed events, so a wrapper used after page load is noticed (#145)', async () => {
    mockedApiClient.getSessionEvents.mockResolvedValue([makeEvent('CoroutineCreated', 1)])

    const live = [makeEvent('MutexLockRequested', 2)]
    const { result } = renderHook(() => useEventCategories('session-1', live), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.hasSyncPrimitives).toBe(true))
  })
})

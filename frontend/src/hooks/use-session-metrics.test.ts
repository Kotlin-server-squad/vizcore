import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { createElement } from 'react'
import { useSessionMetrics } from './use-session-metrics'
import { apiClient } from '@/lib/api-client'
import type { MetricsResponse } from '@/types/api'

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getMetrics: vi.fn(),
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

function metricsFixture(): MetricsResponse {
  return {
    active: 3,
    peak: 7,
    throughputPerSec: 12.5,
    dispatcherUtilization: { Default: 2, IO: 1 },
    leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }],
    leakThresholdMs: 30_000,
  }
}

describe('useSessionMetrics', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches the MetricsResponse wire shape for a session', async () => {
    const fixture = metricsFixture()
    mockedApiClient.getMetrics.mockResolvedValue(fixture)

    const { result } = renderHook(() => useSessionMetrics('session-1'), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toEqual(fixture)
    expect(mockedApiClient.getMetrics).toHaveBeenCalledWith('session-1')
  })

  it('does not fetch when sessionId is undefined', () => {
    const { result } = renderHook(() => useSessionMetrics(undefined), {
      wrapper: createWrapper(),
    })

    expect(result.current.isFetching).toBe(false)
    expect(mockedApiClient.getMetrics).not.toHaveBeenCalled()
  })

  it('does not fetch when enabled is false (read-only shared view, T-08-08)', () => {
    const { result } = renderHook(
      () => useSessionMetrics('session-1', false, false),
      { wrapper: createWrapper() },
    )

    expect(result.current.isFetching).toBe(false)
    expect(mockedApiClient.getMetrics).not.toHaveBeenCalled()
  })
})

describe('useSessionMetrics polling cadence', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls on the live 5s interval when isLive is true', async () => {
    mockedApiClient.getMetrics.mockResolvedValue(metricsFixture())

    renderHook(() => useSessionMetrics('session-live', true), {
      wrapper: createWrapper(),
    })

    // Initial fetch
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(mockedApiClient.getMetrics).toHaveBeenCalledTimes(1)

    // Just under 5s: no live-mode refetch yet (the 2s non-live poll must NOT fire)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4900)
    })
    expect(mockedApiClient.getMetrics).toHaveBeenCalledTimes(1)

    // Crossing 5s: the live fallback poll fires
    await act(async () => {
      await vi.advanceTimersByTimeAsync(200)
    })
    expect(mockedApiClient.getMetrics).toHaveBeenCalledTimes(2)
  })

  it('polls on the 2s interval when not live', async () => {
    mockedApiClient.getMetrics.mockResolvedValue(metricsFixture())

    renderHook(() => useSessionMetrics('session-poll', false), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(mockedApiClient.getMetrics).toHaveBeenCalledTimes(1)

    // The non-live 2s poll fires
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100)
    })
    expect(mockedApiClient.getMetrics).toHaveBeenCalledTimes(2)
  })
})

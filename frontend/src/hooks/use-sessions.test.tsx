import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useSessions, useCreateSession, useDeleteSession, SESSIONS_REFRESH_MS } from './use-sessions'
import { apiClient } from '@/lib/api-client'

// Mock the api client module
vi.mock('@/lib/api-client', () => ({
  apiClient: {
    listSessions: vi.fn(),
    getSession: vi.fn(),
    getSessionEvents: vi.fn(),
    createSession: vi.fn(),
    deleteSession: vi.fn(),
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
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

describe('useSessions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns sessions data', async () => {
    const sessions = [
      { sessionId: 'session-1', coroutineCount: 5 },
      { sessionId: 'session-2', coroutineCount: 12 },
    ]
    mockedApiClient.listSessions.mockResolvedValue(sessions)

    const { result } = renderHook(() => useSessions(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toEqual(sessions)
    expect(mockedApiClient.listSessions).toHaveBeenCalledOnce()
  })

  it('handles error when listing sessions fails', async () => {
    mockedApiClient.listSessions.mockRejectedValue(new Error('Network error'))

    const { result } = renderHook(() => useSessions(), {
      wrapper: createWrapper(),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error?.message).toBe('Network error')
  })

  it('re-reads the list when the window gets the focus back, so a new app appears without a reload', async () => {
    mockedApiClient.listSessions.mockResolvedValueOnce([])
    mockedApiClient.listSessions.mockResolvedValue([{ sessionId: 'my-app-1', coroutineCount: 1 }])
    // The app's own client turns focus refetching off by default; this list opts back in.
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0, refetchOnWindowFocus: false } },
    })
    const { result } = renderHook(() => useSessions(), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      ),
    })
    await waitFor(() => expect(result.current.data).toEqual([]))

    act(() => {
      focusManager.setFocused(false)
      focusManager.setFocused(true)
    })

    await waitFor(() => expect(result.current.data).toEqual([{ sessionId: 'my-app-1', coroutineCount: 1 }]))
    focusManager.setFocused(undefined)
  })

  it('re-reads the list on a slow interval', async () => {
    vi.useFakeTimers()
    try {
      mockedApiClient.listSessions.mockResolvedValue([])
      renderHook(() => useSessions(), { wrapper: createWrapper() })
      await vi.advanceTimersByTimeAsync(0)
      expect(mockedApiClient.listSessions).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(SESSIONS_REFRESH_MS - 1000)
      expect(mockedApiClient.listSessions).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(1000)
      expect(mockedApiClient.listSessions).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('useCreateSession', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('creates session and invalidates cache', async () => {
    const response = { sessionId: 'session-new', message: 'Created' }
    mockedApiClient.createSession.mockResolvedValue(response)
    mockedApiClient.listSessions.mockResolvedValue([])

    const { result } = renderHook(() => useCreateSession(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      result.current.mutate({ name: 'My Session', demoId: 'nested' })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(mockedApiClient.createSession).toHaveBeenCalledWith('My Session', 'nested')
    expect(result.current.data).toEqual(response)
  })

  it('creates session without name', async () => {
    const response = { sessionId: 'session-new', message: 'Created' }
    mockedApiClient.createSession.mockResolvedValue(response)

    const { result } = renderHook(() => useCreateSession(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      result.current.mutate({})
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(mockedApiClient.createSession).toHaveBeenCalledWith(undefined, undefined)
  })
})

describe('useDeleteSession', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('deletes session and invalidates cache', async () => {
    const response = { message: 'Deleted' }
    mockedApiClient.deleteSession.mockResolvedValue(response)
    mockedApiClient.listSessions.mockResolvedValue([])

    const { result } = renderHook(() => useDeleteSession(), {
      wrapper: createWrapper(),
    })

    await act(async () => {
      result.current.mutate('session-1')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(mockedApiClient.deleteSession).toHaveBeenCalledWith('session-1')
    expect(result.current.data).toEqual(response)
  })
})

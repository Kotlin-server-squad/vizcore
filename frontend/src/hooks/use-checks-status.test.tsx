import { describe, it, expect, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useChecksStatus, checksStatusKey } from './use-checks-status'
import { apiClient } from '@/lib/api-client'

vi.mock('@/lib/api-client', () => ({ apiClient: { getChecksStatus: vi.fn() } }))

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return { client, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> }
}

describe('useChecksStatus (T-8)', () => {
  it('reads the last run once, and not when disabled', async () => {
    vi.mocked(apiClient.getChecksStatus).mockResolvedValue({ sessionId: 's', lastRun: null, newEventsSince: null })
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useChecksStatus('s', true), { wrapper })
    await waitFor(() => expect(result.current.data?.sessionId).toBe('s'))
    expect(client.getQueryData(checksStatusKey('s'))).toBeDefined()
    renderHook(() => useChecksStatus('t', false), { wrapper })
    expect(apiClient.getChecksStatus).toHaveBeenCalledTimes(1)
  })
})

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { DispatcherOverview } from './DispatcherOverview'
import type { ThreadActivity } from '@/types/api'

const getThreadActivity = vi.fn()
vi.mock('@/lib/api-client', () => ({ apiClient: { getThreadActivity: (...a: unknown[]) => getThreadActivity(...a) } }))

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>
)

describe('DispatcherOverview at a moment (Part 8, M-7)', () => {
  it('reads the activity it is given, fetches nothing, and counts the threads it observed', () => {
    const activity: ThreadActivity = {
      '1': [{ threadId: 1, threadName: 'w-1', coroutineId: 'a', timestamp: 1, eventType: 'ASSIGNED', dispatcherName: 'Dispatchers.IO' }],
      '2': [{ threadId: 2, threadName: 'w-2', coroutineId: 'b', timestamp: 2, eventType: 'ASSIGNED', dispatcherName: 'Dispatchers.IO' }],
    }
    render(<DispatcherOverview sessionId="s" activity={activity} />, { wrapper })
    expect(screen.getAllByText('Dispatchers.IO').length).toBeGreaterThan(0)
    expect(screen.getByText('Threads observed')).toBeInTheDocument()
    expect(screen.queryByText('Thread Pool Size')).toBeNull()
    expect(getThreadActivity).not.toHaveBeenCalled()
  })
})

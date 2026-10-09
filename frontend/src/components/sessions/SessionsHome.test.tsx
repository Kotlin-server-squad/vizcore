import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import type { SessionInfo } from '@/types/api'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  Link: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}))

const useSessionsMock = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({
  useSessions: () => useSessionsMock(),
}))

import { SessionsHome } from './SessionsHome'

const liveSession: SessionInfo = { sessionId: 'order-service-1', coroutineCount: 3 }

beforeEach(() => {
  vi.clearAllMocks()
  useSessionsMock.mockReturnValue({ data: [liveSession], isLoading: false })
})

describe('SessionsHome', () => {
  it('does not carry the sidebar width constraint', () => {
    const { container } = render(<SessionsHome onConnect={vi.fn()} />)
    expect(container.querySelector('.w-\\[320px\\]')).toBeNull()
  })

  it('renders the same badged list as the sidebar', () => {
    const { getByText } = render(<SessionsHome onConnect={vi.fn()} />)
    expect(getByText('order-service-1')).toBeInTheDocument()
  })
})

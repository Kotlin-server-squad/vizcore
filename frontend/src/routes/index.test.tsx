import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { SessionInfo } from '@/types/api'

// Router is mocked so the route component renders without a real router context.
// `Link` must tolerate a render-prop child — Layout's nav uses that form.
const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  createFileRoute: () => (opts: unknown) => opts,
  Link: ({ children, to }: { children: unknown; to?: string }) => (
    <a href={to}>
      {typeof children === 'function'
        ? (children as (s: { isActive: boolean }) => React.ReactNode)({ isActive: false })
        : (children as React.ReactNode)}
    </a>
  ),
}))

const useSessionsMock = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({
  useSessions: () => useSessionsMock(),
}))

// The wizard is a heavy, separately-tested surface; stub it so this test stays
// about the home route's own content.
vi.mock('@/components/connect/ConnectWizard', () => ({
  ConnectWizard: () => null,
}))
vi.mock('@/components/sessions/NewDemoSessionModal', () => ({
  NewDemoSessionModal: () => null,
}))
vi.mock('@/components/sessions/CompareOverlay', () => ({
  CompareOverlay: () => null,
}))

import { HomePage } from './index'

const liveSession: SessionInfo = { sessionId: 'order-service-1', coroutineCount: 3 }

beforeEach(() => {
  vi.clearAllMocks()
  useSessionsMock.mockReturnValue({ data: [liveSession], isLoading: false })
})

describe('root route', () => {
  it('renders the sessions home at the root, not a marketing hero', () => {
    render(<HomePage />)
    expect(screen.getByRole('heading', { name: /sessions/i })).toBeInTheDocument()
    expect(
      screen.queryByText(/Real-time visualization of Kotlin coroutine/i)
    ).not.toBeInTheDocument()
  })

  it('lists the connected sessions', () => {
    render(<HomePage />)
    expect(screen.getByText('order-service-1')).toBeInTheDocument()
  })

  it('offers the re-hosted demo and compare actions alongside Connect', () => {
    render(<HomePage />)
    expect(screen.getByRole('button', { name: /new demo session/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^compare$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /connect/i })).toBeInTheDocument()
  })
})

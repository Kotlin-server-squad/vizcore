import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { SessionInfo } from '@/types/api'
import { deriveSessionKind } from '@/lib/session-kind'
import { SessionsSidebar } from './SessionsSidebar'

// Router navigate is mocked so the "Run a demo scenario instead" CTA is assertable
// without a real router context.
const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  Link: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}))

// useSessions is mocked per-test so the sidebar renders a controlled list.
const useSessionsMock = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({
  useSessions: () => useSessionsMock(),
}))

const liveSession: SessionInfo = { sessionId: 'order-service-1', coroutineCount: 3 }
const demoSession: SessionInfo = { sessionId: 'scenario-Nested Coroutines', coroutineCount: 7 }

beforeEach(() => {
  vi.clearAllMocks()
})

describe('deriveSessionKind', () => {
  it('classifies a scenario-prefixed session as demo', () => {
    expect(deriveSessionKind({ sessionId: 'scenario-Foo', coroutineCount: 1 })).toBe('demo')
  })

  it('classifies a non-scenario session as live', () => {
    expect(deriveSessionKind({ sessionId: 'order-service-7', coroutineCount: 1 })).toBe('live')
  })

  it('defaults unknown/ambiguous sessions to live', () => {
    expect(deriveSessionKind({ sessionId: '', coroutineCount: 0 })).toBe('live')
  })
})

describe('SessionsSidebar', () => {
  it('renders a LIVE pill for a live session and a DEMO chip for a scenario session', () => {
    useSessionsMock.mockReturnValue({ data: [liveSession, demoSession], isLoading: false })
    render(<SessionsSidebar onConnect={vi.fn()} />)

    // The live session carries the LIVE pill (reused LivePill → "LIVE" + poll sub-label).
    expect(screen.getByText('LIVE')).toBeInTheDocument()
    expect(screen.getByText('~150ms poll')).toBeInTheDocument()
    // The scenario session carries the neutral DEMO chip.
    expect(screen.getByText('DEMO')).toBeInTheDocument()
  })

  it('renders both group headers when both kinds exist', () => {
    useSessionsMock.mockReturnValue({ data: [liveSession, demoSession], isLoading: false })
    render(<SessionsSidebar onConnect={vi.fn()} />)

    expect(screen.getByText('Live apps')).toBeInTheDocument()
    expect(screen.getByText('Demo scenarios')).toBeInTheDocument()
  })

  it('fires onConnect when the + Connect button is clicked', () => {
    const onConnect = vi.fn()
    useSessionsMock.mockReturnValue({ data: [liveSession], isLoading: false })
    render(<SessionsSidebar onConnect={onConnect} />)

    fireEvent.click(screen.getByRole('button', { name: /connect/i }))
    expect(onConnect).toHaveBeenCalledTimes(1)
  })

  it('renders the inline "No app connected" empty state with both CTAs when the list is empty', () => {
    const onConnect = vi.fn()
    useSessionsMock.mockReturnValue({ data: [], isLoading: false })
    render(<SessionsSidebar onConnect={onConnect} />)

    expect(screen.getByText('No app connected')).toBeInTheDocument()

    // Primary CTA folds into the list and fires onConnect.
    const connectYourApp = screen.getByRole('button', { name: 'Connect your app' })
    fireEvent.click(connectYourApp)
    expect(onConnect).toHaveBeenCalledTimes(1)

    // Ghost CTA navigates to the scenarios route.
    const runDemo = screen.getByRole('button', { name: 'Run a demo scenario instead' })
    fireEvent.click(runDemo)
    expect(navigate).toHaveBeenCalledWith({ to: '/scenarios' })
  })
})

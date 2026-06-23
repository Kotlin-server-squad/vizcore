import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createRootRoute,
  createRoute,
  createRouter,
  createMemoryHistory,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { SharedSessionPage } from './shared.$token'
import type { SharedSessionResult } from '@/types/share'
import type { SessionSnapshot } from '@/types/api'

// SessionDetails is mocked to a thin probe so the route test focuses on the
// shell behaviour (status branching, no-chrome, read-only wiring) rather than
// re-testing the full viewer (covered in SessionDetails.test.tsx).
vi.mock('@/components/SessionDetails', () => ({
  SessionDetails: ({ sessionId, readOnly }: { sessionId: string; readOnly?: boolean }) => (
    <div data-testid="session-details" data-session-id={sessionId} data-read-only={String(!!readOnly)}>
      SessionDetails
    </div>
  ),
}))

// Layout/Navbar must NOT appear in the shared shell — render a sentinel if it
// is ever imported so the no-chrome assertion is meaningful.
vi.mock('@/components/Layout', () => ({
  Layout: ({ children }: { children: ReactNode }) => (
    <div data-testid="app-layout">{children}</div>
  ),
}))

const getSharedSession = vi.fn<(token: string) => Promise<SharedSessionResult>>()

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    getSharedSession: (token: string) => getSharedSession(token),
  },
}))

function makeSession(): SessionSnapshot {
  return {
    sessionId: 'shared-session-1',
    coroutineCount: 0,
    eventCount: 0,
    coroutines: [],
  } as SessionSnapshot
}

function renderSharedAt(token = 'tok-123') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const sharedRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/shared/$token',
    component: SharedSessionPage,
  })
  const routeTree = rootRoute.addChildren([sharedRoute])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/shared/${token}`] }),
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
  return router
}

beforeEach(() => {
  getSharedSession.mockReset()
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('/shared/$token route', () => {
  it('renders SessionDetails in read-only mode for a valid token', async () => {
    getSharedSession.mockResolvedValue({
      status: 'ok',
      data: { session: makeSession(), events: [] },
    })

    renderSharedAt('tok-123')

    const details = await screen.findByTestId('session-details')
    expect(details).toHaveAttribute('data-read-only', 'true')
    expect(details).toHaveAttribute('data-session-id', 'shared-session-1')
    // The fetch used the route token as the credential.
    expect(getSharedSession).toHaveBeenCalledWith('tok-123')
  })

  it('shows the "Read-only shared view" banner for a valid token', async () => {
    getSharedSession.mockResolvedValue({
      status: 'ok',
      data: { session: makeSession(), events: [] },
    })

    renderSharedAt()

    expect(await screen.findByText('Read-only shared view')).toBeInTheDocument()
  })

  it('has NO app nav chrome (no Layout) for a valid token', async () => {
    getSharedSession.mockResolvedValue({
      status: 'ok',
      data: { session: makeSession(), events: [] },
    })

    renderSharedAt()

    await screen.findByTestId('session-details')
    expect(screen.queryByTestId('app-layout')).not.toBeInTheDocument()
  })

  it('shows the "no longer available" empty state on 410 (expired)', async () => {
    getSharedSession.mockResolvedValue({ status: 'expired' })

    renderSharedAt()

    expect(await screen.findByText('This link is no longer available')).toBeInTheDocument()
    expect(
      screen.getByText('The share link may have expired or been revoked.'),
    ).toBeInTheDocument()
    expect(screen.queryByTestId('session-details')).not.toBeInTheDocument()
  })

  it('shows the "no longer available" empty state on 404 (not-found/revoked)', async () => {
    getSharedSession.mockResolvedValue({ status: 'not-found' })

    renderSharedAt()

    expect(await screen.findByText('This link is no longer available')).toBeInTheDocument()
    expect(
      screen.getByText('The share link may have expired or been revoked.'),
    ).toBeInTheDocument()
  })

  it('shows the "Too many requests" copy on 429 (rate-limited)', async () => {
    getSharedSession.mockResolvedValue({ status: 'rate-limited' })

    renderSharedAt()

    expect(await screen.findByText('Too many requests')).toBeInTheDocument()
    expect(
      screen.getByText(
        'This shared link is receiving a lot of traffic. Try again in a minute.',
      ),
    ).toBeInTheDocument()
  })
})

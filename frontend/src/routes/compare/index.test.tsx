import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  createRootRoute,
  createRoute,
  createRouter,
  createMemoryHistory,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import { ComparePage, validateSearch } from './index'
import { Layout } from '@/components/Layout'

// Mock the data hooks so the route renders deterministically without a network.
vi.mock('@/hooks/use-sessions', async () => {
  const actual = await vi.importActual<typeof import('@/hooks/use-sessions')>(
    '@/hooks/use-sessions',
  )
  return {
    ...actual,
    useSessions: vi.fn(),
    useSession: vi.fn(),
  }
})
vi.mock('@/hooks/use-comparison', () => ({
  useComparison: vi.fn(),
}))

import { useSessions, useSession } from '@/hooks/use-sessions'
import { useComparison } from '@/hooks/use-comparison'

const mockedUseSessions = vi.mocked(useSessions)
const mockedUseSession = vi.mocked(useSession)
const mockedUseComparison = vi.mocked(useComparison)

const sessions = [
  { sessionId: 'session-a', coroutineCount: 5 },
  { sessionId: 'session-b', coroutineCount: 8 },
  { sessionId: 'session-c', coroutineCount: 3 },
]

function emptyQuery() {
  return { data: undefined, isLoading: false, isError: false, error: null } as never
}

/**
 * Builds a standalone router mounting the real ComparePage + validateSearch
 * under a fresh root, seeded at `initialPath`. Returns the router.
 */
function buildRouter(
  initialPath: string,
  rootComponent: () => React.ReactNode = () => <Outlet />,
) {
  const rootRoute = createRootRoute({ component: rootComponent })
  const compareRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/compare',
    validateSearch,
    component: ComparePage,
  })
  const routeTree = rootRoute.addChildren([compareRoute])
  return createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  })
}

function renderCompareAt(initialPath: string) {
  const router = buildRouter(initialPath)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
  return router
}

describe('/compare route', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedUseSessions.mockReturnValue({
      data: sessions,
      isLoading: false,
    } as never)
    mockedUseSession.mockReturnValue(emptyQuery())
    mockedUseComparison.mockReturnValue(emptyQuery())
  })

  it('seeds both pickers from ?a= and ?b= search params', async () => {
    renderCompareAt('/compare?a=session-a&b=session-b')

    // The "Compare Sessions" page heading renders.
    expect(await screen.findByText('Compare Sessions')).toBeInTheDocument()

    // Seeding flows through to the comparison query: it is called with the two
    // search-param ids, proving both pickers are seeded (shareable URL, D-10).
    await waitFor(() => {
      expect(mockedUseComparison).toHaveBeenCalledWith('session-a', 'session-b')
    })
  })

  it('writes the chosen id into the ?a= search param when Session A changes', async () => {
    const user = userEvent.setup()
    const router = renderCompareAt('/compare')

    await screen.findByText('Compare Sessions')

    // Open Session A picker and choose session-c.
    await user.click(screen.getByTestId('select-session-a'))
    await user.click(await screen.findByText('session-c (3 coroutines)'))

    await waitFor(() => {
      const search = router.state.location.search as { a?: string }
      expect(search.a).toBe('session-c')
    })
  })

  it('shows the Session not found surface for an unknown id (D-12)', async () => {
    mockedUseComparison.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('HTTP 404'),
    } as never)

    renderCompareAt('/compare?a=ghost&b=session-b')

    expect(await screen.findByTestId('comparison-not-found')).toBeInTheDocument()
    expect(screen.getByText('Session not found')).toBeInTheDocument()
    // Results tables are NOT rendered.
    expect(screen.queryByTestId('comparison-summary')).not.toBeInTheDocument()
  })
})

describe('Layout Compare nav', () => {
  it('renders a Compare nav item linking to /compare', async () => {
    mockedUseSessions.mockReturnValue({ data: sessions, isLoading: false } as never)
    mockedUseSession.mockReturnValue(emptyQuery())
    mockedUseComparison.mockReturnValue(emptyQuery())

    const router = buildRouter('/compare', () => (
      <Layout>
        <Outlet />
      </Layout>
    ))
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    })

    render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router as never} />
      </QueryClientProvider>,
    )

    await screen.findByText('Compare Sessions')
    // The navbar Compare link points at /compare.
    const compareLinks = screen
      .getAllByRole('link')
      .filter((el) => el.getAttribute('href') === '/compare')
    expect(compareLinks.length).toBeGreaterThanOrEqual(1)
    expect(compareLinks[0]).toHaveTextContent('Compare')
  })
})

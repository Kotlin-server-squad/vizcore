import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
import { HomePage } from './index'

// Layout is mocked to a thin passthrough so the home-render assertions focus on
// the page content (heading / links) rather than the nav chrome.
vi.mock('@/components/Layout', () => ({
  Layout: ({ children }: { children: ReactNode }) => <div data-testid="app-layout">{children}</div>,
}))

// The two api-client calls the index route touches:
//  - listSessions: feeds the existing "Recent Sessions" home block (no-param path).
//  - resolveCorrelation: the Phase 9 poll the ?correlation= deep-link reuses.
const listSessions = vi.fn<() => Promise<unknown[]>>()
const resolveCorrelation = vi.fn<(correlation: string) => Promise<{ sessionId: string } | null>>()

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    listSessions: () => listSessions(),
    resolveCorrelation: (correlation: string) => resolveCorrelation(correlation),
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  listSessions.mockResolvedValue([])
})

afterEach(() => {
  vi.clearAllMocks()
})

/**
 * Mounts the real HomePage under a standalone memory router with a stub
 * `/sessions/$sessionId` route so the deep-link auto-navigate has somewhere to
 * land. `useSearch({ strict: false })` lets HomePage mount outside the generated
 * route tree (the CMPR-02 test idiom). The initial path carries the optional
 * `?correlation=` param under test.
 */
function renderHomeAt(initialPath = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: HomePage,
  })
  const sessionRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/sessions/$sessionId',
    component: () => <div>Session page</div>,
  })
  const sessionsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/sessions',
    component: () => <div>Sessions list</div>,
  })
  const scenariosRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/scenarios',
    component: () => <div>Scenarios</div>,
  })
  const routeTree = rootRoute.addChildren([
    homeRoute,
    sessionRoute,
    sessionsRoute,
    scenariosRoute,
  ])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
  return router
}

describe('/ index route', () => {
  it('Test 1 (deep-link): auto-navigates exactly once to the resolved live session', async () => {
    resolveCorrelation.mockResolvedValue({ sessionId: 'sess-1' })

    const router = renderHomeAt('/?correlation=corr-xyz')

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/sessions/sess-1'),
    )
    expect(resolveCorrelation).toHaveBeenCalledWith('corr-xyz')
  })

  it('Test 2 (no param): renders the home page and never polls or navigates', async () => {
    const router = renderHomeAt('/')

    expect(await screen.findByRole('heading', { name: 'Coroutine Visualizer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View Sessions' })).toBeInTheDocument()

    // Give any stray effect a tick; the home path must not touch resolveCorrelation
    // and must stay on '/'.
    await Promise.resolve()
    expect(resolveCorrelation).not.toHaveBeenCalled()
    expect(router.state.location.pathname).toBe('/')
  })

  it('Test 3 (poll-until-resolved): navigates only after the first non-null result, once', async () => {
    // First poll → not bound yet (404 → null), then bound → { sessionId }.
    resolveCorrelation
      .mockResolvedValueOnce(null)
      .mockResolvedValue({ sessionId: 'sess-2' })

    const router = renderHomeAt('/?correlation=corr-abc')

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/sessions/sess-2'),
    )

    // The one-shot guard means we land exactly once on the resolved id even
    // though the poll keeps firing.
    expect(router.state.location.pathname).toBe('/sessions/sess-2')
    expect(resolveCorrelation.mock.calls.length).toBeGreaterThanOrEqual(2)
  })
})

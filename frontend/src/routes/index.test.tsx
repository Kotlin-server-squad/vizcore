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
import { Home } from './index'

// Layout is mocked to a thin passthrough so the home-render assertions focus on
// the page content (heading / links) rather than the nav chrome.
vi.mock('@/components/Layout', () => ({
  Layout: ({ children }: { children: ReactNode }) => <div data-testid="app-layout">{children}</div>,
}))

// listSessions feeds the "Recent Sessions" home block.
const listSessions = vi.fn<() => Promise<unknown[]>>()

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    listSessions: () => listSessions(),
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  listSessions.mockResolvedValue([])
})

afterEach(() => {
  vi.clearAllMocks()
})

/** Mounts the real Home page under a standalone memory router. */
function renderHomeAt(initialPath = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: Home,
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
  it('renders the home page', async () => {
    renderHomeAt('/')

    expect(await screen.findByRole('heading', { name: 'Coroutine Visualizer' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View Sessions' })).toBeInTheDocument()
  })
})

// NOTE: the `?correlation=` deep-link tests were removed with the deep-link
// itself. Its only producer was the Phase 13 JCEF tool window / system-browser
// fallback, which the native plugin redesign deleted; the native plugin resolves
// correlation server-side via /api/sessions/resolve and never opens a browser.
// `apiClient.resolveCorrelation` is still used — by ConnectWizard.

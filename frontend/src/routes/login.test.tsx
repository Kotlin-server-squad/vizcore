import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  createRootRoute,
  createRoute,
  createRouter,
  createMemoryHistory,
  Outlet,
  RouterProvider,
} from '@tanstack/react-router'
import { LoginPage } from './login'
import { getToken, clearToken } from '@/lib/auth-store'

const mockFetch = vi.fn()

beforeEach(() => {
  mockFetch.mockReset()
  vi.stubGlobal('fetch', mockFetch)
  clearToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
  clearToken()
})

function jsonResponse(data: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
  }
}

/**
 * Mounts the real LoginPage under a standalone memory router with a home route
 * so a successful sign-in's navigate has somewhere to land.
 */
function renderLoginAt(initialPath = '/login') {
  const rootRoute = createRootRoute({ component: () => <Outlet /> })
  const loginRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/login',
    component: LoginPage,
  })
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <div>Home page</div>,
  })
  const sessionRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/sessions/$id',
    component: () => <div>Session page</div>,
  })
  const routeTree = rootRoute.addChildren([loginRoute, homeRoute, sessionRoute])
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
  })
  render(<RouterProvider router={router as never} />)
  return router
}

describe('/login route', () => {
  it('posts valid creds to /api/auth/token, stores the token, and navigates home', async () => {
    const user = userEvent.setup()
    mockFetch.mockResolvedValue(jsonResponse({ token: 'jwt-ok', expiresAt: '2099-01-01T00:00:00Z' }))

    const router = renderLoginAt('/login')
    await screen.findByRole('heading', { name: 'Sign in' })

    await user.type(screen.getByLabelText('Username'), 'alice')
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => {
      const [url, init] = mockFetch.mock.calls[0]!
      expect(url).toBe('/api/auth/token')
      expect(init!.method).toBe('POST')
      expect(JSON.parse(init!.body as string)).toEqual({ username: 'alice', password: 'secret' })
    })

    await waitFor(() => expect(getToken()).toBe('jwt-ok'))
    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
  })

  it('navigates back to the ?redirect= route on success (D-05 resume)', async () => {
    const user = userEvent.setup()
    mockFetch.mockResolvedValue(jsonResponse({ token: 'jwt-ok', expiresAt: '2099-01-01T00:00:00Z' }))

    const router = renderLoginAt('/login?redirect=%2Fsessions%2Fabc')
    await screen.findByRole('heading', { name: 'Sign in' })

    await user.type(screen.getByLabelText('Username'), 'alice')
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/sessions/abc'))
  })

  it('shows the wrong-credentials copy on 401 and stores no token', async () => {
    const user = userEvent.setup()
    mockFetch.mockResolvedValue(jsonResponse({ error: 'Invalid credentials' }, 401))

    renderLoginAt('/login')
    await screen.findByRole('heading', { name: 'Sign in' })

    await user.type(screen.getByLabelText('Username'), 'alice')
    await user.type(screen.getByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Incorrect username or password.')).toBeInTheDocument()
    expect(getToken()).toBeNull()
  })

  it('shows the network/server copy on a 500 / fetch rejection', async () => {
    const user = userEvent.setup()
    mockFetch.mockRejectedValue(new TypeError('Failed to fetch'))

    renderLoginAt('/login')
    await screen.findByRole('heading', { name: 'Sign in' })

    await user.type(screen.getByLabelText('Username'), 'alice')
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(
      await screen.findByText('Could not reach the server. Check your connection and try again.'),
    ).toBeInTheDocument()
    expect(getToken()).toBeNull()
  })

  it('shows the "Signing in…" loading label while the request is in flight', async () => {
    const user = userEvent.setup()
    let resolveFetch: (v: unknown) => void = () => {}
    mockFetch.mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve
      }),
    )

    renderLoginAt('/login')
    await screen.findByRole('heading', { name: 'Sign in' })

    await user.type(screen.getByLabelText('Username'), 'alice')
    await user.type(screen.getByLabelText('Password'), 'secret')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Signing in…')).toBeInTheDocument()

    // Resolve so the test cleans up without a dangling promise.
    resolveFetch(jsonResponse({ token: 'jwt', expiresAt: '2099-01-01T00:00:00Z' }))
  })

  it('validates non-empty fields before posting (V5 client-side guard)', async () => {
    const user = userEvent.setup()

    renderLoginAt('/login')
    await screen.findByRole('heading', { name: 'Sign in' })

    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Username is required')).toBeInTheDocument()
    expect(mockFetch).not.toHaveBeenCalled()
  })
})

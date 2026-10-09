import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { routeTree } from '@/routeTree.gen'
import { apiClient } from '@/lib/api-client'

/**
 * First-run CTAs, through the REAL router and route tree (#139).
 *
 * "Run a demo", Reset and the builder's Cancel all used to navigate to
 * /scenarios, whose beforeLoad redirects straight back to "/" — so the button
 * appeared to do nothing. Mocked-router unit tests could not see that; these
 * render the generated route tree over a memory history with the real
 * api-client against a stubbed `fetch`, and assert where the user ends up.
 */

// Purely visual and heavy in jsdom; neither issues requests.
vi.mock('@/components/CoroutineTreeGraph', () => ({
  CoroutineTreeGraph: () => <div data-testid="coroutine-tree-graph" />,
}))
vi.mock('@/components/export/ExportMenu', () => ({ ExportMenu: () => null }))

const SCENARIOS = {
  scenarios: [
    { id: 'nested', name: 'Nested Coroutines', description: 'd', endpoint: '/x', category: 'basic' },
  ],
}

type Handler = (url: string, init?: RequestInit) => { status: number; body?: unknown } | undefined

let override: Handler | undefined
let createdCount = 0

function serve(url: string, init?: RequestInit) {
  const method = (init?.method ?? 'GET').toUpperCase()
  const path = url.replace(/^\/api/, '').split('?')[0]!
  const custom = override?.(url, init)
  if (custom) return custom
  if (path === '/sessions' && method === 'GET') return { status: 200, body: [] }
  if (path === '/sessions' && method === 'POST') {
    createdCount += 1
    return { status: 200, body: { sessionId: `scenario-fresh-${createdCount}`, message: 'ok' } }
  }
  if (path === '/scenarios') return { status: 200, body: SCENARIOS }
  if (path.startsWith('/sessions/') && method === 'DELETE') return { status: 200, body: { message: 'deleted' } }
  if (/^\/sessions\/[^/]+$/.test(path)) {
    const id = decodeURIComponent(path.split('/')[2]!)
    return { status: 200, body: { sessionId: id, coroutineCount: 0, eventCount: 0, coroutines: [] } }
  }
  if (path.endsWith('/events')) return { status: 200, body: [] }
  if (path.endsWith('/metrics')) {
    return {
      status: 200,
      body: { active: 0, peak: 0, throughputPerSec: 0, dispatcherUtilization: {}, leaks: [], leakThresholdMs: 30000 },
    }
  }
  return { status: 200, body: {} }
}

const fetchMock = vi.fn((url: string, init?: RequestInit) => {
  const { status, body } = serve(url, init)
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    statusText: '',
    headers: new Headers(),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body ?? '')),
  })
})

class NoopEventSource {
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  readyState = 0
  addEventListener() {}
  close() {}
}

function renderAt(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
    context: { queryClient },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>,
  )
  return router
}

beforeEach(() => {
  override = undefined
  createdCount = 0
  fetchMock.mockClear()
  apiClient.clearRateLimits()
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('EventSource', NoopEventSource)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('first-run CTAs go somewhere useful (#139)', () => {
  it.each(['/', '/sessions'])(
    '%s: "Run a demo scenario instead" opens the demo picker, and Start lands in the new session',
    async path => {
      const router = renderAt(path)

      fireEvent.click(await screen.findByRole('button', { name: 'Run a demo scenario instead' }))

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText('New demo session')).toBeInTheDocument()
      expect(router.state.location.pathname).toBe(path)

      fireEvent.click(await within(dialog).findByRole('button', { name: /start/i }))

      await waitFor(() =>
        expect(router.state.location.pathname).toBe('/sessions/scenario-fresh-1'),
      )
      expect(router.state.location.search).toMatchObject({
        scenarioId: 'nested',
        scenarioName: 'Nested Coroutines',
      })
    },
  )

  it('a failed demo start is shown in the picker instead of failing silently', async () => {
    override = (url, init) =>
      url.startsWith('/api/sessions?') && init?.method === 'POST'
        ? { status: 500, body: { error: 'session store unavailable' } }
        : undefined
    const router = renderAt('/')

    fireEvent.click(await screen.findByRole('button', { name: 'Run a demo scenario instead' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(await within(dialog).findByRole('button', { name: /start/i }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      /could not start .*nested coroutines.*session store unavailable/i,
    )
    expect(router.state.location.pathname).toBe('/')
  })

  it('the scenario builder\'s Cancel returns to the sessions list', async () => {
    const router = renderAt('/scenarios/builder')

    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(await screen.findByRole('heading', { name: 'Sessions' })).toBeInTheDocument()
  })

  it('Reset deletes the session and opens a fresh one for the same scenario', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    const router = renderAt('/sessions/scenario-old?scenarioId=nested&scenarioName=Nested%20Coroutines')

    fireEvent.click(await screen.findByRole('button', { name: /reset/i }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/sessions/scenario-fresh-1'),
    )
    const calls = fetchMock.mock.calls.map(([u, init]) => `${init?.method ?? 'GET'} ${u}`)
    expect(calls).toContain('DELETE /api/sessions/scenario-old')
    expect(calls).toContain('POST /api/sessions?name=scenario-Nested%20Coroutines')
  })

  it('a failed Reset delete is shown and keeps the user on the session', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    override = (_url, init) =>
      init?.method === 'DELETE' ? { status: 503, body: { error: 'try later' } } : undefined
    const router = renderAt('/sessions/scenario-old?scenarioId=nested&scenarioName=Nested')

    fireEvent.click(await screen.findByRole('button', { name: /reset/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not delete this session/i)
    expect(router.state.location.pathname).toBe('/sessions/scenario-old')
  })

  it('a failed scenario run is shown', async () => {
    override = (url, init) =>
      url.startsWith('/api/scenarios/') && init?.method === 'POST'
        ? { status: 500, body: { error: 'scenario crashed' } }
        : undefined
    renderAt('/sessions/scenario-old?scenarioId=nested&scenarioName=Nested')

    fireEvent.click(await screen.findByRole('button', { name: /run scenario/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not run the scenario.*scenario crashed/i)
  })
})

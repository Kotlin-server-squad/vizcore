import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { routeTree } from '@/routeTree.gen'
import { LOOK_STORAGE_KEY, forgetVisitLook, useLook } from '@/looks/look-context'
import { forgetExperimentConfig } from '@/looks/experiment/api'
import { forgetLoadVisits } from '@/looks/experiment/LookExperimentLayer'
import { forgetQueue, pendingEvents } from '@/looks/experiment/queue'
import { forgetExperimentState, readExperimentState, updateExperimentState } from '@/looks/experiment/storage'

vi.mock('@/components/SessionWorkspace', () => ({
  SessionWorkspace: () => {
    const { look } = useLook()
    return <div data-testid="workspace">{look}</div>
  },
}))
vi.mock('@/components/Layout', () => ({
  Layout: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

let fetchMock: ReturnType<typeof vi.fn>
let answerConfig: (body: unknown, status?: number) => void

/** The config request waits until the test answers it. */
function holdConfig() {
  fetchMock.mockImplementation((url: string) => {
    if (url === '/api/experiments/looks/config') {
      return new Promise<Response>(resolve => {
        answerConfig = (body, status = 200) => resolve(new Response(JSON.stringify(body), { status }))
      })
    }
    return Promise.resolve(new Response(null, { status: 204 }))
  })
}

const configCalls = () => fetchMock.mock.calls.filter(([url]) => url === '/api/experiments/looks/config')

function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
    context: { queryClient },
  })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>
  )
  return router
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
  forgetExperimentConfig()
  forgetExperimentState()
  forgetQueue()
  forgetLoadVisits()
  delete document.documentElement.dataset.look
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  holdConfig()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const ON = { enabled: true, weights: { classic: 0, a: 0, b: 1, c: 0 }, surveyUrl: null }

describe('the look experiment on the session page', () => {
  it('assigns a new browser a look by the weights, keeps it, and records the entry', async () => {
    renderAt('/sessions/s-1')
    expect(await screen.findByTestId('workspace')).toHaveTextContent('classic')
    await act(async () => answerConfig(ON))
    await waitFor(() => expect(screen.getByTestId('workspace')).toHaveTextContent('b'))
    expect(readExperimentState()).toMatchObject({ entry: { look: 'b', kind: 'assigned' }, assigned: 'b' })
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBeNull()
    expect(pendingEvents()).toEqual(expect.arrayContaining([{ type: 'entry', look: 'b', kind: 'assigned' }]))
  })

  it('a browser that picked a look keeps it and joins as chose', async () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'a')
    renderAt('/sessions/s-1')
    await screen.findByTestId('workspace')
    await act(async () => answerConfig(ON))
    await waitFor(() => expect(readExperimentState().entry).toEqual({ look: 'a', kind: 'chose' }))
    expect(screen.getByTestId('workspace')).toHaveTextContent('a')
  })

  it('a ?look= link wins and joins as chose', async () => {
    renderAt('/sessions/s-1?look=c')
    await screen.findByTestId('workspace')
    await act(async () => answerConfig(ON))
    await waitFor(() => expect(readExperimentState().entry).toEqual({ look: 'c', kind: 'chose' }))
    expect(screen.getByTestId('workspace')).toHaveTextContent('c')
  })

  it('while off, a stored assignment never shows: the page stays Classic and nothing is recorded', async () => {
    updateExperimentState(s => ({ ...s, entry: { look: 'b', kind: 'assigned' }, assigned: 'b' }))
    renderAt('/sessions/s-1')
    expect(await screen.findByTestId('workspace')).toHaveTextContent('classic')
    expect(document.documentElement.dataset.look).toBe('classic')
    await act(async () => answerConfig({ enabled: false }))
    expect(screen.getByTestId('workspace')).toHaveTextContent('classic')
    expect(pendingEvents()).toEqual([])
    expect(screen.queryByTestId('look-experiment')).toBeNull()
  })

  it('asks again on the next session page after a failed answer (say, before logging in)', async () => {
    const router = renderAt('/sessions/s-1')
    await screen.findByTestId('workspace')
    await act(async () => answerConfig({ error: 'Unauthorized' }, 401))
    await act(async () => {
      await router.navigate({ to: '/login' })
    })
    await act(async () => {
      await router.navigate({ to: '/sessions/$sessionId', params: { sessionId: 's-2' } })
    })
    await waitFor(() => expect(configCalls()).toHaveLength(2))
  })

  it('asks again when going straight to another session page after a failed answer', async () => {
    const router = renderAt('/sessions/s-1')
    await screen.findByTestId('workspace')
    await act(async () => answerConfig({ error: 'Bad gateway' }, 502))
    await act(async () => {
      await router.navigate({ to: '/sessions/$sessionId', params: { sessionId: 's-2' } })
    })
    await waitFor(() => expect(configCalls()).toHaveLength(2))
  })

  it('going to another session by a ?look= link counts that page in the linked look only', async () => {
    const router = renderAt('/sessions/s-1')
    await screen.findByTestId('workspace')
    await act(async () => answerConfig(ON))
    await waitFor(() => expect(screen.getByTestId('workspace')).toHaveTextContent('b'))
    await act(async () => {
      await router.navigate({ to: '/sessions/$sessionId', params: { sessionId: 's-2' }, search: { look: 'c' } })
    })
    await waitFor(() => expect(screen.getByTestId('workspace')).toHaveTextContent('c'))
    const seen = pendingEvents().filter(e => e.type === 'seen').map(e => (e.type === 'seen' ? e.look : ''))
    expect(seen).toEqual(['b', 'c'])
  })

  it('after a failed answer, joins by the link of the session page that asked again', async () => {
    const router = renderAt('/sessions/s-1?look=a')
    await screen.findByTestId('workspace')
    await act(async () => answerConfig({ error: 'Bad gateway' }, 502))
    await act(async () => {
      await router.navigate({ to: '/sessions/$sessionId', params: { sessionId: 's-2' }, search: { look: 'c' } })
    })
    await waitFor(() => expect(configCalls()).toHaveLength(2))
    await act(async () => answerConfig(ON))
    await waitFor(() => expect(readExperimentState().entry).toEqual({ look: 'c', kind: 'chose' }))
  })

  it('other pages and the shared view ask nothing', async () => {
    const router = renderAt('/login')
    await act(async () => {
      await router.navigate({ to: '/shared/$token', params: { token: 't-1' } })
    })
    await act(async () => {
      await router.navigate({ to: '/sessions' })
    })
    expect(configCalls()).toHaveLength(0)
  })
})

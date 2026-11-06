import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { routeTree } from '@/routeTree.gen'
import { LOOK_STORAGE_KEY, forgetVisitLook, useLook } from '@/looks/look-context'

// The session page's own content is not under test here: only how the route
// reads ?look=, applies it and leaves the address clean.
vi.mock('@/components/SessionWorkspace', () => ({
  SessionWorkspace: ({ sessionId }: { sessionId: string }) => {
    const { look } = useLook()
    return (
      <div data-testid="workspace" data-session-id={sessionId}>
        {look}
      </div>
    )
  },
}))
vi.mock('@/components/Layout', () => ({ Layout: ({ children }: { children: ReactNode }) => <>{children}</> }))

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
    </QueryClientProvider>,
  )
  return router
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
  delete document.documentElement.dataset.look
})

describe('the session page and ?look=', () => {
  it('applies the linked look, remembers it, and takes ?look= out of the address', async () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'b')
    const router = renderAt('/sessions/s-1?look=a&scenarioId=nested')
    expect(await screen.findByTestId('workspace')).toHaveTextContent('a')
    expect(document.documentElement.dataset.look).toBe('a')
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe('a')
    await waitFor(() => expect(router.state.location.search).toEqual({ scenarioId: 'nested' }))
    // The look stays once the link is gone from the address.
    expect(screen.getByTestId('workspace')).toHaveTextContent('a')
  })

  it('reads an unknown or non-text look as Classic', async () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'c')
    renderAt('/sessions/s-1?look=1')
    expect(await screen.findByTestId('workspace')).toHaveTextContent('classic')
    expect(localStorage.getItem(LOOK_STORAGE_KEY)).toBe('classic')
  })

  it('without a link, opens on the remembered look', async () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'c')
    renderAt('/sessions/s-1')
    expect(await screen.findByTestId('workspace')).toHaveTextContent('c')
    expect(document.documentElement.dataset.look).toBe('c')
  })

  it('leaves data-look off <html> on every other page', async () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'c')
    const router = renderAt('/sessions/s-1')
    await screen.findByTestId('workspace')
    await router.navigate({ to: '/login' })
    await waitFor(() => expect(screen.queryByTestId('workspace')).toBeNull())
    expect(document.documentElement.dataset.look).toBeUndefined()
  })
})

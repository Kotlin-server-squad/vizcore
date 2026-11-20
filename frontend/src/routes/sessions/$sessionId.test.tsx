import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { useEffect, type ReactNode } from 'react'
import { routeTree } from '@/routeTree.gen'

// The session page's own content is not under test here: only how the route
// hands ?run= and ?variant= to it and takes ?run= out of the address. This
// stand-in reads the request the way use-auto-run does: once.
const runs = vi.fn()
vi.mock('@/components/SessionWorkspace', () => ({
  SessionWorkspace: ({
    autoRun,
    variant,
    onAutoRunConsumed,
  }: {
    autoRun?: boolean
    variant?: string
    onAutoRunConsumed?: () => void
  }) => {
    useEffect(() => {
      if (!autoRun) return
      onAutoRunConsumed?.()
      runs(variant)
    }, [autoRun, variant, onAutoRunConsumed])
    return <div data-testid="workspace" data-auto-run={String(autoRun === true)} data-variant={variant ?? ''} />
  },
}))
vi.mock('@/components/Layout', () => ({
  Layout: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

function renderAt(entries: string[]) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const history = createMemoryHistory({ initialEntries: entries, initialIndex: entries.length - 1 })
  const router = createRouter({ routeTree, history, context: { queryClient } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router as never} />
    </QueryClientProvider>
  )
  return { router, history, view }
}

const OPENED_BY_RUN =
  '/sessions/s-1?scenarioId=order-processing&scenarioName=Order&run=true&variant=card-declined'

describe('the session page and ?run=', () => {
  it('starts the run once, then takes only ?run= out of the address, in place', async () => {
    runs.mockClear()
    const { router, history } = renderAt(['/login', OPENED_BY_RUN])
    const entries = history.length
    await screen.findByTestId('workspace')
    await waitFor(() =>
      expect(router.state.location.search).toEqual({
        scenarioId: 'order-processing',
        scenarioName: 'Order',
        variant: 'card-declined',
      })
    )
    await waitFor(() => expect(screen.getByTestId('workspace')).toHaveAttribute('data-auto-run', 'false'))
    expect(screen.getByTestId('workspace')).toHaveAttribute('data-variant', 'card-declined')
    expect(runs).toHaveBeenCalledTimes(1)
    expect(runs).toHaveBeenCalledWith('card-declined')
    // Replaced, not pushed: Back leaves the session instead of returning to the address that asked for a run.
    expect(history.length).toBe(entries)
    expect(history.location.href).not.toContain('run=')
  })

  it('does not start a second run on a reload of the address the first one left', async () => {
    runs.mockClear()
    const first = renderAt([OPENED_BY_RUN])
    await waitFor(() => expect(first.history.location.href).not.toContain('run='))
    const left = first.history.location.href
    first.view.unmount()
    expect(runs).toHaveBeenCalledTimes(1)

    renderAt([left])
    expect(await screen.findByTestId('workspace')).toHaveAttribute('data-auto-run', 'false')
    expect(screen.getByTestId('workspace')).toHaveAttribute('data-variant', 'card-declined')
    expect(runs).toHaveBeenCalledTimes(1)
  })

  it.each(['', '1', 'null', 'true', '%5B%22card-declined%22%5D'])('reads ?variant=%s as no variant', async raw => {
    renderAt([`/sessions/s-1?variant=${raw}`])
    expect(await screen.findByTestId('workspace')).toHaveAttribute('data-variant', '')
  })
})

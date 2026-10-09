import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ConnectWizard } from './ConnectWizard'

// --- Router navigate (auto-resolve + Skip both navigate to the live view) ---
const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
}))

// --- The resolve poll is mocked so we can drive the undefined → { sessionId }
// transition. The wizard NO LONGER mints its own session; it polls
// apiClient.resolveCorrelation(correlation) until the REAL connecting app's
// session binds to the client-minted token. The wizard MUST key the query to
// the correlation token and use a positive refetchInterval; the test asserts
// both by inspecting the options the mocked useQuery receives, plus that the
// queryFn calls resolveCorrelation. ---
let polledData: { sessionId: string } | undefined
let lastUseQueryOptions:
  | { queryKey?: unknown[]; enabled?: boolean; refetchInterval?: number; queryFn?: () => unknown }
  | undefined
vi.mock('@tanstack/react-query', () => ({
  useQuery: (opts: {
    queryKey?: unknown[]
    enabled?: boolean
    refetchInterval?: number
    queryFn?: () => unknown
  }) => {
    lastUseQueryOptions = opts
    return { data: opts.enabled ? polledData : undefined }
  },
}))

const resolveCorrelation = vi.fn()
vi.mock('@/lib/api-client', () => ({
  apiClient: { resolveCorrelation: (...args: unknown[]) => resolveCorrelation(...args) },
}))

beforeEach(() => {
  vi.clearAllMocks()
  polledData = undefined
  lastUseQueryOptions = undefined
})

describe('ConnectWizard', () => {
  it('renders the three step headings with copyable snippets and the step-3 waiting spinner', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    expect(screen.getByText('Add the client library')).toBeInTheDocument()
    expect(screen.getByText('Enable in your app')).toBeInTheDocument()
    expect(screen.getByText('Run your app')).toBeInTheDocument()

    // The dependency snippet renders the LOCKED canonical coordinate (D-06),
    // not the stale com.jh:vizcore-client:0.1.
    expect(
      screen.getByText(/com\.jh\.coroutine-visualizer:coroutine-viz-client:0\.1\.0/),
    ).toBeInTheDocument()
    // The start snippet now carries the client-minted correlation token (D-01).
    expect(screen.getByText(/VizcoreClient\.start/)).toBeInTheDocument()
    expect(screen.getByText(/correlation = "/)).toBeInTheDocument()

    // Step 3 shows the waiting copy with the app name.
    expect(screen.getByText(/Waiting for events from/)).toBeInTheDocument()
  })

  it('AUTO-resolves to the live view the moment resolve returns a real sessionId', async () => {
    polledData = undefined
    const { rerender } = render(<ConnectWizard isOpen onClose={vi.fn()} />)

    // While resolve returns undefined (token not bound yet) the wizard has NOT
    // navigated — no premature resolution onto a self-minted/empty session.
    expect(navigate).not.toHaveBeenCalled()

    // Simulate resolve binding to the REAL connecting app's session id.
    polledData = { sessionId: 'real-app-session-1' }
    rerender(<ConnectWizard isOpen onClose={vi.fn()} />)

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({
        to: '/sessions/$sessionId',
        params: { sessionId: 'real-app-session-1' },
      }),
    )
  })

  it('polls resolveCorrelation keyed to the correlation token with a positive refetchInterval', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    expect(lastUseQueryOptions?.enabled).toBe(true)
    // The query is scoped to the resolve-correlation key (token-scoped), never a
    // shared/stale cache.
    expect(lastUseQueryOptions?.queryKey?.[0]).toBe('resolve-correlation')
    expect(typeof lastUseQueryOptions?.refetchInterval).toBe('number')
    expect(lastUseQueryOptions?.refetchInterval).toBeGreaterThan(0)

    // The queryFn calls apiClient.resolveCorrelation (poll the resolve endpoint).
    lastUseQueryOptions?.queryFn?.()
    expect(resolveCorrelation).toHaveBeenCalledTimes(1)
  })

  it('Skip to live view navigates to the resolved id once resolve has landed', () => {
    polledData = { sessionId: 'real-app-session-1' }
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: /skip to live view/i }))
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 'real-app-session-1' },
    })
  })

  it('Cancel fires onClose', () => {
    const onClose = vi.fn()
    render(<ConnectWizard isOpen onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

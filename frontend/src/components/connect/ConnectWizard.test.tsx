import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { ConnectWizard, STILL_WAITING_AFTER_MS } from './ConnectWizard'

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
interface CapturedQueryOptions {
  queryKey?: unknown[]
  enabled?: boolean
  refetchInterval?: unknown
  retry?: unknown
  queryFn?: () => unknown
}
let polledData: { sessionId: string } | undefined
let polledError: Error | null = null
let lastUseQueryOptions: CapturedQueryOptions | undefined
vi.mock('@tanstack/react-query', () => ({
  useQuery: (opts: CapturedQueryOptions) => {
    lastUseQueryOptions = opts
    return { data: opts.enabled ? polledData : undefined, error: polledError }
  },
}))

const resolveCorrelation = vi.fn()
vi.mock('@/lib/api-client', async () => {
  const errors = await vi.importActual<typeof import('@/lib/api-errors')>('@/lib/api-errors')
  return {
    ...errors,
    apiClient: { resolveCorrelation: (...args: unknown[]) => resolveCorrelation(...args) },
  }
})

import { RateLimitedError } from '@/lib/api-errors'

beforeEach(() => {
  vi.clearAllMocks()
  polledData = undefined
  polledError = null
  lastUseQueryOptions = undefined
})

describe('ConnectWizard', () => {
  it('renders the three step headings with copyable snippets and the step-3 waiting spinner', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    expect(screen.getByText('Add the client library')).toBeInTheDocument()
    expect(screen.getByText('Start it in your app')).toBeInTheDocument()
    expect(screen.getByText('Run your app')).toBeInTheDocument()

    // The dependency snippet renders the LOCKED canonical coordinate (D-06),
    // not the stale com.jh:vizcore-client:0.1.
    expect(
      screen.getByText(/com\.jh\.coroutine-visualizer:coroutine-viz-client:0\.1\.0/),
    ).toBeInTheDocument()
    // The start snippet carries the client-minted correlation token (D-01).
    const start = screen.getByLabelText('Kotlin start call')
    expect(start).toHaveTextContent(/VizcoreClient\.start\(/)
    expect(start).toHaveTextContent(/correlation = "/)

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
        // A just-connected app opens with the live stream on (#126).
        search: { live: true },
      }),
    )
  })

  it('polls resolveCorrelation keyed to the correlation token at 1–2 s, not 300 ms (#124)', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    expect(lastUseQueryOptions?.enabled).toBe(true)
    // The query is scoped to the resolve-correlation key (token-scoped), never a
    // shared/stale cache.
    expect(lastUseQueryOptions?.queryKey?.[0]).toBe('resolve-correlation')
    const interval = lastUseQueryOptions?.refetchInterval
    expect(typeof interval).toBe('function')
    const firstDelay = (interval as (q: unknown) => number)({
      state: { data: null, error: null, dataUpdateCount: 0, errorUpdateCount: 0 },
    })
    expect(firstDelay).toBeGreaterThanOrEqual(1000)
    expect(firstDelay).toBeLessThanOrEqual(2000)
    // An immediate retry would only spend more of the rate-limit budget.
    expect(lastUseQueryOptions?.retry).toBe(false)

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
      search: { live: true },
    })
  })

  it('says so when the server rate-limits the resolve poll, instead of waiting silently', () => {
    polledError = new RateLimitedError(30_000)
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent(/rate-limiting/i)
  })

  it('Cancel fires onClose', () => {
    const onClose = vi.fn()
    render(<ConnectWizard isOpen onClose={onClose} />)

    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

describe('ConnectWizard snippet and flow (#126)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('generates a start call with the real backend URL, an empty token (auth off) and no fixed app name', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    const start = screen.getByLabelText('Kotlin start call')
    expect(start).toHaveTextContent(`backendUrl = "${window.location.origin}"`)
    expect(start).toHaveTextContent('token = ""')
    expect(start).toHaveTextContent('appName = "my-app"')
    expect(screen.queryByText(/order-service/)).not.toBeInTheDocument()
    expect(screen.queryByText(/localhost:8080/)).not.toBeInTheDocument()
  })

  it('the app name the user types flows into the snippet and the waiting copy', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    fireEvent.change(screen.getByLabelText('App name'), { target: { value: 'billing' } })

    expect(screen.getByLabelText('Kotlin start call')).toHaveTextContent('appName = "billing"')
    expect(screen.getByText(/Waiting for events from billing/)).toBeInTheDocument()
  })

  it('shows the repository block with credentials in the dependency snippet', () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    const dep = screen.getByLabelText('Gradle dependency')
    expect(dep).toHaveTextContent('maven.pkg.github.com')
    expect(dep).toHaveTextContent(/credentials/)
  })

  it('mints a fresh correlation token every time the wizard is opened, not once per mount', () => {
    const { rerender } = render(<ConnectWizard isOpen={false} onClose={vi.fn()} />)

    rerender(<ConnectWizard isOpen onClose={vi.fn()} />)
    const first = lastUseQueryOptions?.queryKey?.[1]
    rerender(<ConnectWizard isOpen onClose={vi.fn()} />)
    expect(lastUseQueryOptions?.queryKey?.[1]).toBe(first) // stable while open

    rerender(<ConnectWizard isOpen={false} onClose={vi.fn()} />)
    rerender(<ConnectWizard isOpen onClose={vi.fn()} />)
    const second = lastUseQueryOptions?.queryKey?.[1]

    expect(typeof first).toBe('string')
    expect(second).not.toBe(first)
    expect(screen.getByLabelText('Kotlin start call')).toHaveTextContent(String(second))
  })

  it('turns the spinner into a troubleshooting checklist after a while', () => {
    vi.useFakeTimers()
    render(<ConnectWizard isOpen onClose={vi.fn()} />)
    expect(screen.queryByTestId('still-waiting')).not.toBeInTheDocument()

    act(() => {
      vi.advanceTimersByTime(STILL_WAITING_AFTER_MS + 100)
    })

    const help = screen.getByTestId('still-waiting')
    expect(help).toHaveTextContent(/still waiting/i)
    expect(help).toHaveTextContent(`${window.location.origin}/api/health`)
  })

  it('confirms a copy, and says so when the clipboard is unavailable', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'Copy setup snippet' }))

    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
    expect(writeText.mock.calls[0]![0]).toContain('VizcoreClient.start(')

    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    fireEvent.click(screen.getByLabelText('Copy kotlin start call'))
    await waitFor(() =>
      expect(screen.getByLabelText(/copy failed/i)).toBeInTheDocument(),
    )
  })
})

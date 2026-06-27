import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ConnectWizard } from './ConnectWizard'

// --- Router navigate (auto-resolve + Skip both navigate to the live view) ---
const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
}))

// --- useCreateSession mints the new session id when the wizard opens ---
const mutateAsync = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({
  useCreateSession: () => ({ mutateAsync }),
}))

// --- The polled new-session query is mocked so we can drive the 0 → N transition.
// The wizard MUST scope it to the new id (passed as the queryKey id) and use a
// refetchInterval; the test asserts both by inspecting the options it receives. ---
let polledData: { coroutineCount: number } | undefined
let lastUseQueryOptions: { queryKey?: unknown[]; enabled?: boolean; refetchInterval?: number } | undefined
vi.mock('@tanstack/react-query', () => ({
  useQuery: (opts: { queryKey?: unknown[]; enabled?: boolean; refetchInterval?: number }) => {
    lastUseQueryOptions = opts
    return { data: opts.enabled ? polledData : undefined }
  },
}))

beforeEach(() => {
  vi.clearAllMocks()
  polledData = undefined
  lastUseQueryOptions = undefined
  mutateAsync.mockResolvedValue({ sessionId: 'new-session-1', message: 'ok' })
})

describe('ConnectWizard', () => {
  it('renders the three step headings with copyable snippets and the step-3 waiting spinner', async () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    await waitFor(() => expect(mutateAsync).toHaveBeenCalled())

    expect(screen.getByText('Add the client library')).toBeInTheDocument()
    expect(screen.getByText('Enable in your app')).toBeInTheDocument()
    expect(screen.getByText('Run your app')).toBeInTheDocument()

    // The static, public snippets are present (no secret embedded — PD-14).
    expect(screen.getByText(/com\.jh:vizcore-client:0\.1/)).toBeInTheDocument()
    expect(screen.getByText(/VizcoreClient\.start/)).toBeInTheDocument()

    // Step 3 shows the waiting copy with the app name.
    expect(screen.getByText(/Waiting for events from/)).toBeInTheDocument()
  })

  it('AUTO-resolves to the live view when the polled session coroutineCount transitions > 0', async () => {
    polledData = { coroutineCount: 0 }
    const { rerender } = render(<ConnectWizard isOpen onClose={vi.fn()} />)

    await waitFor(() => expect(mutateAsync).toHaveBeenCalled())
    // At count 0 the wizard has NOT navigated (no premature resolution).
    expect(navigate).not.toHaveBeenCalled()

    // Simulate the polled transition 0 → N.
    polledData = { coroutineCount: 4 }
    rerender(<ConnectWizard isOpen onClose={vi.fn()} />)

    await waitFor(() =>
      expect(navigate).toHaveBeenCalledWith({
        to: '/sessions/$sessionId',
        params: { sessionId: 'new-session-1' },
      }),
    )
  })

  it('scopes the poll to the new id with an explicit refetchInterval (never a stale cache)', async () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)

    await waitFor(() => expect(lastUseQueryOptions?.enabled).toBe(true))
    expect(lastUseQueryOptions?.queryKey).toContain('new-session-1')
    expect(typeof lastUseQueryOptions?.refetchInterval).toBe('number')
    expect(lastUseQueryOptions?.refetchInterval).toBeGreaterThan(0)
  })

  it('Skip to live view navigates immediately as a fallback', async () => {
    render(<ConnectWizard isOpen onClose={vi.fn()} />)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /skip to live view/i }))
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: 'new-session-1' },
    })
  })

  it('Cancel fires onClose', async () => {
    const onClose = vi.fn()
    render(<ConnectWizard isOpen onClose={onClose} />)
    await waitFor(() => expect(mutateAsync).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ManageShares } from './ManageShares'
import type { ShareSummary } from '@/types/share'

function renderWithClient(ui: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

const listShares = vi.fn<(sessionId: string) => Promise<ShareSummary[]>>()
const revokeShare = vi.fn<(sessionId: string, token: string) => Promise<void>>()
const createShare = vi.fn()
const toastSuccess = vi.fn<(t: string) => void>()
const toastError = vi.fn<(t: string) => void>()

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    listShares: (sessionId: string) => listShares(sessionId),
    revokeShare: (sessionId: string, token: string) => revokeShare(sessionId, token),
    createShare: (...args: unknown[]) => createShare(...args),
  },
}))

vi.mock('@/lib/toast', () => ({
  toastSuccess: (t: string) => toastSuccess(t),
  toastError: (t: string) => toastError(t),
}))

beforeEach(() => {
  listShares.mockReset()
  revokeShare.mockReset()
  toastSuccess.mockReset()
  toastError.mockReset()
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('ManageShares', () => {
  it('renders rows with short token / expiry / views / last accessed', async () => {
    listShares.mockResolvedValue([
      {
        token: 'abcdef 1234567890abcdef'.replace(' ', ''),
        expiresAt: '2099-01-01T00:00:00Z',
        accessCount: 5,
        lastAccessedAt: '2026-06-01T12:00:00Z',
      },
      {
        token: 'neverexpiretoken000000',
        expiresAt: null,
        accessCount: 0,
        lastAccessedAt: null,
      },
    ])

    renderWithClient(<ManageShares sessionId="sess-1" />)

    // Views value for the first row.
    expect(await screen.findByText('5')).toBeInTheDocument()
    // Never-expires cell + no-hits last-accessed dash.
    expect(screen.getByText('Never')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
    // Two Revoke buttons (one per row).
    expect(screen.getAllByRole('button', { name: 'Revoke' })).toHaveLength(2)
  })

  it('shows the empty state with a "Create link" action', async () => {
    listShares.mockResolvedValue([])

    renderWithClient(<ManageShares sessionId="sess-1" />)

    expect(await screen.findByText('No active share links')).toBeInTheDocument()
    expect(
      screen.getByText('Create a share link to give read-only access to this session.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create link' })).toBeInTheDocument()
  })

  it('shows the error alert when the list fetch fails', async () => {
    listShares.mockRejectedValue(new Error('500'))

    renderWithClient(<ManageShares sessionId="sess-1" />)

    expect(
      await screen.findByText('Could not load share links. Try again.'),
    ).toBeInTheDocument()
  })

  it('revokes a share via the destructive confirm and removes the row', async () => {
    const user = userEvent.setup()
    listShares.mockResolvedValue([
      {
        token: 'token-to-revoke-000000',
        expiresAt: null,
        accessCount: 2,
        lastAccessedAt: '2026-06-01T12:00:00Z',
      },
    ])
    revokeShare.mockResolvedValue(undefined)

    renderWithClient(<ManageShares sessionId="sess-1" />)

    await user.click(await screen.findByRole('button', { name: 'Revoke' }))

    // Destructive confirm modal.
    expect(await screen.findByText('Revoke share link?')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Revoke link' }))

    await waitFor(() => {
      expect(revokeShare).toHaveBeenCalledWith('sess-1', 'token-to-revoke-000000')
      expect(toastSuccess).toHaveBeenCalledWith('Share link revoked')
    })

    // Row removed → now empty state.
    await waitFor(() =>
      expect(screen.getByText('No active share links')).toBeInTheDocument(),
    )
  })
})

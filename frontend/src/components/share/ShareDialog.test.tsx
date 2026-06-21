import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ShareDialog } from './ShareDialog'
import type { CreateShareResponse } from '@/types/share'
import type { ShareExpiry } from '@/types/share'

const createShare = vi.fn<(sessionId: string, expiresIn: ShareExpiry) => Promise<CreateShareResponse>>()
const toastSuccess = vi.fn<(t: string) => void>()
const toastError = vi.fn<(t: string) => void>()

vi.mock('@/lib/api-client', () => ({
  apiClient: {
    createShare: (sessionId: string, expiresIn: ShareExpiry) => createShare(sessionId, expiresIn),
  },
}))

vi.mock('@/lib/toast', () => ({
  toastSuccess: (t: string) => toastSuccess(t),
  toastError: (t: string) => toastError(t),
}))

const writeText = vi.fn<(text: string) => Promise<void>>()

// `userEvent.setup()` installs its own stub on `navigator.clipboard`, so we
// install ours AFTER setup (see each test) — this helper makes the mock the
// live clipboard regardless of userEvent's stub.
function installClipboard() {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText },
  })
}

beforeEach(() => {
  createShare.mockReset()
  toastSuccess.mockReset()
  toastError.mockReset()
  writeText.mockReset()
  writeText.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('ShareDialog', () => {
  it('creates a link with the default expiry mapping and shows the URL', async () => {
    const user = userEvent.setup()
    createShare.mockResolvedValue({
      token: 'tok-abc',
      url: 'https://viz.example/shared/tok-abc',
      expiresAt: '2099-01-01T00:00:00Z',
    })

    render(<ShareDialog isOpen sessionId="sess-1" onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Create link' }))

    await waitFor(() => {
      // Default expiry option is "7 days" → mapped code 7d.
      expect(createShare).toHaveBeenCalledWith('sess-1', '7d')
    })
    expect(
      await screen.findByDisplayValue('https://viz.example/shared/tok-abc'),
    ).toBeInTheDocument()
  })

  it('copies the link to the clipboard and toasts "Link copied"', async () => {
    const user = userEvent.setup()
    installClipboard()
    createShare.mockResolvedValue({
      token: 'tok-abc',
      url: 'https://viz.example/shared/tok-abc',
      expiresAt: null,
    })

    render(<ShareDialog isOpen sessionId="sess-1" onClose={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Create link' }))
    await screen.findByDisplayValue('https://viz.example/shared/tok-abc')

    await user.click(screen.getByRole('button', { name: 'Copy link' }))

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith('https://viz.example/shared/tok-abc')
      expect(toastSuccess).toHaveBeenCalledWith('Link copied')
    })
  })

  it('toasts an error when the clipboard write fails (URL stays selectable)', async () => {
    const user = userEvent.setup()
    installClipboard()
    writeText.mockRejectedValue(new Error('denied'))
    createShare.mockResolvedValue({
      token: 'tok-abc',
      url: 'https://viz.example/shared/tok-abc',
      expiresAt: null,
    })

    render(<ShareDialog isOpen sessionId="sess-1" onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Create link' }))
    await screen.findByDisplayValue('https://viz.example/shared/tok-abc')

    await user.click(screen.getByRole('button', { name: 'Copy link' }))

    await waitFor(() => expect(toastError).toHaveBeenCalled())
    // URL still shown for manual copy.
    expect(
      screen.getByDisplayValue('https://viz.example/shared/tok-abc'),
    ).toBeInTheDocument()
  })

  it('toasts an error when create fails', async () => {
    const user = userEvent.setup()
    createShare.mockRejectedValue(new Error('boom'))

    render(<ShareDialog isOpen sessionId="sess-1" onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Create link' }))

    await waitFor(() =>
      expect(toastError).toHaveBeenCalledWith('Could not create the share link. Try again.'),
    )
  })
})

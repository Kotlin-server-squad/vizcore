import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HeroUIProvider } from '@heroui/react'
import { RecordConfirmModal } from './RecordConfirmModal'

function renderModal(props: Partial<Parameters<typeof RecordConfirmModal>[0]> = {}) {
  const onConfirm = vi.fn()
  const onCancel = vi.fn()
  render(
    <HeroUIProvider>
      <RecordConfirmModal
        isOpen
        estimateMs={150_000}
        speed={1}
        onConfirm={onConfirm}
        onCancel={onCancel}
        {...props}
      />
    </HeroUIProvider>
  )
  return { onConfirm, onCancel }
}

describe('RecordConfirmModal', () => {
  it('renders the locked title and Start/Cancel actions when open', () => {
    renderModal()
    expect(screen.getByText('Record replay?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start recording' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('renders the formatted estimate (m min s s) and speed in the body', () => {
    // 150_000ms → 2 min 30 s
    renderModal({ estimateMs: 150_000, speed: 2 })
    const body = screen.getByTestId('record-confirm-body')
    expect(body.textContent).toContain('2 min 30 s')
    expect(body.textContent).toContain('2x')
  })

  it('calls onConfirm when Start recording is pressed', async () => {
    const user = userEvent.setup()
    const { onConfirm } = renderModal()
    await user.click(screen.getByRole('button', { name: 'Start recording' }))
    expect(onConfirm).toHaveBeenCalledOnce()
  })

  it('calls onCancel when Cancel is pressed', async () => {
    const user = userEvent.setup()
    const { onCancel } = renderModal()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('renders nothing when closed', () => {
    renderModal({ isOpen: false })
    expect(screen.queryByText('Record replay?')).not.toBeInTheDocument()
  })
})

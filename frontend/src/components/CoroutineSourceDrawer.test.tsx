import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CoroutineSourceDrawer } from './CoroutineSourceDrawer'

// Mock the body so this test proves the SHELL (title + close + mount), not the body.
vi.mock('./CoroutineSourceStack', () => ({
  CoroutineSourceStack: ({ coroutineId }: { coroutineId: string | null }) => (
    <div data-testid="source-stack">stack:{coroutineId}</div>
  ),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('CoroutineSourceDrawer', () => {
  it('renders the title, close button, and body when open', () => {
    const onClose = vi.fn()
    render(
      <CoroutineSourceDrawer
        sessionId="s-1"
        coroutineId="c-1"
        label="Worker"
        isOpen
        onClose={onClose}
      />,
    )

    expect(screen.getByText('Coroutine source — Worker')).toBeInTheDocument()
    expect(screen.getByTestId('source-stack')).toHaveTextContent('stack:c-1')

    const closeButton = screen.getByRole('button', { name: 'Close source drawer' })
    fireEvent.click(closeButton)
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('falls back to the coroutine id when no label is given', () => {
    render(
      <CoroutineSourceDrawer
        sessionId="s-1"
        coroutineId="c-42"
        isOpen
        onClose={vi.fn()}
      />,
    )

    expect(screen.getByText('Coroutine source — c-42')).toBeInTheDocument()
  })

  it('does not render the drawer content when closed', () => {
    render(
      <CoroutineSourceDrawer
        sessionId="s-1"
        coroutineId="c-1"
        label="Worker"
        isOpen={false}
        onClose={vi.fn()}
      />,
    )

    expect(screen.queryByText('Coroutine source — Worker')).not.toBeInTheDocument()
    expect(screen.queryByTestId('source-stack')).not.toBeInTheDocument()
  })
})

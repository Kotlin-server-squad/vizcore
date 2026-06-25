import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SuspensionSourceRef } from './CoroutineNodeEnhanced'

const toastSuccessMock = vi.fn()
vi.mock('@/lib/toast', () => ({
  toastSuccess: (title: string) => toastSuccessMock(title),
}))

const writeTextMock = vi.fn(() => Promise.resolve())

describe('SuspensionSourceRef jump-to-code (Delta S1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function mockClipboard() {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: writeTextMock },
      configurable: true,
    })
  }

  it('renders a user-frame file:line as a role=button jump target and copies on click', async () => {
    const user = userEvent.setup()
    mockClipboard()
    render(
      <SuspensionSourceRef
        fn="com.acme.app.OrderService.process"
        fileName="OrderService.kt"
        lineNumber={42}
      />,
    )

    const jumpTarget = screen.getByRole('button', {
      name: 'Jump to code: OrderService.kt:42',
    })
    expect(jumpTarget).toBeInTheDocument()
    expect(jumpTarget).toHaveTextContent('OrderService.kt:42')

    await user.click(jumpTarget)
    expect(writeTextMock).toHaveBeenCalledWith('OrderService.kt:42')
    expect(toastSuccessMock).toHaveBeenCalledWith('Copied OrderService.kt:42')
  })

  it('renders a library-frame file:line as inert text (no role/aria-label, not clickable)', async () => {
    const user = userEvent.setup()
    mockClipboard()
    render(
      <SuspensionSourceRef
        fn="kotlinx.coroutines.delay"
        fileName="Delay.kt"
        lineNumber={100}
      />,
    )

    // The library frame's file:line is present as plain text...
    expect(screen.getByText('Delay.kt:100')).toBeInTheDocument()
    // ...but it is not a jump target.
    expect(
      screen.queryByRole('button', { name: 'Jump to code: Delay.kt:100' }),
    ).toBeNull()

    await user.click(screen.getByText('Delay.kt:100'))
    expect(writeTextMock).not.toHaveBeenCalled()
    expect(toastSuccessMock).not.toHaveBeenCalled()
  })
})

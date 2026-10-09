import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LockedPanel } from './LockedPanel'

const props = {
  title: 'Lock contention',
  whatYouWouldSee: 'Who holds each mutex, who is queued behind it, and for how long.',
  unlockWith: 'Swap `Mutex()` for `VizMutex()` in the code you want to watch.',
}

describe('LockedPanel', () => {
  it('says what would appear here', () => {
    render(<LockedPanel {...props} />)
    expect(screen.getByText(/who holds each mutex/i)).toBeInTheDocument()
  })

  it('names the specific change that unlocks it', () => {
    render(<LockedPanel {...props} />)
    expect(screen.getByText(/VizMutex/)).toBeInTheDocument()
  })

  it('reads as an invitation, not an error', () => {
    const { container } = render(<LockedPanel {...props} />)
    expect(container.textContent).not.toMatch(/error|failed|unavailable|not supported/i)
    // Never danger-styled: nothing is wrong, there is simply more available.
    expect(container.innerHTML).not.toContain('danger')
  })

  it('titles the thing that is missing, not the lock', () => {
    render(<LockedPanel {...props} />)
    expect(screen.getByRole('heading', { name: /lock contention/i })).toBeInTheDocument()
  })
})

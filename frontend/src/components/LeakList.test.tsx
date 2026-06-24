import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LeakList } from './LeakList'
import type { LeakDto } from '@/types/api'

const THRESHOLD = 30_000

function leak(overrides: Partial<LeakDto> & Pick<LeakDto, 'coroutineId'>): LeakDto {
  return { label: 'fetchUser', aliveMs: 42_000, ...overrides }
}

describe('LeakList', () => {
  it('renders a warning row per leak with "{name} · alive {duration}" copy', () => {
    render(
      <LeakList leaks={[leak({ coroutineId: 'dp-1' })]} leakThresholdMs={THRESHOLD} />,
    )

    expect(screen.getByText(/fetchUser · alive 42s/)).toBeInTheDocument()
  })

  it('shows the singular badge "1 potential leak" for a single leak', () => {
    render(
      <LeakList leaks={[leak({ coroutineId: 'dp-1' })]} leakThresholdMs={THRESHOLD} />,
    )

    expect(screen.getByText('1 potential leak')).toBeInTheDocument()
  })

  it('shows the plural badge "2 potential leaks" for multiple leaks', () => {
    render(
      <LeakList
        leaks={[
          leak({ coroutineId: 'dp-1' }),
          leak({ coroutineId: 'dp-2', label: 'pollOrders', aliveMs: 61_000 }),
        ]}
        leakThresholdMs={THRESHOLD}
      />,
    )

    expect(screen.getByText('2 potential leaks')).toBeInTheDocument()
    expect(screen.getByText(/pollOrders · alive 61s/)).toBeInTheDocument()
  })

  it('styles leaks as warning (amber), never danger', () => {
    const { container } = render(
      <LeakList leaks={[leak({ coroutineId: 'dp-1' })]} leakThresholdMs={THRESHOLD} />,
    )

    // literal warning tile classes (IN-12) — never danger for a leak
    expect(container.querySelector('.bg-warning\\/10')).not.toBeNull()
    expect(container.querySelector('.text-warning')).not.toBeNull()
    expect(container.querySelector('.bg-danger\\/10')).toBeNull()
    expect(container.querySelector('.text-danger')).toBeNull()
  })

  it('renders the leak-threshold tooltip copy referencing the threshold', () => {
    render(
      <LeakList leaks={[leak({ coroutineId: 'dp-1' })]} leakThresholdMs={THRESHOLD} />,
    )

    // Tooltip content is rendered into the DOM (HeroUI renders content lazily;
    // we assert the formatted threshold is present in the accessible markup).
    expect(
      screen.getByLabelText(
        /Active longer than the leak threshold \(30s\)\. May be a stuck or never-completing coroutine\./,
      ),
    ).toBeInTheDocument()
  })

  it('renders nothing (no badge) when there are no leaks', () => {
    const { container } = render(<LeakList leaks={[]} leakThresholdMs={THRESHOLD} />)

    expect(screen.queryByText(/potential leak/)).toBeNull()
    expect(container.querySelector('.bg-warning\\/10')).toBeNull()
  })

  it('falls back to the coroutineId when label is null', () => {
    render(
      <LeakList
        leaks={[leak({ coroutineId: 'dp-9', label: null, aliveMs: 5_000 })]}
        leakThresholdMs={THRESHOLD}
      />,
    )

    expect(screen.getByText(/dp-9 · alive 5s/)).toBeInTheDocument()
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { HeroUIProvider } from '@heroui/react'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))

// ComparisonView is separately tested and data-heavy; stub it so this test is
// about the overlay's hosting contract, not the comparison itself.
const comparisonProps = vi.fn()
vi.mock('@/components/comparison/ComparisonView', () => ({
  ComparisonView: (props: Record<string, unknown>) => {
    comparisonProps(props)
    return <div data-testid="comparison-view" />
  },
}))

import { CompareOverlay } from './CompareOverlay'

beforeEach(() => vi.clearAllMocks())

describe('CompareOverlay', () => {
  it('hosts ComparisonView when open', () => {
    render(
      <HeroUIProvider>
        <CompareOverlay isOpen onClose={vi.fn()} />
      </HeroUIProvider>
    )
    expect(screen.getByTestId('comparison-view')).toBeInTheDocument()
  })

  it('renders nothing when closed', () => {
    render(
      <HeroUIProvider>
        <CompareOverlay isOpen={false} onClose={vi.fn()} />
      </HeroUIProvider>
    )
    expect(screen.queryByTestId('comparison-view')).not.toBeInTheDocument()
  })

  it('owns the selection locally rather than in the URL', () => {
    render(
      <HeroUIProvider>
        <CompareOverlay isOpen onClose={vi.fn()} />
      </HeroUIProvider>
    )
    const props = comparisonProps.mock.calls[0]![0] as Record<string, unknown>
    expect(props.a).toBeUndefined()
    expect(props.b).toBeUndefined()
    expect(typeof props.onAChange).toBe('function')
    expect(typeof props.onBChange).toBe('function')
  })
})

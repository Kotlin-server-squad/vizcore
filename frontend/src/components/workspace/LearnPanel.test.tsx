import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LearnPanel } from './LearnPanel'

describe('LearnPanel (S-9, T-6)', () => {
  it('is marked as explanation, not recorded data (S-6)', () => {
    render(<LearnPanel />)
    expect(screen.getByText('Explanation, not recorded data')).toBeInTheDocument()
  })

  it('holds the legend with every state in words', () => {
    render(<LearnPanel />)
    for (const word of ['Active', 'Suspended', 'Waiting for children', 'Completed', 'Failed', 'Cancelled']) {
      expect(screen.getByTestId('learn-legend')).toHaveTextContent(word)
    }
  })

  it('qualifies how a failure spreads: an ordinary job versus a SupervisorJob (T-6)', () => {
    render(<LearnPanel />)
    expect(screen.getByText(/In an ordinary Job/)).toHaveTextContent('cancels its other children')
    expect(screen.getByText(/In an ordinary Job/)).toHaveTextContent('SupervisorJob')
  })

  it('names the sampling (H-4)', () => {
    render(<LearnPanel />)
    expect(screen.getByTestId('learn-sampling')).toHaveTextContent('captured coroutines')
  })

  it('shows a demo\'s own pipeline view when given one', () => {
    render(<LearnPanel><div data-testid="pipeline" /></LearnPanel>)
    expect(screen.getByTestId('pipeline')).toBeInTheDocument()
  })
})

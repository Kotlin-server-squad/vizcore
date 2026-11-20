import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@tanstack/react-router', () => ({
  createFileRoute: () => (opts: unknown) => opts,
}))
vi.mock('@/components/Layout', () => ({ Layout: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }))
vi.mock('@/components/ScenarioBuilder', () => ({ ScenarioBuilder: () => <div data-testid="scenario-builder" /> }))
const usePublicDemoMock = vi.fn(() => false)
vi.mock('@/hooks/use-public-demo', () => ({ usePublicDemo: () => usePublicDemoMock() }))

import { ScenarioBuilderPage } from './builder'

beforeEach(() => {
  usePublicDemoMock.mockReturnValue(false)
})

describe('the scenario builder page', () => {
  it('shows the builder', () => {
    render(<ScenarioBuilderPage />)
    expect(screen.getByTestId('scenario-builder')).toBeInTheDocument()
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('on a public demo server says custom scenarios are not available, instead of a builder that fails', () => {
    usePublicDemoMock.mockReturnValue(true)
    render(<ScenarioBuilderPage />)
    expect(screen.queryByTestId('scenario-builder')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Custom scenarios are not available here' })).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('This is a public demo.')
  })
})

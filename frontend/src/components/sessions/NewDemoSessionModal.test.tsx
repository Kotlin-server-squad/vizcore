import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HeroUIProvider } from '@heroui/react'

const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => navigate }))

const useScenariosMock = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({ useScenarios: () => useScenariosMock() }))

const mutateAsync = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({
  useCreateSession: () => ({ mutateAsync }),
}))

import { NewDemoSessionModal } from './NewDemoSessionModal'

const scenarios = [
  { id: 'order-processing', name: 'Order Processing', category: 'realistic', duration: '~5s' },
  { id: 'nested', name: 'Nested Coroutines', category: 'basic' },
]

beforeEach(() => {
  vi.clearAllMocks()
  useScenariosMock.mockReturnValue({ data: { scenarios }, isLoading: false })
  mutateAsync.mockResolvedValue({ sessionId: 'scenario-Order Processing' })
})

const renderModal = () =>
  render(
    <HeroUIProvider>
      <NewDemoSessionModal isOpen onClose={vi.fn()} />
    </HeroUIProvider>
  )

describe('NewDemoSessionModal', () => {
  it('lists the available scenarios, grouped by category', async () => {
    renderModal()
    expect(await screen.findByText('Order Processing')).toBeInTheDocument()
    expect(screen.getByText('Nested Coroutines')).toBeInTheDocument()
    // Grouped: the category is stated once as a heading, not repeated per row.
    expect(screen.getByRole('heading', { name: 'Real-world' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Patterns' })).toBeInTheDocument()
  })

  it('mints a scenario-prefixed session so the DEMO badge derives correctly', async () => {
    renderModal()
    await userEvent.click((await screen.findAllByRole('button', { name: /start/i }))[0]!)
    expect(mutateAsync).toHaveBeenCalledWith('scenario-Order Processing')
  })

  it('navigates to the created session', async () => {
    renderModal()
    await userEvent.click((await screen.findAllByRole('button', { name: /start/i }))[0]!)
    expect(navigate).toHaveBeenCalledWith(
      expect.objectContaining({
        to: '/sessions/$sessionId',
        params: { sessionId: 'scenario-Order Processing' },
      })
    )
  })
})

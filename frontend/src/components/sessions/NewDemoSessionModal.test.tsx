import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
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
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'

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
    expect(screen.getByRole('heading', { name: 'Real life' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Basics' })).toBeInTheDocument()
  })

  it('mints a scenario-prefixed session so the DEMO badge derives correctly', async () => {
    renderModal()
    await userEvent.click((await screen.findAllByRole('button', { name: /start/i }))[0]!)
    expect(mutateAsync).toHaveBeenCalledWith({ name: 'scenario-Order Processing', demoId: 'order-processing' })
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

describe('NewDemoSessionModal while a demo starts', () => {
  it('disables the other Start buttons, one start at a time', async () => {
    mutateAsync.mockReturnValue(new Promise(() => {}))
    renderModal()
    await userEvent.click(await screen.findByRole('button', { name: /start order processing/i }))
    expect(screen.getByRole('button', { name: /start nested coroutines/i })).toBeDisabled()
  })
})

describe('NewDemoSessionModal with the full catalog', () => {
  const all = catalog.scenarios

  beforeEach(() => {
    useScenariosMock.mockReturnValue({ data: { scenarios: all }, isLoading: false })
    mutateAsync.mockImplementation(async ({ name }: { name: string }) => ({ sessionId: `sid-${name}` }))
  })

  const sectionOf = (label: string) => screen.getByRole('heading', { name: label }).closest('section')!

  it('offers all 32 demos, each with its own Start button', async () => {
    renderModal()
    await screen.findByText('Simple Flow: Stock Ticker')
    expect(all).toHaveLength(32)
    expect(screen.getAllByRole('button', { name: /start/i })).toHaveLength(32)
    for (const demo of all) {
      const sameName = all.filter((s) => s.name === demo.name).length
      expect(screen.getAllByText(demo.name)).toHaveLength(sameName)
    }
  })

  it('names each Start button after its demo and group, so screen readers can tell them apart', async () => {
    renderModal()
    await screen.findByText('Simple Flow: Stock Ticker')
    const names = screen.getAllByRole('button', { name: /^start /i }).map((b) => b.getAttribute('aria-label'))
    expect(new Set(names).size).toBe(32)
    expect(screen.getByRole('button', { name: 'Start Producer-Consumer: Coffee Bar (Patterns)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start Producer-Consumer Buffer: The Bakery Rack (Sync: Semaphore)' })).toBeInTheDocument()
  })

  it('groups the demos in the order of the catalog', async () => {
    renderModal()
    await screen.findByText('Simple Flow: Stock Ticker')
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Real life',
      'Basics',
      'Channels',
      'Flow',
      'Patterns',
      'Sync: Mutex',
      'Sync: Semaphore',
      'Combined',
    ])
    for (const demo of all) {
      expect(within(sectionOf(demo.groupLabel)).getByText(demo.name)).toBeInTheDocument()
    }
  })

  it.each([
    ['Real life', 'order-processing'],
    ['Basics', 'deep-nesting'],
    ['Channels', 'channel-fan-out'],
    ['Flow', 'flow/stateflow'],
    ['Patterns', 'patterns/circuit-breaker'],
    ['Sync: Mutex', 'sync/mutex/deadlock-demo'],
    ['Sync: Semaphore', 'sync/semaphore/connection-pool'],
    ['Combined', 'sync/combined/ecommerce'],
  ])('starts a %s demo (%s) in a new demo session that runs that demo', async (label, id) => {
    renderModal()
    await screen.findByText('Simple Flow: Stock Ticker')
    const demo = all.find((s) => s.id === id)!
    const row = within(sectionOf(label)).getByText(demo.name).closest('[data-demo-row]') as HTMLElement
    await userEvent.click(within(row).getByRole('button', { name: /start/i }))

    expect(mutateAsync).toHaveBeenCalledWith({ name: `scenario-${demo.name}`, demoId: id })
    expect(navigate).toHaveBeenCalledWith({
      to: '/sessions/$sessionId',
      params: { sessionId: `sid-scenario-${demo.name}` },
      // The session page runs it with POST /api/scenarios/{scenarioId}.
      search: { scenarioId: id, scenarioName: demo.name },
    })
  })

  it('still groups a list from an older server, which sends no groups', async () => {
    useScenariosMock.mockReturnValue({
      data: {
        scenarios: [
          { id: 'order-processing', name: 'Order Processing', category: 'realistic' },
          { id: 'channel-buffered', name: 'Channel Buffered', category: 'channel' },
          { id: 'nested', name: 'Nested Coroutines', category: 'basic' },
        ],
      },
      isLoading: false,
    })
    renderModal()
    await screen.findByText('Nested Coroutines')
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Real life',
      'Channels',
      'Basics',
    ])
  })
})

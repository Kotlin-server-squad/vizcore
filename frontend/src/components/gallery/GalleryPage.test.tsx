import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HeroUIProvider } from '@heroui/react'

const useScenariosMock = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({ useScenarios: () => useScenariosMock() }))

const start = vi.fn()
const startState = { preparing: null as string | null, startError: null as unknown }
vi.mock('@/hooks/use-start-demo', () => ({
  useStartDemo: () => ({ start, ...startState }),
}))

import { GalleryPage } from './GalleryPage'
import { MISTAKES } from '@/content/gallery/mistakes'
import { ApiError } from '@/lib/api-errors'
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'

const all = catalog.scenarios
const LABELS = ['Real life', 'Basics', 'Channels', 'Flow', 'Patterns', 'Sync: Mutex', 'Sync: Semaphore', 'Combined']
const refetch = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  startState.preparing = null
  startState.startError = null
  useScenariosMock.mockReturnValue({ data: { scenarios: all }, isLoading: false, error: null, refetch })
})

afterEach(() => {
  window.location.hash = ''
})

const renderPage = (group?: string, onGroupChange = vi.fn()) =>
  render(
    <HeroUIProvider>
      <GalleryPage group={group} onGroupChange={onGroupChange} />
    </HeroUIProvider>,
  )

const cardTitles = () => screen.queryAllByTestId('demo-card').map((c) => within(c).getByRole('heading').textContent)

describe('GalleryPage', () => {
  it('has a tab per catalog group, in order, then Common mistakes, each with its count', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: 'Example Gallery' })).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent)
    expect(tabs).toHaveLength(9)
    LABELS.forEach((label, i) => {
      const count = all.filter((s) => s.groupLabel === label).length
      expect(tabs[i]).toBe(`${label}${count}`)
    })
    expect(tabs[8]).toBe(`Common mistakes${MISTAKES.length}`)
  })

  it('shows the first group when no tab is chosen', () => {
    renderPage()
    expect(cardTitles()).toEqual(all.filter((s) => s.group === 'real-life').map((s) => s.name))
  })

  it('falls back to the first group for an unknown tab', () => {
    renderPage('no-such-group')
    expect(cardTitles()).toEqual(all.filter((s) => s.group === 'real-life').map((s) => s.name))
  })

  it('shows exactly the demos of the chosen group', () => {
    renderPage('basics')
    expect(cardTitles()).toEqual(all.filter((s) => s.group === 'basics').map((s) => s.name))
    expect(cardTitles()).toHaveLength(7)
  })

  it('reaches every demo of the catalog through its own tab', () => {
    const groups = [...new Set(all.map((s) => s.group))]
    const seen: string[] = []
    for (const group of groups) {
      const { unmount } = renderPage(group)
      for (const card of screen.getAllByTestId('demo-card')) {
        seen.push(card.getAttribute('data-demo-id')!)
      }
      unmount()
    }
    expect(seen.sort()).toEqual(all.map((s) => s.id).sort())
  })

  it('reports the tab the reader picks', async () => {
    const onGroupChange = vi.fn()
    renderPage('basics', onGroupChange)
    await userEvent.click(screen.getByRole('tab', { name: /^Flow/ }))
    expect(onGroupChange).toHaveBeenLastCalledWith('flow')
    await userEvent.click(screen.getByRole('tab', { name: /^Common mistakes/ }))
    expect(onGroupChange).toHaveBeenLastCalledWith('mistakes')
  })

  it('starts the demo of the card, with a Run button named after demo and group', async () => {
    renderPage('sync-semaphore')
    await userEvent.click(screen.getByRole('button', { name: 'Run Producer-Consumer (Sync: Semaphore)' }))
    expect(start).toHaveBeenCalledWith(expect.objectContaining({ id: 'sync/semaphore/producer-consumer' }))
  })

  it('shows a failed start above the cards', () => {
    startState.startError = {
      scenario: 'Nested: Product Page',
      error: new ApiError(503, 'Server busy: too many demos running, try again in a few seconds', 'demo-limit'),
    }
    renderPage('basics')
    expect(screen.getByRole('alert')).toHaveTextContent('Could not start “Nested: Product Page”: Server busy')
  })

  it('shows placeholders while the list loads', () => {
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: true, error: null, refetch })
    renderPage('basics')
    expect(screen.getAllByTestId('demo-card-skeleton').length).toBeGreaterThan(0)
    expect(screen.queryAllByTestId('demo-card')).toHaveLength(0)
  })

  it('shows placeholders, not an empty catalog, while a request waits for the network', () => {
    // React Query while offline: pending, not loading, no error and no data.
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: false, isPending: true, error: null, refetch })
    renderPage('basics')
    expect(screen.getAllByTestId('demo-card-skeleton').length).toBeGreaterThan(0)
    expect(screen.queryAllByRole('tab')).toHaveLength(0)
  })

  it('disables the other Run buttons while a demo is starting', () => {
    startState.preparing = 'nested'
    renderPage('basics')
    expect(screen.getByRole('button', { name: 'Run Parallel: Dashboard (Basics)' })).toBeDisabled()
  })

  it('ignores a malformed link fragment instead of failing', () => {
    window.location.hash = '#%'
    renderPage('mistakes')
    expect(screen.getAllByTestId('mistake-card')).toHaveLength(MISTAKES.length)
  })

  it('offers Retry when the list cannot be loaded', async () => {
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: false, error: new ApiError(500, 'boom'), refetch })
    renderPage('basics')
    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(refetch).toHaveBeenCalled()
  })

  it('lists the common mistakes, each with an anchor and its demo still to come', () => {
    renderPage('mistakes')
    const cards = screen.getAllByTestId('mistake-card')
    expect(cards.map((c) => c.id).sort()).toEqual(MISTAKES.map((m) => m.id).sort())
    for (const card of cards) {
      const mistake = MISTAKES.find((m) => m.id === card.id)!
      expect(within(card).getByRole('heading', { name: mistake.title })).toBeInTheDocument()
      expect(within(card).getByRole('button', { name: /demo coming/i })).toBeDisabled()
      expect(within(card).queryByText('Detected by the visualizer') !== null).toBe(mistake.detected)
    }
  })

  it('scrolls to the mistake named in the link', () => {
    const scrolled: string[] = []
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this.id)
    }
    try {
      window.location.hash = '#LEAKED_COROUTINE'
      renderPage('mistakes')
      expect(scrolled).toContain('LEAKED_COROUTINE')
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('still scrolls to the mistake when the demo list was loading at first', () => {
    const scrolled: string[] = []
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this.id)
    }
    try {
      window.location.hash = '#WRONG_DISPATCHER'
      useScenariosMock.mockReturnValue({ data: undefined, isLoading: true, error: null, refetch })
      const { rerender } = renderPage('mistakes')
      useScenariosMock.mockReturnValue({ data: { scenarios: all }, isLoading: false, error: null, refetch })
      rerender(
        <HeroUIProvider>
          <GalleryPage group="mistakes" onGroupChange={vi.fn()} />
        </HeroUIProvider>,
      )
      expect(scrolled).toContain('WRONG_DISPATCHER')
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('offers no How it works or level until a demo has a guide', () => {
    renderPage('basics')
    expect(screen.queryByText(/how it works/i)).toBeNull()
    expect(screen.queryByText(/beginner|intermediate|advanced/i)).toBeNull()
  })
})

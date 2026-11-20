import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HeroUIProvider } from '@heroui/react'

const useScenariosMock = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({ useScenarios: () => useScenariosMock() }))

const start = vi.fn()
const startState = { preparing: null as string | null, preparingVariant: null as string | null, startError: null as unknown }
vi.mock('@/hooks/use-start-demo', () => ({
  useStartDemo: () => ({ start, ...startState }),
}))

// A plain anchor whose href shows where the router would go.
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    children,
    to,
    params,
    ...rest
  }: { children: React.ReactNode; to: string; params?: Record<string, string> } & Record<string, unknown>) => {
    let path = to
    for (const [key, value] of Object.entries(params ?? {})) path = path.replace(`$${key}`, value)
    return (
      <a href={path} {...rest}>
        {children}
      </a>
    )
  },
}))

import { GalleryPage } from './GalleryPage'
import { MISTAKES } from '@/content/gallery/mistakes'
import type { Mistake } from '@/content/gallery/types'
import { GUIDES } from '@/content/gallery/guides'
import { demoSlug } from '@/lib/demo-slug'
import { ApiError } from '@/lib/api-errors'
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'

const all = catalog.scenarios
const LABELS = ['Real life', 'Basics', 'Channels', 'Flow', 'Patterns', 'Sync: Mutex', 'Sync: Semaphore', 'Combined']
const refetch = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  startState.preparing = null
  startState.preparingVariant = null
  startState.startError = null
  useScenariosMock.mockReturnValue({ data: { scenarios: all }, isLoading: false, error: null, refetch })
})

afterEach(() => {
  window.location.hash = ''
})

const renderPage = (group?: string, onGroupChange = vi.fn(), mistakes?: Mistake[]) =>
  render(
    <HeroUIProvider>
      <GalleryPage group={group} onGroupChange={onGroupChange} mistakes={mistakes} />
    </HeroUIProvider>,
  )

/** The mistakes once the first of them has a demo to run: the Common mistakes tab is there only then. */
const RUNNABLE: Mistake[] = MISTAKES.map((m, i) => (i === 0 ? { ...m, demoId: 'nested' } : m))

/**
 * Every demo in the catalog has a guide now. A demo the server adds before its guide is written still gets a card
 * and Run, without a level or a How it works link: this catalog has one, in a group of its own.
 */
const withUnguidedDemo = () =>
  useScenariosMock.mockReturnValue({ data: { scenarios: [...all, UNGUIDED] }, isLoading: false, error: null, refetch })

const UNGUIDED = {
  id: 'labs/new-demo',
  name: 'New demo',
  description: 'A demo without a guide yet.',
  category: 'labs',
  group: 'labs',
  groupLabel: 'Labs',
  duration: '~10 seconds',
  method: 'POST',
  endpoint: '/api/scenarios/labs/new-demo',
}

const cardTitles = () => screen.queryAllByTestId('demo-card').map((c) => within(c).getByRole('heading').textContent)

describe('GalleryPage', () => {
  it('has a tab per catalog group, in order, each with its count', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1, name: 'Example Gallery' })).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent)
    expect(tabs).toHaveLength(8)
    LABELS.forEach((label, i) => {
      const count = all.filter((s) => s.groupLabel === label).length
      expect(tabs[i]).toBe(`${label}${count}`)
    })
  })

  it('has no Common mistakes tab while no mistake has a demo to run', () => {
    // The app's own content: this fails, on purpose, once a mistake gets its demo; the tab is back then.
    expect(MISTAKES.some((m) => m.demoId !== undefined)).toBe(false)
    renderPage()
    expect(screen.queryByRole('tab', { name: /^Common mistakes/ })).toBeNull()
  })

  it('shows the first group for a link to the hidden Common mistakes tab', () => {
    window.location.hash = '#LEAKED_COROUTINE'
    renderPage('mistakes')
    expect(screen.queryAllByTestId('mistake-card')).toHaveLength(0)
    expect(cardTitles()).toEqual(all.filter((s) => s.group === 'real-life').map((s) => s.name))
    expect(screen.getByRole('tab', { name: /^Real life/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('says so instead of failing when there is nothing to show at all', () => {
    useScenariosMock.mockReturnValue({ data: { scenarios: [] }, isLoading: false, error: null, refetch })
    renderPage('mistakes')
    expect(screen.getByText('The server lists no examples.')).toBeInTheDocument()
  })

  it('adds Common mistakes after the groups once a mistake has a demo to run', () => {
    renderPage(undefined, vi.fn(), RUNNABLE)
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
    renderPage('basics', onGroupChange, RUNNABLE)
    await userEvent.click(screen.getByRole('tab', { name: /^Flow/ }))
    expect(onGroupChange).toHaveBeenLastCalledWith('flow')
    await userEvent.click(screen.getByRole('tab', { name: /^Common mistakes/ }))
    expect(onGroupChange).toHaveBeenLastCalledWith('mistakes')
  })

  it('starts the demo of the card, with a Run button named after demo and group', async () => {
    renderPage('sync-semaphore')
    await userEvent.click(screen.getByRole('button', { name: 'Run Producer-Consumer Buffer: The Bakery Rack (Sync: Semaphore)' }))
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
    renderPage('mistakes', vi.fn(), RUNNABLE)
    expect(screen.getAllByTestId('mistake-card')).toHaveLength(MISTAKES.length)
  })

  it('offers Retry when the list cannot be loaded', async () => {
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: false, error: new ApiError(500, 'boom'), refetch })
    renderPage('basics')
    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(refetch).toHaveBeenCalled()
  })

  it.each([
    ['order-processing', 'Play: card declined', 'card-declined'],
    ['user-registration', 'Play: email retried', 'email-retried'],
    ['report-generation', 'Play: slow API cancelled', 'slow-api-cancelled'],
  ])('gives %s a second button, %s, which starts that ending', async (id, label, variant) => {
    renderPage('real-life')
    const card = screen.getAllByTestId('demo-card').find((c) => c.getAttribute('data-demo-id') === id)!
    const buttons = within(card).getAllByRole('button')
    // Run stays the main button, before the second ending's.
    expect(buttons.map((b) => b.textContent)).toEqual(['Run', label])
    await userEvent.click(buttons[1]!)
    expect(start).toHaveBeenCalledWith(expect.objectContaining({ id }), variant)
    await userEvent.click(buttons[0]!)
    expect(start).toHaveBeenLastCalledWith(expect.objectContaining({ id }))
  })

  it('gives a demo with one ending one button', () => {
    renderPage('basics')
    expect(screen.queryByRole('button', { name: /^Play: / })).toBeNull()
  })

  it('shows which ending is starting, and holds the other button', () => {
    startState.preparing = 'order-processing'
    startState.preparingVariant = 'card-declined'
    renderPage('real-life')
    const card = screen.getAllByTestId('demo-card').find((c) => c.getAttribute('data-demo-id') === 'order-processing')!
    const [run, second] = within(card).getAllByRole('button')
    expect(run).toBeDisabled()
    expect(second).toHaveAttribute('data-loading', 'true')
  })

  it('no longer tells the reader to add a parameter to the address', () => {
    renderPage('real-life')
    for (const card of screen.getAllByTestId('demo-card')) expect(card.textContent).not.toMatch(/\?\w+=true/)
  })

  it('lists the common mistakes, each with an anchor, and Run for the one that has a demo', () => {
    renderPage('mistakes', vi.fn(), RUNNABLE)
    const cards = screen.getAllByTestId('mistake-card')
    expect(cards.map((c) => c.id).sort()).toEqual(MISTAKES.map((m) => m.id).sort())
    for (const card of cards) {
      const mistake = RUNNABLE.find((m) => m.id === card.id)!
      expect(within(card).getByRole('heading', { name: mistake.title })).toBeInTheDocument()
      if (mistake.demoId) expect(within(card).getByRole('button', { name: `Run the demo: ${mistake.title}` })).toBeEnabled()
      else expect(within(card).getByRole('button', { name: /demo coming/i })).toBeDisabled()
      expect(within(card).queryByText('Detected by the visualizer') !== null).toBe(mistake.detected)
    }
  })

  it('runs the demo of a mistake that has one', async () => {
    renderPage('mistakes', vi.fn(), RUNNABLE)
    await userEvent.click(screen.getByRole('button', { name: `Run the demo: ${RUNNABLE[0]!.title}` }))
    expect(start).toHaveBeenCalledWith(expect.objectContaining({ id: 'nested' }))
  })

  it('scrolls to the mistake named in the link', () => {
    const scrolled: string[] = []
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this.id)
    }
    try {
      window.location.hash = '#LEAKED_COROUTINE'
      renderPage('mistakes', vi.fn(), RUNNABLE)
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
      const { rerender } = renderPage('mistakes', vi.fn(), RUNNABLE)
      useScenariosMock.mockReturnValue({ data: { scenarios: all }, isLoading: false, error: null, refetch })
      rerender(
        <HeroUIProvider>
          <GalleryPage group="mistakes" onGroupChange={vi.fn()} mistakes={RUNNABLE} />
        </HeroUIProvider>,
      )
      expect(scrolled).toContain('WRONG_DISPATCHER')
    } finally {
      Element.prototype.scrollIntoView = original
    }
  })

  it('shows the level and tags of a demo that has a guide', () => {
    renderPage('basics')
    const nested = screen.getAllByTestId('demo-card').find((c) => c.getAttribute('data-demo-id') === 'nested')!
    expect(within(nested).getByText(GUIDES.nested!.level)).toBeInTheDocument()
    for (const tag of GUIDES.nested!.tags) expect(within(nested).getByText(tag)).toBeInTheDocument()
  })

  it('shows no level for a demo without a guide', () => {
    withUnguidedDemo()
    renderPage('labs')
    expect(screen.getAllByTestId('demo-card').length).toBeGreaterThan(0)
    expect(screen.queryByText(/^(beginner|intermediate|advanced)$/)).toBeNull()
  })

  it.each(['real-life', 'basics', 'channels', 'flow', 'patterns', 'sync-mutex', 'sync-semaphore', 'combined'])('links every %s card to its How it works page', (group) => {
    renderPage(group)
    for (const s of all.filter((d) => d.group === group)) {
      expect(screen.getByRole('link', { name: `How it works: ${s.name}` })).toHaveAttribute('href', `/gallery/${demoSlug(s.id)}`)
    }
  })

  it('shows keyboard focus on a How it works link', () => {
    renderPage('basics')
    const link = screen.getByRole('link', { name: /^How it works: Nested/ })
    // HeroUI's button classes hide the outline and expect data-focus-visible, which a router Link never sets.
    expect(link.className).toMatch(/focus-visible:outline-/)
  })

  it('has no How it works link for a demo without a guide', () => {
    withUnguidedDemo()
    renderPage('labs')
    expect(screen.getAllByTestId('demo-card').length).toBeGreaterThan(0)
    expect(screen.queryByRole('link', { name: /how it works/i })).toBeNull()
  })
})

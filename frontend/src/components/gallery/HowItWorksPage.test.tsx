import { describe, it, expect, vi, beforeEach } from 'vitest'
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

// A plain anchor whose href shows where the router would go.
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    children,
    to,
    params,
    search,
    hash,
    ...rest
  }: {
    children: React.ReactNode
    to: string
    params?: Record<string, string>
    search?: Record<string, string>
    hash?: string
  } & Record<string, unknown>) => {
    let path = to
    for (const [key, value] of Object.entries(params ?? {})) path = path.replace(`$${key}`, value)
    const query = search ? `?${new URLSearchParams(search)}` : ''
    return (
      <a href={`${path}${query}${hash ? `#${hash}` : ''}`} {...rest}>
        {children}
      </a>
    )
  },
}))

import { HowItWorksPage } from './HowItWorksPage'
import { GUIDES } from '@/content/gallery/guides'
import { willSeeFor } from '@/content/gallery/will-see'
import { ApiError } from '@/lib/api-errors'
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'

const all = catalog.scenarios
const nested = all.find((s) => s.id === 'nested')!
const refetch = vi.fn()

beforeEach(() => {
  vi.clearAllMocks()
  startState.preparing = null
  startState.startError = null
  useScenariosMock.mockReturnValue({ data: { scenarios: all }, isLoading: false, error: null, refetch })
})

const renderPage = (slug: string) =>
  render(
    <HeroUIProvider>
      <HowItWorksPage slug={slug} />
    </HeroUIProvider>,
  )

describe('HowItWorksPage', () => {
  it('shows the breadcrumb, the title, the level and the duration', () => {
    renderPage('nested')
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Gallery' })).toHaveAttribute('href', '/gallery')
    expect(within(crumbs).getByRole('link', { name: 'Basics' })).toHaveAttribute('href', '/gallery?group=basics')
    expect(screen.getByRole('heading', { level: 1, name: nested.name })).toBeInTheDocument()
    expect(screen.getByText(GUIDES.nested!.level)).toBeInTheDocument()
    expect(screen.getByText(nested.duration!)).toBeInTheDocument()
  })

  it('has its sections in order', () => {
    renderPage('nested')
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(headings).toEqual(['The story', 'The code', 'Step by step', 'What you will see', 'Key idea', 'Common mistake'])
    expect(screen.getByText(GUIDES.nested!.story)).toBeInTheDocument()
    expect(screen.getByText(GUIDES.nested!.keyIdea)).toBeInTheDocument()
  })

  it('shows the code and numbers the steps 1…n', () => {
    renderPage('nested')
    expect(screen.getByTestId('guide-code').textContent).toBe(GUIDES.nested!.code)
    const steps = within(screen.getByRole('list', { name: 'Step by step' })).getAllByRole('listitem')
    expect(steps).toHaveLength(GUIDES.nested!.steps.length)
    GUIDES.nested!.steps.forEach((step, i) => {
      expect(steps[i]).toHaveTextContent(String(i + 1))
      expect(steps[i]).toHaveTextContent(step.title)
      expect(steps[i]).toHaveTextContent(step.text)
    })
  })

  it('pictures every coroutine with its state, and names the moment', () => {
    renderPage('nested')
    const picture = willSeeFor('nested')!
    expect(screen.getByText(picture.moment)).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: 'What you will see' })).getAllByRole('listitem')
    expect(rows).toHaveLength(picture.coroutines.length)
    picture.coroutines.forEach((c, i) => {
      expect(rows[i]).toHaveTextContent(c.label)
      expect(rows[i]).toHaveTextContent(c.state.toLowerCase().replaceAll('_', ' '))
    })
  })

  it('nests each pictured coroutine inside its parent, so the tree is not flat for screen readers', () => {
    renderPage('nested')
    const row = (label: string) => screen.getByText(label, { selector: 'span' }).closest('li')!
    expect(row('fetch-review-photos').parentElement!.closest('li')).toBe(row('fetch-reviews'))
    expect(row('fetch-reviews').parentElement!.closest('li')).toBe(row('load-reviews'))
    expect(row('load-reviews').parentElement!.closest('li')).toBe(row('load-product-page'))
    expect(row('load-product-page').parentElement!.closest('li')).toBeNull()
  })

  it('links the common mistake to its card in the Gallery', () => {
    renderPage('nested')
    expect(screen.getByRole('link', { name: /launching in globalscope/i })).toHaveAttribute(
      'href',
      '/gallery?group=mistakes#GLOBAL_SCOPE_USAGE',
    )
  })

  it('runs the example', async () => {
    renderPage('nested')
    await userEvent.click(screen.getByRole('button', { name: 'Run this example' }))
    expect(start).toHaveBeenCalledWith(nested)
  })

  it('shows why the example could not start', () => {
    startState.startError = { scenario: nested.name, error: new ApiError(429, 'rate limited') }
    renderPage('nested')
    expect(screen.getByRole('alert')).toHaveTextContent(`Could not start “${nested.name}”`)
  })

  it('finds a demo whose id has slashes by its slug', () => {
    renderPage('order-processing')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('No guide for this example yet')
  })

  it('offers Run for a demo that has no guide yet', async () => {
    renderPage('order-processing')
    expect(screen.getByRole('link', { name: /back to the gallery/i })).toHaveAttribute('href', '/gallery')
    await userEvent.click(screen.getByRole('button', { name: 'Run this example' }))
    expect(start).toHaveBeenCalledWith(all.find((s) => s.id === 'order-processing'))
  })

  it('has no Run for an unknown slug', () => {
    renderPage('no-such-demo')
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('No guide for this example yet')
    expect(screen.queryByRole('button', { name: 'Run this example' })).toBeNull()
    expect(screen.getByRole('link', { name: /back to the gallery/i })).toBeInTheDocument()
  })

  it('shows a skeleton while the demo list loads', () => {
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: true, error: null, refetch })
    renderPage('nested')
    expect(screen.getByTestId('guide-skeleton')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull()
  })

  it('offers Retry when the demo list fails', async () => {
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: false, error: new ApiError(503, 'down'), refetch })
    renderPage('nested')
    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(refetch).toHaveBeenCalled()
  })
})

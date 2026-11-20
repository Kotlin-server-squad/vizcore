import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import type { SessionInfo } from '@/types/api'
// What GET /api/scenarios serves; a backend test keeps this copy equal to it.
import catalog from '@/test/demo-catalog.json'

// Router is mocked so the route component renders without a real router context.
// `Link` must tolerate a render-prop child — Layout's nav uses that form.
const navigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => navigate,
  createFileRoute: () => (opts: unknown) => opts,
  Link: ({ children, to }: { children: unknown; to?: string }) => (
    <a href={to}>
      {typeof children === 'function'
        ? (children as (s: { isActive: boolean }) => React.ReactNode)({ isActive: false })
        : (children as React.ReactNode)}
    </a>
  ),
}))

const useSessionsMock = vi.fn()
vi.mock('@/hooks/use-sessions', () => ({
  useSessions: () => useSessionsMock(),
}))

const useScenariosMock = vi.fn()
vi.mock('@/hooks/use-scenarios', () => ({ useScenarios: () => useScenariosMock() }))

const start = vi.fn()
const startState = { preparing: null as string | null, preparingVariant: null as string | null, startError: null as unknown }
vi.mock('@/hooks/use-start-demo', () => ({
  useStartDemo: () => ({ start, ...startState }),
}))

// The wizard is a heavy, separately-tested surface; stub it so this test stays
// about the home route's own content.
const wizard = vi.fn((_: { isOpen: boolean }) => null)
vi.mock('@/components/connect/ConnectWizard', () => ({
  ConnectWizard: (props: { isOpen: boolean }) => wizard(props),
}))
vi.mock('@/components/sessions/NewDemoSessionModal', () => ({
  NewDemoSessionModal: () => null,
}))
vi.mock('@/components/sessions/CompareOverlay', () => ({
  CompareOverlay: () => null,
}))

import { HomePage } from './index'
import { ORDER_STORY, ORDER_STORY_DEMO } from '@/content/home/order-story'
import { ApiError } from '@/lib/api-errors'
import { FRONT_DOOR_APPS_KEY } from '@/lib/front-door-memory'

const liveSession: SessionInfo = { sessionId: 'order-service-1', coroutineCount: 3 }
const demoSession: SessionInfo = { sessionId: 'scenario-Order Processing-1728383000000', coroutineCount: 6 }
const order = catalog.scenarios.find((s) => s.id === 'order-processing')!
const realLife = catalog.scenarios.filter((s) => s.category === 'realistic')

/** Whether `a` comes before `b` on the page. */
const precedes = (a: Element, b: Element) =>
  Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  startState.preparing = null
  startState.preparingVariant = null
  startState.startError = null
  useSessionsMock.mockReturnValue({ data: [], isLoading: false })
  useScenariosMock.mockReturnValue({ data: catalog, isLoading: false, error: null, refetch: vi.fn() })
})

describe('root route: the front door', () => {
  it('leads with what vizcore shows, not with "No app connected"', () => {
    render(<HomePage />)
    expect(
      screen.getByRole('heading', { level: 1, name: 'See what your Kotlin coroutines are doing.' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Who started whom, who waits for what/)).toBeInTheDocument()
    expect(screen.getByText('About 15 seconds. Nothing to install. Runs on this server.')).toBeInTheDocument()
    expect(screen.queryByText('No app connected')).not.toBeInTheDocument()
    expect(screen.queryByText(/vizcore-client/)).not.toBeInTheDocument()
  })

  it('"Watch a checkout run" starts the order demo with one click, its usual ending', () => {
    render(<HomePage />)
    fireEvent.click(screen.getByRole('button', { name: 'Watch a checkout run' }))
    expect(start).toHaveBeenCalledTimes(1)
    expect(start).toHaveBeenCalledWith(order)
  })

  it('"Watch a checkout run" works before the demo list is in, and when it failed', () => {
    useScenariosMock.mockReturnValue({ data: undefined, isLoading: true, error: null, refetch: vi.fn() })
    const { unmount } = render(<HomePage />)
    fireEvent.click(screen.getByRole('button', { name: 'Watch a checkout run' }))
    expect(start).toHaveBeenLastCalledWith(ORDER_STORY_DEMO)
    unmount()

    useScenariosMock.mockReturnValue({ data: undefined, isLoading: false, error: new ApiError(503, 'x'), refetch: vi.fn() })
    render(<HomePage />)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Watch a checkout run' }))
    expect(start).toHaveBeenCalledTimes(2)
  })

  it('starts nothing by itself', () => {
    render(<HomePage />)
    expect(start).not.toHaveBeenCalled()
  })

  it('"Connect your app" opens the connect wizard', () => {
    render(<HomePage />)
    expect(wizard).toHaveBeenLastCalledWith(expect.objectContaining({ isOpen: false }))
    fireEvent.click(screen.getByRole('button', { name: 'Connect your app' }))
    expect(wizard).toHaveBeenLastCalledWith(expect.objectContaining({ isOpen: true }))
  })

  it('draws the order story as a still picture: one bar per coroutine, from content', () => {
    render(<HomePage />)
    expect(screen.getByText(ORDER_STORY.title)).toBeInTheDocument()
    const bars = screen.getAllByTestId('order-story-bar')
    expect(bars).toHaveLength(ORDER_STORY.bars.length)
    expect(within(bars[0]!).getByText('processOrder')).toBeInTheDocument()
    expect(bars[0]).toHaveTextContent('runs from 0 s to 14.5 s, from 11.5 s only waiting for its children')
    expect(bars[3]).toHaveTextContent('runs from 6 s to 10 s')
  })

  it('shows the three Real life demos, each with Run and its second ending, as the Gallery has them', () => {
    render(<HomePage />)
    expect(screen.getByRole('heading', { level: 2, name: 'Three real-life stories' })).toBeInTheDocument()
    const cards = screen.getAllByTestId('demo-card')
    expect(cards.map((c) => c.getAttribute('data-demo-id'))).toEqual(realLife.map((s) => s.id))
    expect(realLife).toHaveLength(3)

    const endings = ['Play: card declined', 'Play: email retried', 'Play: slow API cancelled']
    const variants = ['card-declined', 'email-retried', 'slow-api-cancelled']
    realLife.forEach((scenario, i) => {
      fireEvent.click(screen.getByRole('button', { name: `Run ${scenario.name} (Real life)` }))
      expect(start).toHaveBeenLastCalledWith(scenario)
      fireEvent.click(screen.getByRole('button', { name: `${endings[i]}, ${scenario.name} (Real life)` }))
      expect(start).toHaveBeenLastCalledWith(scenario, variants[i])
    })
    expect(screen.getByRole('link', { name: 'All examples in the Gallery' })).toHaveAttribute('href', '/gallery')
  })

  it('one start at a time: while a story starts, the hero and the other cards wait', () => {
    startState.preparing = 'user-registration'
    render(<HomePage />)
    expect(screen.getByRole('button', { name: 'Watch a checkout run' })).toBeDisabled()
    expect(screen.getByRole('button', { name: `Run ${order.name} (Real life)` })).toBeDisabled()
  })

  it('says why a demo could not start', () => {
    startState.startError = { scenario: order.name, error: new ApiError(500, 'session store unavailable') }
    render(<HomePage />)
    expect(screen.getByRole('alert')).toHaveTextContent(/could not start/i)
  })

  it('leaves the stories out for a server that lists no Real life demo', () => {
    useScenariosMock.mockReturnValue({
      data: { scenarios: catalog.scenarios.filter((s) => s.category !== 'realistic') },
      isLoading: false,
      error: null,
      refetch: vi.fn(),
    })
    render(<HomePage />)
    expect(screen.queryByRole('heading', { name: /real-life stories/i })).not.toBeInTheDocument()
    expect(screen.queryByTestId('demo-card')).not.toBeInTheDocument()
  })

  it('with no sessions, keeps the list below the stories, as a quiet note', () => {
    render(<HomePage />)
    const stories = screen.getByRole('heading', { name: 'Three real-life stories' })
    const sessions = screen.getByRole('heading', { name: 'Sessions' })
    expect(precedes(stories, sessions)).toBe(true)
    expect(screen.getByText('None connected yet. A connected app shows up here by itself.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'How to connect in 3 steps' })).toBeInTheDocument()
  })

  it('with an app in the list, puts the list right under a smaller hero, above the stories', () => {
    useSessionsMock.mockReturnValue({ data: [liveSession], isLoading: false })
    render(<HomePage />)
    expect(screen.getByText('order-service-1')).toBeInTheDocument()
    const hero = screen.getByRole('heading', { level: 1 })
    const sessions = screen.getByRole('heading', { name: 'Sessions' })
    const stories = screen.getByRole('heading', { name: 'Three real-life stories' })
    expect(precedes(hero, sessions)).toBe(true)
    expect(precedes(sessions, stories)).toBe(true)
    // The picture gives way to the list; both ways in stay.
    expect(screen.queryByTestId('order-story-bar')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Watch a checkout run' })).toBeInTheDocument()
  })

  // With auth off the list is the whole server's: other visitors' demo
  // sessions must not take the picture away from a first-time visitor.
  it('with only demo sessions in the list, keeps the picture and the list below the stories', () => {
    useSessionsMock.mockReturnValue({ data: [demoSession], isLoading: false })
    render(<HomePage />)
    expect(screen.getAllByTestId('order-story-bar')).toHaveLength(ORDER_STORY.bars.length)
    const stories = screen.getByRole('heading', { name: 'Three real-life stories' })
    const sessions = screen.getByRole('heading', { name: 'Sessions' })
    expect(precedes(stories, sessions)).toBe(true)
    expect(screen.getByText('Demo scenarios')).toBeInTheDocument()
  })

  it('while the list loads, lays itself out as it was last time, so it does not jump', () => {
    useSessionsMock.mockReturnValue({ data: [liveSession], isLoading: false })
    const { unmount } = render(<HomePage />)
    expect(localStorage.getItem(FRONT_DOOR_APPS_KEY)).toBe('1')
    unmount()

    useSessionsMock.mockReturnValue({ data: undefined, isLoading: true, isPending: true })
    const loading = render(<HomePage />)
    expect(screen.queryByTestId('order-story-bar')).not.toBeInTheDocument()
    expect(
      precedes(screen.getByRole('heading', { name: 'Sessions' }), screen.getByRole('heading', { name: 'Three real-life stories' })),
    ).toBe(true)
    loading.unmount()

    // The app is gone: the full front door is back, and stays for the next visit.
    useSessionsMock.mockReturnValue({ data: [], isLoading: false })
    render(<HomePage />)
    expect(screen.getAllByTestId('order-story-bar')).toHaveLength(ORDER_STORY.bars.length)
    expect(localStorage.getItem(FRONT_DOOR_APPS_KEY)).toBeNull()
  })

  it('a first visit shows the full front door while the list loads', () => {
    useSessionsMock.mockReturnValue({ data: undefined, isLoading: true, isPending: true })
    render(<HomePage />)
    expect(screen.getAllByTestId('order-story-bar')).toHaveLength(ORDER_STORY.bars.length)
  })

  it('while a demo starts, the list does not offer to start another', () => {
    useSessionsMock.mockReturnValue({ data: [liveSession], isLoading: false })
    startState.preparing = 'order-processing'
    const { unmount } = render(<HomePage />)
    expect(screen.getByRole('button', { name: /new demo session/i })).toBeDisabled()
    unmount()

    useSessionsMock.mockReturnValue({ data: [], isLoading: false })
    render(<HomePage />)
    expect(screen.getByRole('button', { name: 'Run a demo scenario instead' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'How to connect in 3 steps' })).toBeEnabled()
  })

  it('offers the re-hosted demo and compare actions alongside Connect', () => {
    render(<HomePage />)
    expect(screen.getByRole('button', { name: /new demo session/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^compare$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect' })).toBeInTheDocument()
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { SessionMetrics } from './SessionMetrics'
import type { MetricsResponse } from '@/types/api'

const useSessionMetricsMock = vi.fn()

vi.mock('@/hooks/use-session-metrics', () => ({
  useSessionMetrics: (...args: unknown[]) => useSessionMetricsMock(...args),
}))

function metrics(overrides: Partial<MetricsResponse> = {}): MetricsResponse {
  return {
    active: 3,
    peak: 7,
    throughputPerSec: 12.5,
    dispatcherUtilization: { Default: 2, IO: 1 },
    leaks: [],
    leakThresholdMs: 30_000,
    ...overrides,
  }
}

const slice = (over: Partial<NonNullable<MetricsResponse['byOrigin']>['app']> = {}) => ({
  captured: 0,
  active: 0,
  peak: 0,
  throughputPerSec: 0,
  dispatcherUtilization: {},
  longLived: 0,
  ...over,
})

describe('SessionMetrics', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders Active / Peak / Throughput from the metrics snapshot', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText('Session metrics')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.getByText('Peak')).toBeInTheDocument()
    expect(screen.getByText('7')).toBeInTheDocument()
    expect(screen.getByText('Throughput')).toBeInTheDocument()
    // throughput numeral + the /s suffix
    expect(screen.getByText('12.5')).toBeInTheDocument()
    expect(screen.getByText('/s')).toBeInTheDocument()
  })

  it('keeps each tile\'s label and value apart: a term and its value, with a gap', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics({ active: 0, peak: 6 }), isLoading: false })

    render(<SessionMetrics sessionId="s-1" />)

    const terms = screen.getAllByRole('term')
    expect(terms.map(t => t.textContent)).toEqual(['Active', 'Peak', 'Throughput'])
    expect(terms[0]!.nextElementSibling).toHaveTextContent(/^0$/)
    expect(terms[1]!.nextElementSibling).toHaveTextContent(/^6$/)
    // The tile's row has a gap, so the value never touches its label ("Active0").
    expect(terms[0]!.parentElement!.className).toMatch(/\bgap-3\b/)
  })

  it('shows "—" for throughput when no events have arrived yet', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({ throughputPerSec: 0 }),
      isLoading: false,
    })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText('—')).toBeInTheDocument()
    expect(screen.queryByText('/s')).toBeNull()
  })

  it('renders the dispatcher utilization label', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText('Dispatcher utilization')).toBeInTheDocument()
  })

  it('renders a loading state while the query is loading', () => {
    useSessionMetricsMock.mockReturnValue({ data: undefined, isLoading: true })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText(/Loading metrics/i)).toBeInTheDocument()
  })

  it('observes the metrics query without polling, forwarding sessionId/enabled (#124)', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })

    render(<SessionMetrics sessionId="s-1" enabled={false} />)

    expect(useSessionMetricsMock).toHaveBeenCalledWith('s-1', false, false)
  })

  it('reflows the tiles into a horizontal flex strip (Delta L1, not a 2-col grid)', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })

    const { container } = render(<SessionMetrics sessionId="s-1" />)

    // The tile container is a wrapping flex strip, not the old md:grid-cols-2 card grid.
    expect(container.querySelector('.flex.flex-wrap')).not.toBeNull()
    expect(container.querySelector('.md\\:grid-cols-2')).toBeNull()
  })

  it('renders metric numerals at font-semibold (2-weight scale), never font-bold', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })

    const { container } = render(<SessionMetrics sessionId="s-1" />)

    // No metric numeral uses font-bold (700); the live contract is a 2-weight scale.
    expect(container.querySelector('.font-bold')).toBeNull()

    // Active numeral keeps text-primary AND is font-semibold.
    const active = screen.getByText('3')
    expect(active).toHaveClass('font-semibold')
    expect(active).toHaveClass('text-primary')

    // Peak + throughput numerals are font-semibold too.
    expect(screen.getByText('7')).toHaveClass('font-semibold')
    expect(screen.getByText('12.5')).toHaveClass('font-semibold')
  })

  it('renders the throughput em-dash at font-semibold when zero', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({ throughputPerSec: 0 }),
      isLoading: false,
    })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText('—')).toHaveClass('font-semibold')
  })

  it('has no leak card: long-lived coroutines are reached through the Problems tab and the filter (Part 7, T-5)', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({ leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }] }),
      isLoading: false,
    })
    render(<SessionMetrics sessionId="s-1" />)
    expect(screen.queryByText('Potential leaks')).toBeNull()
    expect(screen.queryByText(/potential leak/)).toBeNull()
  })

  it("shows the app's own figures, vizcore's connection left out (T-3)", () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        active: 9,
        peak: 20,
        byOrigin: {
          app: slice({ captured: 5, active: 4, peak: 6, throughputPerSec: 3, dispatcherUtilization: { IO: 4 } }),
          vizcore: slice({ captured: 3, active: 3, peak: 3 }),
          unknown: slice(),
          untracked: 0,
        },
      }),
      isLoading: false,
    })
    render(<SessionMetrics sessionId="s-1" />)
    expect(screen.getByTestId('metrics-scope')).toHaveTextContent("Your app's coroutines; vizcore's own connection is left out.")
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
    expect(screen.getByText('IO: 4')).toBeInTheDocument()
  })

  it('shows every captured coroutine, and says so, when origins are not reported', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        byOrigin: { app: slice(), vizcore: slice(), unknown: slice({ captured: 8, active: 3 }), untracked: 0 },
      }),
      isLoading: false,
    })
    render(<SessionMetrics sessionId="s-1" />)
    expect(screen.getByTestId('metrics-scope')).toHaveTextContent(
      "All captured coroutines (origin not available: vizcore's own connection may be included).",
    )
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('qualifies figures rebuilt from retained events only (T-3)', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({ retainedOnly: true, oldestRetainedAppNanos: 4_000_000_000 }),
      isLoading: false,
    })
    render(<SessionMetrics sessionId="s-1" captureStartAppNanos={1_000_000_000} />)
    expect(screen.getByTestId('metrics-retained')).toHaveTextContent('Peak and throughput: in retained events since 3.0 s into the capture.')
  })

  it('adds no caption to metrics it is given (a replay moment, Part 8)', () => {
    render(<SessionMetrics sessionId="s-1" data={metrics()} />)
    expect(screen.queryByTestId('metrics-scope')).toBeNull()
  })
})

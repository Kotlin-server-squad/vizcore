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
    expect(screen.getByText('Potential leaks')).toBeInTheDocument()
  })

  it('renders a loading state while the query is loading', () => {
    useSessionMetricsMock.mockReturnValue({ data: undefined, isLoading: true })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText(/Loading metrics/i)).toBeInTheDocument()
  })

  it('forwards sessionId/isLive/enabled to the metrics hook', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })

    render(<SessionMetrics sessionId="s-1" isLive enabled={false} />)

    expect(useSessionMetricsMock).toHaveBeenCalledWith('s-1', true, false)
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

  it('surfaces leaks through the LeakList badge', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }],
      }),
      isLoading: false,
    })

    render(<SessionMetrics sessionId="s-1" />)

    expect(screen.getByText('1 potential leak')).toBeInTheDocument()
  })

  it('keeps the internal leak Card by default (showLeaks omitted = back-compat)', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }],
      }),
      isLoading: false,
    })

    render(<SessionMetrics sessionId="s-1" />)

    // The "Potential leaks" Card header renders for every existing call site.
    expect(screen.getByText('Potential leaks')).toBeInTheDocument()
    expect(screen.getByText('1 potential leak')).toBeInTheDocument()
  })

  it('hides the internal leak Card when showLeaks={false} (tiles-only strip)', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }],
      }),
      isLoading: false,
    })

    render(<SessionMetrics sessionId="s-1" showLeaks={false} />)

    // Strip is tiles-only — no leak Card, no leak badge (the dock owns the leak mount).
    expect(screen.queryByText('Potential leaks')).toBeNull()
    expect(screen.queryByText('1 potential leak')).toBeNull()
    // Tiles still render.
    expect(screen.getByText('Active')).toBeInTheDocument()
  })
})

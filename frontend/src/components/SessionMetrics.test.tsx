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
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { MetricsResponse } from '@/types/api'
import { LiveDockPanel } from './LiveDockPanel'

// The dock sources leak data through useSessionMetrics (PD-02). Mock the hook so
// the metric-tile strip (SessionMetrics, which also calls it) and the dock's own
// inline LeakList both render off a single deterministic fixture.
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

describe('LiveDockPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useSessionMetricsMock.mockReturnValue({ data: metrics(), isLoading: false })
  })

  it('renders the docked panel with the locked top accent border + min-height', () => {
    const { container } = render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    // Sketch .ide-dock: top accent border + min-h-[200px] dock surface.
    expect(container.querySelector('.border-t-2.border-primary')).not.toBeNull()
    expect(container.querySelector('.min-h-\\[200px\\]')).not.toBeNull()
  })

  it('renders the LIVE pill + the four metric-tile labels in the header strip', () => {
    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    // LivePill (driven by streamEnabled) shows in the dock header.
    expect(screen.getByText('LIVE')).toBeInTheDocument()
    expect(screen.getByText('~150ms poll')).toBeInTheDocument()

    // The reflowed SessionMetrics tiles (un-buried from the Threads tab).
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('Peak')).toBeInTheDocument()
    expect(screen.getByText('Throughput')).toBeInTheDocument()
    expect(screen.getByText('Dispatcher utilization')).toBeInTheDocument()
  })

  it('renders the metric strip tiles-only — NO leak Card in the header strip', () => {
    // Even with leaks present, the header strip suppresses SessionMetrics's
    // internal leak Card (showLeaks={false}); the only leak surface is the
    // single inline LeakList in the left column (asserted below).
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }],
      }),
      isLoading: false,
    })

    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    // The "Potential leaks" Card header (SessionMetrics internal) must NOT render.
    expect(screen.queryByText('Potential leaks')).toBeNull()
  })

  it('renders the left list slot and the right source slot', () => {
    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
        sourcePanel={<div data-testid="source-panel" />}
      />,
    )

    expect(screen.getByTestId('live-list')).toBeInTheDocument()
    expect(screen.getByTestId('source-panel')).toBeInTheDocument()
  })

  it('renders a muted placeholder in the right column when sourcePanel is absent', () => {
    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    expect(screen.getByText(/Select a coroutine to view its source/i)).toBeInTheDocument()
  })

  it('mounts the inline LeakList EXACTLY ONCE in the left column for a multi-leak fixture', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        leaks: [
          { coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 },
          { coroutineId: 'dp-2', label: 'pollLoop', aliveMs: 51_000 },
        ],
      }),
      isLoading: false,
    })

    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    // The LeakList count badge appears exactly once — a single leak-list mount.
    const badges = screen.getAllByText('2 potential leaks')
    expect(badges).toHaveLength(1)
  })

  it('renders leaks with amber/warning tokens, never danger', () => {
    useSessionMetricsMock.mockReturnValue({
      data: metrics({
        leaks: [{ coroutineId: 'dp-1', label: 'fetchUser', aliveMs: 42_000 }],
      }),
      isLoading: false,
    })

    const { container } = render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    // Amber present, danger absent in the leak surface.
    expect(container.querySelector('.text-warning')).not.toBeNull()
    expect(container.querySelector('.text-danger')).toBeNull()
    expect(container.querySelector('.bg-danger')).toBeNull()
  })

  it('renders no LeakList when there are no leaks', () => {
    useSessionMetricsMock.mockReturnValue({ data: metrics({ leaks: [] }), isLoading: false })

    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    expect(screen.queryByText(/potential leak/i)).toBeNull()
  })

  it('keeps the metric numeral at text-lg (never text-2xl)', () => {
    const { container } = render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly={false}
        liveList={<div data-testid="live-list" />}
      />,
    )

    // The reflowed tiles keep the shipped numeral size (PD-04).
    expect(container.querySelector('.text-lg.font-semibold')).not.toBeNull()
    expect(container.querySelector('.text-2xl')).toBeNull()
  })

  it('passes !readOnly as the enabled flag to the metrics hook', () => {
    render(
      <LiveDockPanel
        sessionId="s-1"
        streamEnabled
        readOnly
        liveList={<div data-testid="live-list" />}
      />,
    )

    // The dock's own leak-data call: useSessionMetrics(sessionId, streamEnabled, !readOnly).
    expect(useSessionMetricsMock).toHaveBeenCalledWith('s-1', true, false)
  })
})

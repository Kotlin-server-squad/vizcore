import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

const metrics = vi.fn()
vi.mock('@/hooks/use-session-metrics', () => ({ useSessionMetrics: (...args: unknown[]) => metrics(...args) }))
vi.mock('../SessionMetrics', () => ({ SessionMetrics: () => <div data-testid="session-metrics" /> }))

import { SessionSummary } from './SessionSummary'

describe('SessionSummary — the Inspector with nothing selected (T-3)', () => {
  it('invites a selection, says what was captured and shows the metric tiles', () => {
    metrics.mockReturnValue({ data: { leaks: [], leakThresholdMs: 60_000 } })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={7} eventCount={54} />)
    expect(screen.getByText('Select a coroutine to inspect it')).toBeInTheDocument()
    expect(screen.getByTestId('session-captured')).toHaveTextContent('7 coroutines and 54 events captured')
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it('lists long-lived coroutines once, with their age in words', () => {
    metrics.mockReturnValue({ data: { leaks: [{ coroutineId: 'c-1', label: 'poll', aliveMs: 46_000 }], leakThresholdMs: 30_000 } })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={1} eventCount={2} />)
    expect(screen.getByText(/poll · alive 46\.0 s/)).toBeInTheDocument()
  })

  it('explains sampling where nothing has been captured yet (H-4)', () => {
    metrics.mockReturnValue({ data: undefined })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={0} eventCount={0} />)
    expect(screen.getByTestId('session-captured')).toHaveTextContent('Nothing captured yet')
    expect(screen.getByTestId('session-captured')).toHaveTextContent('on a poll')
  })

  it('passes the protected-fetch flag through (no /metrics without a Bearer)', () => {
    metrics.mockReturnValue({ data: undefined })
    render(<SessionSummary sessionId="s-1" enabled={false} coroutineCount={0} eventCount={0} />)
    expect(metrics).toHaveBeenCalledWith('s-1', false, false)
  })
})

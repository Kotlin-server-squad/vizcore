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
    expect(screen.getByTestId('session-captured')).not.toHaveTextContent('Part 7')
    expect(screen.getAllByTestId('session-metrics')).toHaveLength(1)
  })

  it("splits what was captured by origin, from the session health (T-3)", () => {
    metrics.mockReturnValue({ data: undefined })
    render(
      <SessionSummary sessionId="s-1" enabled coroutineCount={9} eventCount={54} counts={{ app: 5, vizcore: 3, unknown: 1, untracked: 0 }} />,
    )
    expect(screen.getByTestId('session-captured')).toHaveTextContent(
      "9 coroutines and 54 events captured: 5 your app's · 3 vizcore's own connection · 1 origin unknown",
    )
    expect(screen.queryByText(/alive/)).toBeNull()
  })

  it('explains sampling where nothing has been captured yet (H-4)', () => {
    metrics.mockReturnValue({ data: undefined })
    render(<SessionSummary sessionId="s-1" enabled coroutineCount={0} eventCount={0} />)
    expect(screen.getByTestId('session-captured')).toHaveTextContent('Nothing captured yet')
    expect(screen.getByTestId('session-captured')).toHaveTextContent('on a poll')
  })

})

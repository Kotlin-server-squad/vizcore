import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ConnectionFacts } from './ConnectionFacts'

const at = (h: number, m: number, s: number) => Date.UTC(2026, 10, 7, h, m, s)

afterEach(() => vi.restoreAllMocks())

describe('ConnectionFacts (S-3, T-2)', () => {
  it('says the app is connected, when its last data was observed on its own clock, and when the session started', () => {
    render(
      <ConnectionFacts
        clock={{ timeZone: 'UTC' }}
        session={{ appConnected: true, startedAtEpochMs: at(13, 0, 5), lastIngestEpochMs: at(14, 2, 40), lastWatermark: { appNanos: 1, appEpochMs: at(14, 2, 31), receivedEpochMs: at(14, 2, 32) } }}
      />,
    )
    expect(screen.getByTestId('connection')).toHaveTextContent('App connected')
    expect(screen.getByTestId('connection-facts')).toHaveTextContent('Last data 14:02:31 · started 13:00:05')
  })

  it('falls back to the backend receipt time, labelled as such', () => {
    render(
      <ConnectionFacts
        clock={{ timeZone: 'UTC' }}
        session={{ appConnected: false, startedAtEpochMs: at(13, 0, 5), lastIngestEpochMs: at(14, 2, 40), lastWatermark: null }}
      />,
    )
    expect(screen.getByTestId('connection')).toHaveTextContent('App disconnected')
    expect(screen.getByTestId('connection-facts')).toHaveTextContent('Last data received 14:02:40 · started 13:00:05')
  })

  it('claims no last data before any, and never reads the browser clock', () => {
    const now = vi.spyOn(Date, 'now')
    render(<ConnectionFacts clock={{ timeZone: 'UTC' }} session={{ appConnected: true, startedAtEpochMs: at(13, 0, 5), lastIngestEpochMs: null, lastWatermark: null }} />)
    expect(screen.getByTestId('connection-facts')).toHaveTextContent('No data yet · started 13:00:05')
    expect(now).not.toHaveBeenCalled()
  })

  it('shows nothing for a server that reports no connection state', () => {
    const { container } = render(<ConnectionFacts session={{}} />)
    expect(container).toBeEmptyDOMElement()
  })
})

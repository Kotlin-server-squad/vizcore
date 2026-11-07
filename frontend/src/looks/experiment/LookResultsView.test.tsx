import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import type { LookResults, LookResultRow } from './results'
import { LookResultsView } from './LookResultsView'

function row(look: string, label: string, extra: Partial<LookResultRow> = {}): LookResultRow {
  return {
    look,
    label,
    weight: 25,
    inSplit: true,
    assigned: 0,
    chose: 0,
    visitorsSeen: 0,
    sessionsSeen: 0,
    medianTimeMs: null,
    switchAwayRate: null,
    switchedTo: {},
    votes: 0,
    averageRating: null,
    comments: [],
    ...extra,
  }
}

const results: LookResults = {
  enabled: true,
  storage: 'database',
  startedAt: Date.UTC(2026, 10, 3),
  day: 4,
  rule: { minVotesPerLook: 30, maxDays: 14, clearlyWorseSwitchAwayPoints: 10, tooCloseRating: 0.3 },
  decision: { status: 'RUNNING', text: 'Not decided yet: B · Calm has 12 of 30 votes, day 4 of 14.', winner: null, keep: [] },
  tooLittleData: true,
  warning: 'Too little data yet: these numbers can still change a lot.',
  looks: [
    row('classic', 'Classic', { assigned: 10, chose: 2, sessionsSeen: 30, medianTimeMs: 125_000, switchAwayRate: 0.25, switchedTo: { b: 2, c: 1 }, votes: 14, averageRating: 3.5 }),
    row('a', 'A · Compact', { weight: 0, inSplit: false }),
    row('b', 'B · Calm', {
      votes: 12,
      averageRating: 4.17,
      comments: [
        { rating: 5, comment: 'So calm', at: Date.UTC(2026, 10, 5) },
        { rating: 2, comment: 'Too pale', at: Date.UTC(2026, 10, 4) },
      ],
    }),
    row('c', 'C · Bold'),
  ],
}

describe('LookResultsView', () => {
  it('states the decision in plain English, and warns while there is too little data', () => {
    render(<LookResultsView results={results} />)
    expect(screen.getByRole('status')).toHaveTextContent('Not decided yet: B · Calm has 12 of 30 votes, day 4 of 14.')
    expect(screen.getByRole('alert')).toHaveTextContent('Too little data yet')
  })

  it('has one row per look with its numbers', () => {
    render(<LookResultsView results={results} />)
    const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(4)
    const classic = within(rows[0]!)
    expect(classic.getByRole('rowheader')).toHaveTextContent('Classic')
    expect(rows[0]).toHaveTextContent('10')
    expect(rows[0]).toHaveTextContent('2 m 5 s')
    expect(rows[0]).toHaveTextContent('25 %')
    expect(rows[0]).toHaveTextContent('to B · Calm 2, C · Bold 1')
    expect(rows[0]).toHaveTextContent('3.5')
    expect(rows[1]).toHaveTextContent('not in the split')
    expect(rows[3]).toHaveTextContent('—')
  })

  it('lists the comments per look, newest first, with their rating', () => {
    render(<LookResultsView results={results} />)
    const b = screen.getByRole('region', { name: 'Comments on B · Calm' })
    const items = within(b).getAllByRole('listitem')
    expect(items.map(i => i.textContent)).toEqual([expect.stringContaining('So calm'), expect.stringContaining('Too pale')])
    expect(items[0]).toHaveTextContent('5 / 5')
  })

  it('says when the experiment is off and when the data lives only in memory', () => {
    render(<LookResultsView results={{ ...results, enabled: false, storage: 'memory' }} />)
    expect(screen.getByText(/the experiment is off/i)).toBeInTheDocument()
    expect(screen.getByText(/kept in memory/i)).toBeInTheDocument()
  })

  it('names the rule it applies', () => {
    render(<LookResultsView results={results} />)
    expect(screen.getByText(/at least 30 ratings/)).toBeInTheDocument()
    expect(screen.getByText(/14 days/)).toBeInTheDocument()
  })
})

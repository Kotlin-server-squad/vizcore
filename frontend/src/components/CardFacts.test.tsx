import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CardFacts, CardStateContext } from './CardFacts'
import { CardFactsContext } from '@/lib/card-facts'
import { CoroutineState, type CoroutineNode } from '@/types/api'

const node = (over: Partial<CoroutineNode> = {}): CoroutineNode => ({
  id: 'c', jobId: 'j', parentId: null, scopeId: 's', label: 'c', state: CoroutineState.ACTIVE, ...over,
})

describe('CardFacts (Part 7, P-9)', () => {
  it("shows the server's lifetime and creation site, and the health's badges", () => {
    render(
      <CardFactsContext.Provider value={new Map([['c', { badges: [{ kind: 'possibly-blocked', text: 'Possibly blocked' }] }]])}>
        <CardFacts
          node={node({ lifetimeNanos: 12.3e9, lifetimeKind: 'SO_FAR', creationLocation: { function: 'f', fileName: 'Orders.kt', lineNumber: 42, reason: 'launch' } })}
        />
      </CardFactsContext.Provider>,
    )
    expect(screen.getByTestId('node-meta')).toHaveTextContent('12.3 s so far · Orders.kt:42')
    const badge = screen.getByText('Possibly blocked')
    expect(badge).toHaveAttribute('data-badge', 'possibly-blocked')
  })

  it("names the health's observation time on its badges, and marks them when the last refresh failed (H-2)", () => {
    render(
      <CardFactsContext.Provider
        value={Object.assign(new Map([['c', { badges: [{ kind: 'long-lived' as const, text: 'Long-lived · 40.0 s' }] }]]), { asOf: 'as of 14:02:31', stale: true })}
      >
        <CardFacts node={node()} />
      </CardFactsContext.Provider>,
    )
    const badge = screen.getByText('Long-lived · 40.0 s')
    expect(badge).toHaveAttribute('title', 'Long-lived · 40.0 s — from the session health, as of 14:02:31; its last refresh failed')
    expect(badge).toHaveAttribute('data-stale', 'true')
  })

  it('on a card, shows the first badge and how many more, every badge named in its title; the Inspector shows all', () => {
    const facts = new Map([['c', { badges: [
      { kind: 'possibly-blocked' as const, text: 'Possibly blocked' },
      { kind: 'long-lived' as const, text: 'Long-lived · 40.0 s' },
    ] }]])
    const { unmount } = render(
      <CardFactsContext.Provider value={facts}>
        <CardFacts node={node()} compact />
      </CardFactsContext.Provider>,
    )
    expect(screen.getByText('Possibly blocked')).toHaveAttribute('title', 'Possibly blocked · Long-lived · 40.0 s')
    expect(screen.queryByText('Long-lived · 40.0 s')).toBeNull()
    expect(screen.getByText('+1')).toHaveAttribute('data-look-part', 'node-badge-more')
    unmount()
    render(
      <CardFactsContext.Provider value={facts}>
        <CardFacts node={node()} />
      </CardFactsContext.Provider>,
    )
    expect(screen.getByText('Long-lived · 40.0 s')).toBeInTheDocument()
    expect(screen.queryByText('+1')).toBeNull()
  })

  it('shows nothing for a node with no facts (a replay projection)', () => {
    const { container } = render(<CardFacts node={node()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it("adds the round's context to the state words", () => {
    render(
      <CardFactsContext.Provider value={new Map([['c', { badges: [], context: 'after the failure' }]])}>
        <CardStateContext id="c" />
      </CardFactsContext.Provider>,
    )
    expect(screen.getByText('· after the failure')).toBeInTheDocument()
  })
})

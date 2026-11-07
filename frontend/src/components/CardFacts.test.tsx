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

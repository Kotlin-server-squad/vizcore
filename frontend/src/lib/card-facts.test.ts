import { describe, it, expect } from 'vitest'
import { cardFacts, lifetimeText } from './card-facts'
import { health } from '@/test/health-fixture'

describe('cardFacts (Part 7, P-9)', () => {
  it('gives each coroutine the badges the health lists for it, with frozen ages, and the round context', () => {
    const facts = cardFacts(
      health({
        state: 'PROBLEMS',
        incidents: {
          total: 2,
          items: [
            {
              type: 'FAILED_ROUND', id: 'r', roundId: 'r1', anchorId: 'a',
              failures: { items: [{ coroutineId: 'f', name: 'f', exceptionType: 'E', message: null, failedAtSeq: 3, detailsDropped: false }], total: 1 },
              cancelledAfter: { items: ['x'], total: 1 }, firstFailureSeq: 3, sortSeq: 3,
            },
            {
              type: 'POSSIBLY_BLOCKED', id: 'b', coroutineId: 'p', name: 'p', roundId: null, anchorId: null, lastThread: null,
              dispatcher: null, lastSuspension: null, lastSuspensionProvenance: null, lastRunStart: { kind: 'STARTED', appNanos: 0, seq: 1 },
              runningForNanos: 11e9, transitions: [], transitionsGap: false, firstObservedRunning: true, eventLoopClue: null, sortSeq: 1,
            },
          ],
        },
        observations: {
          longLived: { items: [{ coroutineId: 'p', name: 'p', ageNanos: 123e9 }], total: 1 },
          longSuspended: { items: [{ coroutineId: 's', name: 's', ageNanos: 45e9 }], total: 1 },
        },
      }),
    )
    // The problem first, then long suspended, then long-lived: a card shows the first.
    expect(facts.get('p')?.badges).toEqual([
      { kind: 'possibly-blocked', text: 'Possibly blocked' },
      { kind: 'long-lived', text: 'Long-lived · 2 m 3 s' },
    ])
    expect(facts.get('s')?.badges).toEqual([{ kind: 'long-suspended', text: 'Long suspended · 45.0 s' }])
    expect(facts.get('x')?.context).toBe('after the failure')
    expect(facts.get('f')).toBeUndefined()
  })

  it('puts long suspended before long-lived', () => {
    const facts = cardFacts(
      health({
        observations: {
          longLived: { items: [{ coroutineId: 's', name: 's', ageNanos: 90e9 }], total: 1 },
          longSuspended: { items: [{ coroutineId: 's', name: 's', ageNanos: 45e9 }], total: 1 },
        },
      }),
    )
    expect(facts.get('s')?.badges.map(b => b.kind)).toEqual(['long-suspended', 'long-lived'])
  })

  it('is empty without a health', () => {
    expect(cardFacts(undefined).size).toBe(0)
  })
})

describe('lifetimeText', () => {
  it('says how the lifetime was measured', () => {
    expect(lifetimeText({ lifetimeNanos: 2.5e9, lifetimeKind: 'FINISHED' })).toBe('2.5 s')
    expect(lifetimeText({ lifetimeNanos: 12e9, lifetimeKind: 'SO_FAR' })).toBe('12.0 s so far')
    expect(lifetimeText({ lifetimeNanos: 3e9, lifetimeKind: 'AT_LEAST' })).toBe('at least 3.0 s')
    expect(lifetimeText({})).toBeNull()
  })
})

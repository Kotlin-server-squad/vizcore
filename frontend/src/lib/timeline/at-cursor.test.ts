import { describe, it, expect } from 'vitest'
import { buildTimeline, type Timeline } from './build-timeline'
import { holdsAt, pieces, stateAt } from './at-cursor'
import { Run } from './test-events'
import { expectAgreesAtEveryCursor } from './test-agreement'

describe('the timeline at a cursor', () => {
  it('fills what the events up to the cursor show and leaves the rest hollow, by index not time', () => {
    const r = new Run()
    r.created('a', 0) // 0
    r.life('started', 'a', {}, 5) // 1
    r.life('suspended', 'a', {}, 5) // 2: the same time as its start
    r.life('completed', 'a', {}, 9) // 3
    const t = buildTimeline(r.events)
    const [created, active, suspended] = t.rows.get('a')!.spans
    // At the start, the cursor is on index 1: Active is current, the zero-length Suspended after it is hollow.
    expect(pieces(t, active!, 1)).toEqual({ filled: [5, 5], hollow: [5, 5] })
    expect(pieces(t, suspended!, 1)).toEqual({ filled: null, hollow: [5, 9] })
    expect(pieces(t, created!, 1)).toEqual({ filled: [0, 5], hollow: null })
    // Live (no cursor): all filled; an open span runs to the last event.
    expect(pieces(t, suspended!, null)).toEqual({ filled: [5, 9], hollow: null })
  })

  it('splits the current span at the cursor’s time', () => {
    const r = new Run()
    r.created('a', 0)
    r.created('b', 4)
    r.life('completed', 'a', {}, 10)
    const t = buildTimeline(r.events)
    expect(pieces(t, t.rows.get('a')!.spans[0]!, 1)).toEqual({ filled: [0, 4], hollow: [4, 10] })
  })

  it('gives the terminal state from its event on', () => {
    const r = new Run()
    r.created('a')
    r.life('failed', 'a')
    const t = buildTimeline(r.events)
    const row = t.rows.get('a')!
    expect(stateAt(t, row, 0)).toBe('CREATED')
    expect(stateAt(t, row, 1)).toBe('FAILED')
  })

  it('has no state before a row’s creation, or for a row created before the events', () => {
    const r = new Run()
    r.life('resumed', 'old')
    r.created('a')
    const t = buildTimeline(r.events)
    expect(stateAt(t, t.rows.get('a')!, 0)).toBeNull()
    expect(stateAt(t, t.rows.get('old')!, 1)).toBeNull()
  })

  it('agrees with the tree and the second line on locks, handovers and a hold past the end', () => {
    const r = new Run()
    r.created('a')
    r.created('b')
    r.life('started', 'a')
    r.lock('m', 'a')
    r.life('suspended', 'a')
    r.life('started', 'b')
    r.life('suspended', 'b', { waitKind: 'MUTEX', resourceId: 'm' })
    r.life('resumed', 'a')
    r.lock('m', 'b')
    r.unlock('m', 'a')
    r.life('resumed', 'b')
    r.life('completed', 'a')
    r.life('completed', 'b') // b ends holding m
    r.created('c')
    expectAgreesAtEveryCursor(r.events)
  })

  it('agrees on permits: counts, snapshots between operations, unknown releases and trimmed acquisitions', () => {
    const r = new Run()
    r.snapshot('s', 3, 2, { old: 1 }) // a trimmed acquisition
    r.created('a')
    r.created('b')
    r.acquire('s', 'a', 1)
    r.snapshot('s', 3, 1, { old: 1, a: 1 })
    r.acquire('s', 'a', 0)
    r.snapshot('s', 3, 0, { old: 1, a: 2 })
    r.release('s', 'x', 1, true)
    r.snapshot('s', 3, 1, { old: 1, a: 2 }, 1)
    r.acquire('s', 'b', 0)
    r.release('s', 'a', 1, false)
    r.snapshot('s', 3, 1, { old: 1, a: 1, b: 1 }, 1)
    r.release('s', 'old', 2, false)
    r.snapshot('s', 3, 2, { a: 1 })
    r.release('s', 'a', 3, false)
    expectAgreesAtEveryCursor(r.events)
  })

  it('lists a row’s open holds at a cursor, certain or not', () => {
    const r = new Run()
    r.semCreated('s', 2)
    r.snapshot('s', 2, 1, { a: 1 }) // 1
    r.release('s', 'x', 2, false) // 2: x holds none, but the producer says it is known
    r.lock('m', 'a') // 3
    const t: Timeline = buildTimeline(r.events)
    expect(holdsAt(t, 'a', 1)).toEqual([{ resourceId: 's', kind: 'permit', certain: true }])
    expect(holdsAt(t, 'a', 3)).toEqual([{ resourceId: 'm', kind: 'lock', certain: true }])
  })
})

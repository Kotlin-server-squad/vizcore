import { describe, it, expect } from 'vitest'
import { coroutineChangesBetween, hierarchyChangesBetween, indexReplay } from './replay-index'
import type { VizEvent } from '@/types/api'

const ev = (seq: number, kind: string, coroutineId?: string) =>
  ({ seq, tsNanos: seq, sessionId: 's', kind, ...(coroutineId ? { coroutineId } : {}) }) as unknown as VizEvent

// "old" was created before the replay starts (seq 10); "a" is created in it.
const events = [
  ev(10, 'coroutine.suspended', 'old'),
  ev(11, 'coroutine.created', 'a'),
  ev(12, 'coroutine.started', 'a'),
  ev(13, 'mutex.created'),
  ev(14, 'coroutine.resumed', 'old'),
  ev(15, 'coroutine.suspended', 'a'),
  // seq 16 is missing: the live stream shed it.
  ev(17, 'coroutine.completed', 'a'),
]
const index = indexReplay(events)

describe('coroutineChangesBetween', () => {
  it('is false across other coroutines’ events, in either direction', () => {
    expect(coroutineChangesBetween(index, 'a', 12, 14)).toBe(false)
    expect(coroutineChangesBetween(index, 'a', 14, 12)).toBe(false)
  })

  it('is true across one of its own events (the later end counts, the earlier does not)', () => {
    expect(coroutineChangesBetween(index, 'a', 12, 15)).toBe(true)
    expect(coroutineChangesBetween(index, 'a', 15, 15)).toBe(false)
    expect(coroutineChangesBetween(index, 'a', 11, 12)).toBe(true)
  })

  it('is true across a gap in the replay, which may hide one of its events', () => {
    expect(coroutineChangesBetween(index, 'old', 15, 17)).toBe(true)
  })
})

describe('hierarchyChangesBetween', () => {
  it('is false across events of coroutines the replay created, which the page projects itself', () => {
    expect(hierarchyChangesBetween(index, 12, 13)).toBe(false)
  })

  it('is true across a creation, an event of a coroutine created before the replay, or a gap', () => {
    expect(hierarchyChangesBetween(index, 10, 11)).toBe(true)
    expect(hierarchyChangesBetween(index, 13, 14)).toBe(true)
    expect(hierarchyChangesBetween(index, 15, 17)).toBe(true)
  })
})

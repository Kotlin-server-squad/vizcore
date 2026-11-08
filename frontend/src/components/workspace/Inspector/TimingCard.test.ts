import { describe, it, expect } from 'vitest'
import * as timing from './TimingCard'
import type { CoroutineTimeline } from '@/types/api'

const SECOND = 1_000_000_000

/** Created at tsNanos 1s / wall 10_000 ms; resumed at 3s and not heard from since (#218). */
function blocked(overrides: Partial<CoroutineTimeline> = {}): CoroutineTimeline {
  return {
    coroutineId: 'c',
    name: 'c',
    state: 'ACTIVE',
    totalDuration: null,
    activeDuration: 0.1 * SECOND,
    suspendedDuration: 1.9 * SECOND,
    childrenIds: [],
    events: [
      { seq: 1, tsNanos: 1 * SECOND, kind: 'coroutine.created' },
      { seq: 2, tsNanos: 1.1 * SECOND, kind: 'coroutine.suspended' },
    ],
    openPhase: 'ACTIVE',
    openPhaseSinceNanos: 3 * SECOND,
    createdAtNanos: 1 * SECOND,
    createdAtEpochMs: 10_000,
    observedAtEpochMs: 70_000,
    ...overrides,
  }
}

describe('TimingCard (Part 7, #232 removed)', () => {
  it('keeps no estimate of the app clock and no blocked threshold of its own', () => {
    expect('liveTiming' in timing).toBe(false)
    expect('POSSIBLY_BLOCKED_AFTER_NANOS' in timing).toBe(false)
  })

  it('counts an unfinished coroutine by its recorded events, the most the client can say', () => {
    expect(timing.lifetimeSoFar(blocked())).toBe(2 * SECOND)
  })
})

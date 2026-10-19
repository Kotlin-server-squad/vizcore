import { describe, it, expect } from 'vitest'
import { liveTiming } from './TimingCard'
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

describe('liveTiming', () => {
  it('counts an open ACTIVE phase up to now, through the backend clock and then the browser clock', () => {
    // Backend saw it 60 s after creation; the browser has held the response for 5 s since.
    const live = liveTiming(blocked(), 1_000, 6_000)!
    expect(live.total).toBeCloseTo(65 * SECOND)
    // Now is 66 s on the app clock; the open phase began at 3 s.
    expect(live.openFor).toBeCloseTo(63 * SECOND)
    expect(live.active).toBeCloseTo(0.1 * SECOND + 63 * SECOND)
    expect(live.suspended).toBeCloseTo(1.9 * SECOND)
  })

  it('adds an open SUSPENDED phase to the suspended time instead', () => {
    const live = liveTiming(blocked({ openPhase: 'SUSPENDED' }), 1_000, 1_000)!
    expect(live.suspended).toBeCloseTo(1.9 * SECOND + 58 * SECOND)
    expect(live.active).toBeCloseTo(0.1 * SECOND)
  })

  it('never estimates "now" before the latest recorded event', () => {
    // A skewed clock that would put "now" before the open phase began.
    const live = liveTiming(blocked({ observedAtEpochMs: 9_000 }), 1_000, 1_000)!
    expect(live.openFor).toBe(0)
    expect(live.total).toBeCloseTo(2 * SECOND)
  })

  it('is null for a finished coroutine and for a backend without the open-phase fields', () => {
    expect(liveTiming(blocked({ totalDuration: 5 * SECOND, openPhase: null }), 0, 0)).toBeNull()
    expect(liveTiming(blocked({ openPhase: undefined, openPhaseSinceNanos: undefined }), 0, 0)).toBeNull()
    expect(liveTiming(blocked({ observedAtEpochMs: undefined }), 0, 0)).toBeNull()
  })

  it('is null when the creation wall time was never set (zero), rather than counting from 1970', () => {
    expect(liveTiming(blocked({ createdAtEpochMs: 0 }), 0, 0)).toBeNull()
  })
})

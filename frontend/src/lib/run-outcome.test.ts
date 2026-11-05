import { describe, it, expect } from 'vitest'
import { deriveRunOutcome, recordedSpanNanos } from './run-outcome'
import type { SessionSnapshot, VizEvent } from '@/types/api'

const session = (...states: string[]) =>
  ({
    sessionId: 's-1',
    coroutineCount: states.length,
    eventCount: 0,
    coroutines: states.map((state, i) => ({
      id: `c-${i}`, jobId: `j-${i}`, parentId: null, scopeId: 'scope', label: null, state,
    })),
  }) as unknown as SessionSnapshot

describe('deriveRunOutcome', () => {
  it('is not started before any coroutine is seen', () => {
    expect(deriveRunOutcome(session())).toEqual({ outcome: 'notStarted', failedCount: 0, ended: false })
  })

  it('is running while any coroutine is not terminal', () => {
    expect(deriveRunOutcome(session('COMPLETED', 'SUSPENDED')).outcome).toBe('running')
  })

  it('is finished when every coroutine completed or was cancelled', () => {
    expect(deriveRunOutcome(session('COMPLETED', 'CANCELLED'))).toEqual({ outcome: 'finished', failedCount: 0, ended: true })
  })

  it('is failed as soon as one coroutine failed, even while others run (S-3)', () => {
    expect(deriveRunOutcome(session('FAILED', 'ACTIVE'))).toEqual({ outcome: 'failed', failedCount: 1, ended: false })
    expect(deriveRunOutcome(session('FAILED', 'FAILED', 'CANCELLED'))).toEqual({ outcome: 'failed', failedCount: 2, ended: true })
  })
})

describe('recordedSpanNanos', () => {
  const ev = (seq: number, tsNanos: number) => ({ seq, tsNanos, kind: 'CoroutineCreated' }) as unknown as VizEvent

  it('is the time from the first to the last recorded event', () => {
    expect(recordedSpanNanos([ev(1, 5_000), ev(2, 12_000), ev(3, 9_000)])).toBe(7_000)
  })

  it('spans every list it is given: the history loaded with the page and the events streamed since', () => {
    expect(recordedSpanNanos([ev(1, 5_000), ev(2, 6_000)], [ev(3, 9_000)])).toBe(4_000)
  })

  it('is unknown with fewer than two events', () => {
    expect(recordedSpanNanos([])).toBeNull()
    expect(recordedSpanNanos([ev(1, 5_000)])).toBeNull()
  })
})

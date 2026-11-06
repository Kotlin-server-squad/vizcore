/**
 * Unit tests for projectWaits — what each coroutine waits for right now, from the suspensions the mutex,
 * semaphore and channel wrappers record with `waitKind` and `resourceId` (spec 2026-11-05, S-7).
 */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { projectWaits } from './project-waits'

let seq = 0

function event(kind: string, coroutineId: string, extra: Record<string, unknown> = {}): VizEvent {
  seq += 1
  return {
    sessionId: 'session-1',
    seq,
    tsNanos: seq * 1_000,
    kind,
    coroutineId,
    jobId: `job-${coroutineId}`,
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: coroutineId,
    ...extra,
  } as unknown as VizEvent
}

const waitForLock = (id: string) => event('coroutine.suspended', id, { reason: 'mutex', waitKind: 'MUTEX', resourceId: 'mutex-4' })

describe('projectWaits', () => {
  it('a wrapper suspension is a wait with its kind, resource and start', () => {
    const suspended = waitForLock('order-7')

    expect(projectWaits([event('coroutine.started', 'order-7'), suspended]).get('order-7')).toEqual({
      waitKind: 'MUTEX',
      resourceId: 'mutex-4',
      sinceSeq: suspended.seq,
      sinceTsNanos: suspended.tsNanos,
    })
  })

  it('a resume ends the wait', () => {
    expect(projectWaits([waitForLock('order-7'), event('coroutine.resumed', 'order-7')]).has('order-7')).toBe(false)
  })

  it('cancellation ends the wait', () => {
    expect(projectWaits([waitForLock('order-7'), event('coroutine.cancelled', 'order-7')]).has('order-7')).toBe(false)
  })

  it('a suspension without waitKind (a delay, or an older producer) is no wrapper wait', () => {
    const delay = event('coroutine.suspended', 'order-7', { reason: 'delay', durationMillis: 500 })
    const older = event('coroutine.suspended', 'order-8', { reason: 'mutex' })

    expect(projectWaits([delay, older]).size).toBe(0)
  })

  it('keeps each coroutine apart, and a later wait replaces an earlier one', () => {
    // Built in order: the reduction follows seq, not the array's order.
    const earlier = [waitForLock('order-7'), waitForLock('order-8'), event('coroutine.resumed', 'order-7')]
    const later = event('coroutine.suspended', 'order-7', { reason: 'semaphore', waitKind: 'SEMAPHORE', resourceId: 'semaphore-2' })
    const waits = projectWaits([...earlier, later])

    expect(waits.get('order-7')?.waitKind).toBe('SEMAPHORE')
    expect(waits.get('order-8')?.waitKind).toBe('MUTEX')
  })
})

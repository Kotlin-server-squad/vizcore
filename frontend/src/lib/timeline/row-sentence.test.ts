import { describe, it, expect } from 'vitest'
import { buildTimeline } from './build-timeline'
import { rowSentence } from './row-sentence'
import { Run } from './test-events'

const S = 1_000_000_000
const names = new Map([['m', 'inventory-lock'], ['s', 'payment-gateway']])
const nameOf = (id: string) => names.get(id) ?? id

function sentence(r: Run, id: string, opts: { cursor?: number | null; observed?: boolean; treeState?: string } = {}) {
  const timeline = buildTimeline(r.events)
  return rowSentence({
    timeline,
    row: timeline.rows.get(id),
    name: id,
    treeState: opts.treeState ?? 'ACTIVE',
    cursor: opts.cursor ?? null,
    observed: opts.observed ?? false,
    nameOf,
  })
}

/** order-7: created at 0, started 0.4 s, waits for the lock from 1.0 s to 10.0 s, holds it 0.1 s, completes. */
function order7() {
  const r = new Run()
  r.created('order-7', 0) // 0
  r.life('started', 'order-7', {}, 0.4 * S) // 1
  r.life('suspended', 'order-7', { waitKind: 'MUTEX', resourceId: 'm' }, 1.0 * S) // 2
  r.lock('m', 'order-7', 9.95 * S) // 3
  r.life('resumed', 'order-7', {}, 10.0 * S) // 4
  r.unlock('m', 'order-7', 10.05 * S) // 5
  r.life('completed', 'order-7', {}, 10.1 * S) // 6
  return r
}

describe('rowSentence', () => {
  it('sums a finished row up in one sentence', () => {
    expect(sentence(order7(), 'order-7')).toBe(
      'order-7 was active for 700 ms, was suspended for 9.0 s (9.0 s of it waiting for inventory-lock), held inventory-lock for 100 ms, and completed at 10.1 s.',
    )
  })

  it('tells an unfinished row as it is now', () => {
    const r = new Run()
    r.created('a', 0)
    r.life('started', 'a', {}, 1 * S)
    r.life('suspended', 'a', { waitKind: 'SEMAPHORE', resourceId: 's' }, 2 * S)
    r.created('b', 5 * S)
    expect(sentence(r, 'a')).toBe(
      'a was active for 1.0 s, and has been suspended for 3.0 s, waiting for a payment-gateway permit.',
    )
  })

  it('stops at the replay cursor', () => {
    expect(sentence(order7(), 'order-7', { cursor: 2 })).toBe(
      'By 1.0 s, order-7 was active for 600 ms, and is suspended, waiting for inventory-lock.',
    )
  })

  it('says "observed" when events were lost or trimmed', () => {
    expect(sentence(order7(), 'order-7', { observed: true })).toMatch(/^In the observed events, order-7 was active for/)
    expect(sentence(order7(), 'order-7', { observed: true, cursor: 2 })).toMatch(/^In the observed events, by 1.0 s, order-7/)
  })

  it('says only what is known of a row created before the events', () => {
    const r = new Run()
    r.life('resumed', 'old')
    expect(sentence(r, 'old')).toBe('old was created before these events, so its states are not drawn.')
    expect(sentence(new Run(), 'none')).toBe('none has no recorded intervals in these events.')
  })

  it('says a lock is still held after its holder ended', () => {
    const r = new Run()
    r.created('a', 0)
    r.lock('m', 'a')
    r.life('failed', 'a', {}, 2 * S)
    r.created('b', 3 * S)
    expect(sentence(r, 'a')).toBe(
      'a held inventory-lock for 3.0 s, and failed at 2.0 s; it still holds inventory-lock after it failed.',
    )
  })

  it('measures durations on the raw clock of the span’s own events, as the Timing card does', () => {
    const r = new Run()
    r.add('MutexCreated', { mutexId: 'z', mutexLabel: 'z', ownerCoroutineId: null }, 10 * S) // another thread, later
    r.created('a', 4 * S)
    r.life('started', 'a', {}, 5 * S)
    r.life('suspended', 'a', {}, 12 * S)
    r.life('completed', 'a', {}, 13 * S)
    // The running max would put the start at 10 s (2 s active); the raw clock says 7 s.
    expect(sentence(r, 'a')).toBe('a was active for 7.0 s, was suspended for 1.0 s, and completed at 3.0 s.')
  })

  it('counts permits held at once only once, and says when whose permit was unknown', () => {
    const r = new Run()
    r.created('a', 0) // 0
    r.add('SemaphoreCreated', { semaphoreId: 's', semaphoreLabel: 's', totalPermits: 2 }, 0) // 1
    r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 0, totalPermits: 2, activeHolders: ['a'], activeHolderLabels: ['a'], activeHolderPermits: [2], unknownHolderPermits: 0, waitingCoroutines: [], waitingLabels: [] }, 1 * S) // 2
    r.add('SemaphorePermitReleased', { semaphoreId: 's', semaphoreLabel: 's', releaserId: 'x', releaserLabel: 'x', newAvailablePermits: 1, holdDurationNanos: 0, holderUnknown: true }, 2 * S) // 3
    r.created('b', 4 * S) // 4
    expect(sentence(r, 'a')).toBe(
      'a may hold payment-gateway permits (holder unknown; listed for 3.0 s so far), and has been created, not started, for 4.0 s.',
    )
  })

  it('says "may hold" while whose permit it is is unknown at the cursor, as the node’s line does', () => {
    const r = new Run()
    r.created('a', 0) // 0
    r.add('SemaphoreCreated', { semaphoreId: 's', semaphoreLabel: 's', totalPermits: 3 }, 0) // 1
    r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 1, totalPermits: 3, activeHolders: ['a', 'b'], activeHolderLabels: ['a', 'b'], activeHolderPermits: [1, 1], unknownHolderPermits: 0, waitingCoroutines: [], waitingLabels: [] }, 1 * S) // 2
    r.add('SemaphorePermitReleased', { semaphoreId: 's', semaphoreLabel: 's', releaserId: 'a', releaserLabel: 'a', newAvailablePermits: 2, holdDurationNanos: 0, holderUnknown: false }, 2 * S) // 3: not yet snapshotted
    expect(sentence(r, 'a', { cursor: 3 })).toContain('may hold a payment-gateway permit (holder unknown; listed for 1.0 s so far)')
    expect(sentence(r, 'a', { cursor: 2 })).toContain('holds a payment-gateway permit (for 0 ms so far)')
  })

  it('names waiting for children and a cancellation', () => {
    const r = new Run()
    r.created('p', 0)
    r.life('started', 'p', {}, 1 * S)
    r.life('body-completed', 'p', {}, 2 * S)
    r.life('cancelled', 'p', {}, 4 * S)
    expect(sentence(r, 'p')).toBe('p was active for 1.0 s, waited 2.0 s for its children, and was cancelled at 4.0 s.')
  })
})

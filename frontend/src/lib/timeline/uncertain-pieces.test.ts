import { describe, it, expect } from 'vitest'
import { buildTimeline } from './build-timeline'
import { bucketMarks, uncertainPieces } from './uncertain-pieces'
import { Run } from './test-events'

const S = 1_000_000_000

function permits() {
  const r = new Run()
  r.add('SemaphoreCreated', { semaphoreId: 's', semaphoreLabel: 's', totalPermits: 3 }, 0) // 0
  r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 1, totalPermits: 3, activeHolders: ['a', 'b'], activeHolderLabels: ['a', 'b'], activeHolderPermits: [1, 1], unknownHolderPermits: 0, waitingCoroutines: [], waitingLabels: [] }, 1 * S) // 1
  r.add('SemaphorePermitReleased', { semaphoreId: 's', semaphoreLabel: 's', releaserId: 'a', releaserLabel: 'a', newAvailablePermits: 2, holdDurationNanos: 0, holderUnknown: false }, 5 * S) // 2
  r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 2, totalPermits: 3, activeHolders: ['b'], activeHolderLabels: ['b'], activeHolderPermits: [1], unknownHolderPermits: 0, waitingCoroutines: [], waitingLabels: [] }, 5 * S + 1000) // 3
  r.add('MutexCreated', { mutexId: 'm', mutexLabel: 'm', ownerCoroutineId: null }, 10 * S) // 4
  return buildTimeline(r.events)
}

describe('uncertainPieces', () => {
  it('drops an unsettled instant too short to see, outside the cursor', () => {
    const t = permits()
    const b = t.lanes[0]!.bands.find(x => x.coroutineId === 'b')!
    expect(uncertainPieces(t, b, null)).toEqual([])
  })

  it('marks "may hold" at the cursor, decided by index, even when it lasts an instant', () => {
    const t = permits()
    const b = t.lanes[0]!.bands.find(x => x.coroutineId === 'b')!
    expect(uncertainPieces(t, b, 2)).toEqual([{ range: [5 * S, 5 * S], after: false, atCursor: true }])
    expect(uncertainPieces(t, b, 3)).toEqual([])
  })

  it('draws no sliver per operation: a thousand unsettled moments in a long run cost nothing', () => {
    const r = new Run()
    const snap = (ts: number) =>
      r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 2, totalPermits: 3, activeHolders: ['a'], activeHolderLabels: ['a'], activeHolderPermits: [1], unknownHolderPermits: 0, waitingCoroutines: [], waitingLabels: [] }, ts)
    r.add('SemaphoreCreated', { semaphoreId: 's', semaphoreLabel: 's', totalPermits: 3 }, 0)
    snap(0)
    for (let i = 1; i <= 1000; i++) {
      r.add('SemaphorePermitAcquired', { semaphoreId: 's', semaphoreLabel: 's', acquirerId: 'a', acquirerLabel: 'a', remainingPermits: 2, waitDurationNanos: 0 }, i * 50_000_000)
      snap(i * 50_000_000 + 500_000)
    }
    const t = buildTimeline(r.events)
    expect(t.lanes[0]!.bands[0]!.uncertain.length).toBe(1000)
    expect(uncertainPieces(t, t.lanes[0]!.bands[0]!, null)).toEqual([])
  })

  it('merges visible pieces closer than the drawing can tell apart', () => {
    const r = new Run()
    r.add('SemaphoreCreated', { semaphoreId: 's', semaphoreLabel: 's', totalPermits: 3 }, 0)
    const state = (ts: number, unknown: number) =>
      r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 1, totalPermits: 3, activeHolders: ['a'], activeHolderLabels: ['a'], activeHolderPermits: [1], unknownHolderPermits: unknown, waitingCoroutines: [], waitingLabels: [] }, ts)
    state(0, 0)
    // "May hold" for 2 s, settled for 1 ms, "may hold" again for 2 s: one piece on a 100 s axis.
    state(1 * S, 1)
    state(3 * S, 0)
    state(3 * S + 1_000_000, 1)
    state(5 * S, 0)
    r.add('MutexCreated', { mutexId: 'm', mutexLabel: 'm', ownerCoroutineId: null }, 100 * S)
    const t = buildTimeline(r.events)
    expect(uncertainPieces(t, t.lanes[0]!.bands[0]!, null)).toEqual([{ range: [1 * S, 5 * S], after: false }])
  })

  it('marks "may hold" at the cursor on a zero-length axis too', () => {
    const r = new Run()
    r.add('SemaphoreCreated', { semaphoreId: 's', semaphoreLabel: 's', totalPermits: 3 }, 0)
    r.add('SemaphoreStateChanged', { semaphoreId: 's', semaphoreLabel: 's', availablePermits: 1, totalPermits: 3, activeHolders: ['a'], activeHolderLabels: ['a'], activeHolderPermits: [1], unknownHolderPermits: 1, waitingCoroutines: [], waitingLabels: [] }, 0)
    const t = buildTimeline(r.events)
    expect(uncertainPieces(t, t.lanes[0]!.bands[0]!, null)).toEqual([{ range: [0, 0], after: false, atCursor: true }])
  })
})

describe('bucketMarks', () => {
  it('draws at most one mark per hundredth of the axis, counting what it stands for, apart either side of the cursor', () => {
    const r = new Run()
    for (let i = 0; i < 1000; i++) r.add('MutexCreated', { mutexId: `m${i}`, mutexLabel: 'm', ownerCoroutineId: null }, i * 1_000_000)
    const t = buildTimeline(r.events)
    const marks = bucketMarks(t, [...Array(1000).keys()], 500)
    expect(marks.length).toBeLessThanOrEqual(102)
    expect(marks.reduce((n, m) => n + m.count, 0)).toBe(1000)
    expect(marks.every(m => m.after === m.at > 500)).toBe(true)
  })

  it('merges marks at one time on a zero-length axis, either side of the cursor', () => {
    const r = new Run()
    for (let i = 0; i < 50; i++) r.add('MutexCreated', { mutexId: `m${i}`, mutexLabel: 'm', ownerCoroutineId: null }, 0)
    const t = buildTimeline(r.events)
    expect(bucketMarks(t, [...Array(50).keys()], 20).map(m => [m.count, m.after])).toEqual([
      [21, false],
      [29, true],
    ])
  })
})


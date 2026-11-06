/** Unit tests for projectSync — what each mutex and semaphore looks like after a list of events (S-6). */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { projectSync } from './project-sync'

let seq = 0
function event(kind: string, fields: Record<string, unknown>): VizEvent {
  seq += 1
  return { sessionId: 's-1', seq, tsNanos: seq * 1_000, kind, ...fields } as unknown as VizEvent
}

const created = event('SemaphoreCreated', { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 3 })
function stateChanged(fields: Record<string, unknown>) {
  return event('SemaphoreStateChanged', {
    semaphoreId: 'sem-1',
    semaphoreLabel: 'payment-gateway',
    availablePermits: 0,
    totalPermits: 3,
    activeHolders: ['c-1', 'c-2'],
    activeHolderLabels: ['order-1', 'order-2'],
    waitingCoroutines: [],
    waitingLabels: [],
    ...fields,
  })
}

describe('projectSync', () => {
  it('has nothing for no events', () => {
    expect(projectSync(undefined)).toMatchObject({ mutexes: [], semaphores: [], hasDeadlock: false })
    expect(projectSync([])).toMatchObject({ mutexes: [], semaphores: [] })
  })

  it('counts the permits each holder holds', () => {
    const { semaphores } = projectSync([created, stateChanged({ activeHolderPermits: [2, 1] })])
    expect(semaphores[0]!.activeHolders).toEqual([
      { id: 'c-1', label: 'order-1', permits: 2 },
      { id: 'c-2', label: 'order-2', permits: 1 },
    ])
  })

  it('reads one permit per holder from an older producer that sends no counts', () => {
    const { semaphores } = projectSync([created, stateChanged({})])
    expect(semaphores[0]!.activeHolders.map(h => h.permits)).toEqual([1, 1])
  })

  it('keeps the permits whose holder is unknown (S-7), zero by default', () => {
    expect(projectSync([created]).semaphores[0]!.unknownHolderPermits).toBe(0)
    const { semaphores } = projectSync([
      created,
      stateChanged({ activeHolders: ['c-1'], activeHolderLabels: ['order-1'], unknownHolderPermits: 1 }),
    ])
    expect(semaphores[0]!.unknownHolderPermits).toBe(1)
  })

  it('tracks the lock holder and the latest queue of a mutex', () => {
    const { mutexes } = projectSync([
      event('MutexCreated', { mutexId: 'm-1', mutexLabel: 'inventory-lock', ownerCoroutineId: null }),
      event('MutexLockAcquired', { mutexId: 'm-1', mutexLabel: 'inventory-lock', acquirerId: 'c-1', acquirerLabel: 'order-1', waitDurationNanos: 0 }),
      event('MutexQueueChanged', { mutexId: 'm-1', mutexLabel: 'inventory-lock', waitingCoroutineIds: ['c-2', 'c-3'], waitingLabels: ['order-2', 'order-3'] }),
    ])
    expect(mutexes[0]).toMatchObject({
      isLocked: true,
      currentHolder: 'c-1',
      waitQueue: [{ id: 'c-2', label: 'order-2' }, { id: 'c-3', label: 'order-3' }],
    })
  })

  describe('a lock handed over', () => {
    const m = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
    const acquired = (who: string) => event('MutexLockAcquired', { ...m, acquirerId: who, acquirerLabel: who, waitDurationNanos: 0 })
    const unlocked = (who: string) => event('MutexUnlocked', { ...m, releaserId: who, releaserLabel: who, nextWaiterId: null, holdDurationNanos: 0 })

    it('the ordinary handoff: released, then acquired by the next', () => {
      const [mutex] = projectSync([acquired('c-1'), unlocked('c-1'), acquired('c-2')]).mutexes
      expect(mutex).toMatchObject({ isLocked: true, currentHolder: 'c-2' })
    })

    it('the previous holder\'s release recorded after the next acquisition does not free the lock', () => {
      // VizMutex unlocks before it records the release, so the waiter it woke can record its acquisition first.
      const [mutex] = projectSync([acquired('c-1'), acquired('c-2'), unlocked('c-1')]).mutexes
      expect(mutex).toMatchObject({ isLocked: true, currentHolder: 'c-2' })
    })

    it('several handovers recorded before their releases leave the last holder holding', () => {
      const events = [acquired('c-1'), acquired('c-2'), acquired('c-3'), unlocked('c-1'), unlocked('c-2')]
      expect(projectSync(events).mutexes[0]).toMatchObject({ isLocked: true, currentHolder: 'c-3' })
      expect(projectSync([...events, unlocked('c-3')]).mutexes[0]).toMatchObject({ isLocked: false })
    })

    it('a coroutine that acquires again is no longer waiting to record an old release', () => {
      // c-1's release after the handover was never recorded; when c-1 holds the lock again, its release frees it.
      const events = [acquired('c-1'), acquired('c-2'), unlocked('c-2'), acquired('c-1'), unlocked('c-1')]
      expect(projectSync(events).mutexes[0]).toMatchObject({ isLocked: false, currentHolder: null })
    })

    it('a release by some other coroutine frees it', () => {
      const [mutex] = projectSync([acquired('c-1'), unlocked('c-9')]).mutexes
      expect(mutex).toMatchObject({ isLocked: false, currentHolder: null })
    })
  })

  it('reduces in seq order whatever order the events arrived in', () => {
    const m = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
    const a = event('MutexLockAcquired', { ...m, acquirerId: 'c-1', acquirerLabel: 'c-1', waitDurationNanos: 0 })
    const u = event('MutexUnlocked', { ...m, releaserId: 'c-1', releaserLabel: 'c-1', nextWaiterId: null, holdDurationNanos: 0 })
    const arrived = [u, a]
    expect(projectSync(arrived).mutexes[0]).toMatchObject({ isLocked: false })
    expect(arrived).toEqual([u, a])
  })

  describe('permits between two state snapshots', () => {
    // A snapshot is the only source of permit counts. An acquire or release between two snapshots only lists
    // an acquirer not listed yet and unsettles the list: whether a snapshot already counted an operation cannot be told
    // from the totals (VizSemaphore records a holder before the acquisition event, and drops it before the
    // release event; a semaphore can start with permits taken).
    const s = { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway' }
    const acquired = (who: string, remainingPermits = 1) =>
      event('SemaphorePermitAcquired', { ...s, acquirerId: who, acquirerLabel: who, remainingPermits, waitDurationNanos: 0 })
    const released = (who: string, holderUnknown = false, newAvailablePermits = 2) =>
      event('SemaphorePermitReleased', { ...s, releaserId: who, releaserLabel: who, newAvailablePermits, holdDurationNanos: 0, holderUnknown })

    it('an acquirer not listed yet is listed, the list is unsettled, and it leaves the queue', () => {
      const queued = stateChanged({ activeHolders: [], activeHolderLabels: [], waitingCoroutines: ['c-1'], waitingLabels: ['c-1'] })
      const [sem] = projectSync([created, queued, acquired('c-1')]).semaphores
      expect(sem!.activeHolders).toEqual([{ id: 'c-1', label: 'c-1', permits: 1 }])
      expect(sem!.unsettled).toBe(true)
      expect(sem!.waitQueue).toEqual([])
    })

    it('a release while A and B are listed unsettles the list: who still holds is unknown', () => {
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 1, activeHolderPermits: [1, 1] }),
        released('c-1', false, 2),
      ]).semaphores
      expect(sem!.unsettled).toBe(true)
    })

    it('a snapshot settles the list again', () => {
      expect(projectSync([created]).semaphores[0]!.unsettled).toBe(false)
      const [sem] = projectSync([created, acquired('c-1'), stateChanged({ availablePermits: 1, activeHolderPermits: [1, 1] })]).semaphores
      expect(sem!.unsettled).toBe(false)
    })

    it('a listed acquirer keeps its count: the snapshot may have counted this permit already', () => {
      // Three permits, one taken at creation: the snapshot lists c-1 and c-2 with one each, then c-2's own
      // acquisition event arrives. Its count stays one.
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 0, activeHolderPermits: [1, 1] }),
        acquired('c-2', 0),
      ]).semaphores
      expect(sem!.activeHolders).toEqual([
        { id: 'c-1', label: 'order-1', permits: 1 },
        { id: 'c-2', label: 'order-2', permits: 1 },
      ])
    })

    it('a release changes no counts until the next snapshot', () => {
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 2, activeHolderPermits: [1, 1], unknownHolderPermits: 1 }),
        released('c-1', false, 2),
      ]).semaphores
      expect(sem!.activeHolders.map(h => h.id)).toEqual(['c-1', 'c-2'])
      expect(sem!.unknownHolderPermits).toBe(1)
    })

    it('a release by a coroutine that held none changes no counts either', () => {
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 1, activeHolderPermits: [1, 1] }),
        released('c-9', true, 2),
      ]).semaphores
      expect(sem!.activeHolders.map(h => h.permits)).toEqual([1, 1])
      expect(sem!.unknownHolderPermits).toBe(0)
    })

    it('when a release frees every permit, nobody holds one', () => {
      const two = event('SemaphoreCreated', { ...s, totalPermits: 2 })
      const [sem] = projectSync([two, acquired('c-1', 1), released('c-9', true, 2)]).semaphores
      expect(sem!.activeHolders).toEqual([])
      expect(sem!.unknownHolderPermits).toBe(0)
    })

    it('a snapshot with every permit free means nobody holds one, whatever it lists', () => {
      // VizSemaphore copies the holders before it reads the free permits; a release can fall between.
      const [sem] = projectSync([
        created,
        stateChanged({ availablePermits: 3, activeHolders: ['c-1'], activeHolderLabels: ['c-1'], activeHolderPermits: [1], unknownHolderPermits: 1 }),
      ]).semaphores
      expect(sem!.activeHolders).toEqual([])
      expect(sem!.unknownHolderPermits).toBe(0)
    })

    it('the next snapshot confirms or replaces what the operations listed', () => {
      const [sem] = projectSync([
        created,
        acquired('c-1'),
        stateChanged({ availablePermits: 2, activeHolders: ['c-1'], activeHolderLabels: ['c-1'], activeHolderPermits: [1] }),
      ]).semaphores
      expect(sem!.activeHolders).toEqual([{ id: 'c-1', label: 'c-1', permits: 1 }])
      expect(sem!.unsettled).toBe(false)
    })
  })

  it('knows a semaphore\'s free permits only once an event reports them, not from its creation', () => {
    expect(projectSync([created]).semaphores[0]!.availableKnown).toBe(false)
    expect(projectSync([created, stateChanged({ availablePermits: 1 })]).semaphores[0]!.availableKnown).toBe(true)
  })

  describe('a failed try-acquire reports the free permits', () => {
    const one = event('SemaphoreCreated', { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', totalPermits: 2 })
    const held = stateChanged({ totalPermits: 2, availablePermits: 0, activeHolderPermits: [1, 1] })
    const failed = (availablePermits: number) =>
      event('SemaphoreTryAcquireFailed', {
        semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway', requesterId: 'c-9', requesterLabel: 'c-9', availablePermits, permitsRequested: 1,
      })

    it('when it reports fewer permits in use than listed, who still holds is unknown', () => {
      const [sem] = projectSync([one, held, failed(1)]).semaphores
      expect(sem!.unsettled).toBe(true)
    })

    it('when it reports every permit free, nobody holds one', () => {
      const [sem] = projectSync([one, held, failed(2)]).semaphores
      expect(sem!.activeHolders).toEqual([])
    })

    it('when it reports what the snapshot said, the holders stay settled', () => {
      const [sem] = projectSync([one, held, failed(0)]).semaphores
      expect(sem!.unsettled).toBe(false)
      expect(sem!.activeHolders.map(h => h.id)).toEqual(['c-1', 'c-2'])
    })
  })
})


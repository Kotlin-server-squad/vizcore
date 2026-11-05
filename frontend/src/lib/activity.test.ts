/** Unit tests for the node's second line (S-6): what a coroutine waits for or holds. */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { ordinal, projectActivity } from './activity'

let seq = 0
function ev(kind: string, fields: Record<string, unknown> = {}): VizEvent {
  seq += 1
  return { sessionId: 's', seq, tsNanos: seq, kind, ...fields } as unknown as VizEvent
}
const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }
const sem = { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway' }
const waitFor = (id: string, waitKind: string, resourceId: string | null) =>
  ev('coroutine.suspended', { coroutineId: id, waitKind, resourceId, reason: waitKind.toLowerCase() })
const queue = (ids: string[]) => ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: ids, waitingLabels: ids })
const permits = (holders: string[], counts: number[], waiting: string[] = [], unknown = 0) =>
  ev('SemaphoreStateChanged', {
    ...sem, availablePermits: 0, totalPermits: 3, activeHolders: holders, activeHolderLabels: holders,
    activeHolderPermits: counts, waitingCoroutines: waiting, waitingLabels: waiting, unknownHolderPermits: unknown,
  })

describe('ordinal', () => {
  it.each([[1, '1st'], [2, '2nd'], [3, '3rd'], [4, '4th'], [11, '11th'], [12, '12th'], [13, '13th'], [21, '21st'], [102, '102nd']])(
    '%i is %s', (n, text) => expect(ordinal(n)).toBe(text),
  )
})

describe('projectActivity', () => {
  it('a lock holder holds it, neutrally', () => {
    const lines = projectActivity([ev('MutexCreated', lock), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1' })])
    expect(lines.get('c-1')).toEqual({ text: 'holds inventory-lock', waiting: false })
  })

  it('a waiter waits for the lock at its place in the latest queue', () => {
    const lines = projectActivity([
      ev('MutexCreated', lock), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1' }),
      queue(['c-2', 'c-3']), waitFor('c-2', 'MUTEX', 'm-1'), waitFor('c-3', 'MUTEX', 'm-1'),
    ])
    expect(lines.get('c-2')).toEqual({ text: 'waits for inventory-lock · 1st', waiting: true })
    expect(lines.get('c-3')?.text).toBe('waits for inventory-lock · 2nd')
  })

  it('a resume ends the wait, and the next holder holds the lock', () => {
    const lines = projectActivity([
      ev('MutexCreated', lock), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-1' }), queue(['c-2']),
      waitFor('c-2', 'MUTEX', 'm-1'), ev('MutexUnlocked', { ...lock, releaserId: 'c-1' }),
      ev('coroutine.resumed', { coroutineId: 'c-2' }), ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2' }), queue([]),
    ])
    expect(lines.has('c-1')).toBe(false)
    expect(lines.get('c-2')).toEqual({ text: 'holds inventory-lock', waiting: false })
  })

  it('a queue alone is no wait: the line needs the suspension the wrapper recorded', () => {
    // An older producer records no wait kind; and a waiter that has resumed can still be listed until the
    // next queue event. Neither may read as waiting.
    const lines = projectActivity([ev('MutexCreated', lock), queue(['c-2'])])
    expect(lines.has('c-2')).toBe(false)
    const resumed = projectActivity([ev('MutexCreated', lock), queue(['c-2']), waitFor('c-2', 'MUTEX', 'm-1'), ev('coroutine.resumed', { coroutineId: 'c-2' })])
    expect(resumed.has('c-2')).toBe(false)
  })

  it('a wait that arrived after its own resume is still over (seq order, not arrival order)', () => {
    const suspended = waitFor('c-2', 'MUTEX', 'm-1')
    const resumed = ev('coroutine.resumed', { coroutineId: 'c-2' })
    expect(projectActivity([resumed, suspended]).has('c-2')).toBe(false)
  })

  it('names a lock by its id when it has no label', () => {
    const lines = projectActivity([ev('MutexLockAcquired', { mutexId: 'mutex-7', mutexLabel: null, acquirerId: 'c-1' })])
    expect(lines.get('c-1')?.text).toBe('holds mutex-7')
  })

  it('permits are counted, never numbered', () => {
    const lines = projectActivity([ev('SemaphoreCreated', { ...sem, totalPermits: 3 }), permits(['c-1', 'c-2'], [1, 2])])
    expect(lines.get('c-1')?.text).toBe('holds a payment-gateway permit')
    expect(lines.get('c-2')?.text).toBe('holds 2 payment-gateway permits')
  })

  it('a permit waiter waits for a permit at its place', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 1 }), permits(['c-1'], [1], ['c-4', 'c-5']),
      waitFor('c-4', 'SEMAPHORE', 'sem-1'), waitFor('c-5', 'SEMAPHORE', 'sem-1'),
    ])
    expect(lines.get('c-5')).toEqual({ text: 'waits for a payment-gateway permit · 2nd', waiting: true })
  })

  it('says "an" before a vowel', () => {
    const lines = projectActivity([
      ev('SemaphoreStateChanged', {
        semaphoreId: 'sem-2', semaphoreLabel: 'inventory', availablePermits: 0, totalPermits: 1,
        activeHolders: ['c-1'], activeHolderLabels: ['c-1'], waitingCoroutines: [], waitingLabels: [],
      }),
    ])
    expect(lines.get('c-1')?.text).toBe('holds an inventory permit')
  })

  it('channel waits say which side and which channel', () => {
    const lines = projectActivity([
      ev('ChannelCreated', { channelId: 'ch-1', name: 'orders', capacity: 0, channelType: 'RENDEZVOUS' }),
      waitFor('c-1', 'CHANNEL_SEND', 'ch-1'), waitFor('c-2', 'CHANNEL_RECEIVE', 'ch-1'),
    ])
    expect(lines.get('c-1')?.text).toBe('waits to send to orders')
    expect(lines.get('c-2')?.text).toBe('waits to receive from orders')
  })

  it('a wait whose resource is not named says what kind it is', () => {
    const lines = projectActivity([
      waitFor('c-1', 'MUTEX', null), waitFor('c-2', 'SEMAPHORE', null),
      waitFor('c-3', 'CHANNEL_SEND', null), waitFor('c-4', 'CHANNEL_RECEIVE', null),
    ])
    expect([1, 2, 3, 4].map(i => lines.get(`c-${i}`)?.text)).toEqual([
      'waits for a lock', 'waits for a permit', 'waits to send', 'waits to receive',
    ])
  })

  it('a coroutine holding one lock while waiting for another says the wait first', () => {
    const lines = projectActivity([
      ev('MutexLockAcquired', { mutexId: 'm-a', mutexLabel: 'lock-a', acquirerId: 'c-1' }),
      ev('MutexLockAcquired', { mutexId: 'm-b', mutexLabel: 'lock-b', acquirerId: 'c-2' }),
      ev('MutexQueueChanged', { mutexId: 'm-b', mutexLabel: 'lock-b', waitingCoroutineIds: ['c-1'], waitingLabels: ['c-1'] }),
      waitFor('c-1', 'MUTEX', 'm-b'),
    ])
    expect(lines.get('c-1')).toEqual({ text: 'waits for lock-b · 1st, holds lock-a', waiting: true })
  })

  it('a delay is no wait', () => {
    expect(projectActivity([ev('coroutine.suspended', { coroutineId: 'c-1', reason: 'delay' })]).has('c-1')).toBe(false)
  })

  it('when a permit came back from a coroutine that held none, its holder is unknown', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphoreStateChanged', {
        ...sem, availablePermits: 2, totalPermits: 3, activeHolders: ['c-1', 'c-2'], activeHolderLabels: ['c-1', 'c-2'],
        activeHolderPermits: [1, 1], waitingCoroutines: [], waitingLabels: [], unknownHolderPermits: 1,
      }),
    ])
    expect(lines.get('c-1')).toEqual({ text: 'may hold a payment-gateway permit · holder unknown', waiting: false })
    expect(lines.get('c-2')?.text).toBe('may hold a payment-gateway permit · holder unknown')
  })

  it('a holder listed only by its acquisition, before a snapshot confirms it, may hold the permit', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphorePermitAcquired', { ...sem, acquirerId: 'c-1', acquirerLabel: 'c-1', remainingPermits: 2, waitDurationNanos: 0 }),
    ])
    expect(lines.get('c-1')).toEqual({ text: 'may hold a payment-gateway permit · holder unknown', waiting: false })
  })

  it('no one holds a permit when a snapshot has every permit free', () => {
    const lines = projectActivity([
      ev('SemaphoreCreated', { ...sem, totalPermits: 3 }),
      ev('SemaphoreStateChanged', {
        ...sem, availablePermits: 3, totalPermits: 3, activeHolders: ['c-1'], activeHolderLabels: ['c-1'],
        activeHolderPermits: [1], waitingCoroutines: [], waitingLabels: [], unknownHolderPermits: 1,
      }),
    ])
    expect(lines.has('c-1')).toBe(false)
  })
})


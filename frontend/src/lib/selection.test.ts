/** Unit tests for the page's one selection (S-2): what each event is about, and what a selection's events are. */
import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { eventConcerns, eventSubject, eventTitle, namesCoroutine, resourceIds, resourceNames } from './selection'

function ev(kind: string, fields: Record<string, unknown> = {}): VizEvent {
  return { sessionId: 's', seq: 1, tsNanos: 1, kind, ...fields } as unknown as VizEvent
}

const lock = { mutexId: 'm-1', mutexLabel: 'inventory-lock' }

describe('eventSubject', () => {
  it('a coroutine event is about its coroutine', () => {
    expect(eventSubject(ev('coroutine.suspended', { coroutineId: 'c-1', resourceId: 'm-1' }))).toEqual({ kind: 'coroutine', id: 'c-1' })
  })

  it.each([
    ['MutexLockRequested', { requesterId: 'c-2' }],
    ['MutexLockAcquired', { acquirerId: 'c-2' }],
    ['MutexUnlocked', { releaserId: 'c-2' }],
    ['MutexTryLockFailed', { requesterId: 'c-2' }],
  ])('%s is about the requester, acquirer or releaser', (kind, who) => {
    expect(eventSubject(ev(kind, { ...lock, ...who }))).toEqual({ kind: 'coroutine', id: 'c-2' })
  })

  it.each([
    ['SemaphoreAcquireRequested', { requesterId: 'c-3' }],
    ['SemaphorePermitAcquired', { acquirerId: 'c-3' }],
    ['SemaphorePermitReleased', { releaserId: 'c-3', holderUnknown: true }],
  ])('%s is about its coroutine', (kind, who) => {
    expect(eventSubject(ev(kind, { semaphoreId: 'sem-1', ...who }))).toEqual({ kind: 'coroutine', id: 'c-3' })
  })

  it('an event about a resource only selects the resource', () => {
    expect(eventSubject(ev('MutexCreated', { ...lock, ownerCoroutineId: null }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
    expect(eventSubject(ev('MutexQueueChanged', { ...lock, waitingCoroutineIds: [] }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
    expect(eventSubject(ev('SemaphoreStateChanged', { semaphoreId: 'sem-1' }))).toEqual({ kind: 'resource', resource: 'semaphore', id: 'sem-1' })
    expect(eventSubject(ev('ChannelCreated', { channelId: 'ch-1', name: 'orders' }))).toEqual({ kind: 'resource', resource: 'channel', id: 'ch-1' })
  })

  it('a release made outside any coroutine is about the lock, not a coroutine named "unknown"', () => {
    expect(eventSubject(ev('MutexUnlocked', { ...lock, releaserId: 'unknown' }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
    expect(eventSubject(ev('SemaphorePermitReleased', { semaphoreId: 'sem-1', releaserId: 'unknown' }))).toEqual({ kind: 'resource', resource: 'semaphore', id: 'sem-1' })
    expect(eventSubject(ev('MutexLockRequested', { ...lock, requesterId: 'unknown-12345' }))).toEqual({ kind: 'resource', resource: 'mutex', id: 'm-1' })
  })

  it('a deadlock or a coroutine-less flow event selects nothing', () => {
    expect(eventSubject(ev('DeadlockDetected', { involvedCoroutines: ['c-1', 'c-2'] }))).toBeNull()
    expect(eventSubject(ev('FlowOperatorApplied', { flowId: 'f-1', coroutineId: null }))).toBeNull()
  })
})

describe('eventConcerns', () => {
  it('a coroutine\'s events include the lock and permit events about it', () => {
    const sel = { kind: 'coroutine', id: 'c-2' } as const
    expect(eventConcerns(ev('coroutine.started', { coroutineId: 'c-2' }), sel)).toBe(true)
    expect(eventConcerns(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2' }), sel)).toBe(true)
    expect(eventConcerns(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-9' }), sel)).toBe(false)
    expect(eventConcerns(ev('MutexCreated', lock), sel)).toBe(false)
  })

  it('a resource\'s events are those naming it, and the suspensions waiting for it', () => {
    const sel = { kind: 'resource', resource: 'mutex', id: 'm-1' } as const
    expect(eventConcerns(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2' }), sel)).toBe(true)
    expect(eventConcerns(ev('coroutine.suspended', { coroutineId: 'c-2', waitKind: 'MUTEX', resourceId: 'm-1' }), sel)).toBe(true)
    expect(eventConcerns(ev('MutexCreated', { mutexId: 'm-2' }), sel)).toBe(false)
    expect(eventConcerns(ev('coroutine.started', { coroutineId: 'c-2' }), sel)).toBe(false)
  })
})

describe('resourceNames', () => {
  it('names locks, semaphores and channels by their labels', () => {
    const names = resourceNames([
      ev('MutexCreated', lock),
      ev('SemaphoreCreated', { semaphoreId: 'sem-1', semaphoreLabel: 'payment-gateway' }),
      ev('ChannelCreated', { channelId: 'ch-1', name: 'orders' }),
      ev('MutexCreated', { mutexId: 'm-2', mutexLabel: null }),
    ])
    expect(Object.fromEntries(names)).toEqual({ 'm-1': 'inventory-lock', 'sem-1': 'payment-gateway', 'ch-1': 'orders' })
  })
})

describe('eventTitle', () => {
  it('names a coroutine event by its coroutine', () => {
    expect(eventTitle(ev('coroutine.started', { coroutineId: 'c-1', label: 'order-1' }))).toBe('order-1')
  })

  it('names a lock or permit event by who and what', () => {
    expect(eventTitle(ev('MutexLockAcquired', { ...lock, acquirerId: 'c-2', acquirerLabel: 'order-2' }))).toBe('order-2 · inventory-lock')
    expect(eventTitle(ev('MutexCreated', lock))).toBe('inventory-lock')
    expect(eventTitle(ev('SemaphorePermitReleased', { semaphoreId: 'sem-1', semaphoreLabel: null, releaserId: 'c-3', releaserLabel: null }))).toBe('c-3 · sem-1')
  })

  it('names a channel event by its channel, from the page\'s names', () => {
    const names = new Map([['ch-1', 'orders']])
    expect(eventTitle(ev('ChannelCreated', { channelId: 'ch-1', name: 'orders' }))).toBe('orders')
    expect(eventTitle(ev('ChannelClosed', { channelId: 'ch-1', cause: null }), names)).toBe('orders')
    expect(eventTitle(ev('ChannelBufferStateChanged', { channelId: 'ch-1', currentSize: 1, capacity: 2 }))).toBe('ch-1')
    expect(eventTitle(ev('ChannelSendStarted', { channelId: 'ch-1', coroutineId: 'c-1' }), names)).toBe('c-1 · orders')
  })

  it('says "outside any coroutine" for a wrapper used outside one', () => {
    expect(eventTitle(ev('MutexUnlocked', { ...lock, releaserId: 'unknown', releaserLabel: null }))).toBe('outside any coroutine · inventory-lock')
  })
})

describe('namesCoroutine', () => {
  it('is false for the ids the wrappers write when there was no coroutine', () => {
    expect(namesCoroutine('c-1')).toBe(true)
    expect(namesCoroutine('unknown')).toBe(false)
    expect(namesCoroutine('unknown-123')).toBe(false)
    expect(namesCoroutine(null)).toBe(false)
  })
})

describe('resourceIds', () => {
  it('lists every lock, semaphore and channel the events name, labelled or not', () => {
    const ids = resourceIds([
      ev('MutexCreated', { mutexId: 'm-2', mutexLabel: null }),
      ev('SemaphoreStateChanged', { semaphoreId: 'sem-1' }),
      ev('ChannelClosed', { channelId: 'ch-1' }),
      ev('coroutine.started', { coroutineId: 'c-1' }),
    ])
    expect([...ids].sort()).toEqual(['ch-1', 'm-2', 'sem-1'])
  })
})


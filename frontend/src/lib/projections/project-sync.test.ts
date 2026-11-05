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
})

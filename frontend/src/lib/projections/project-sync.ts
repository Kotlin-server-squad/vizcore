/**
 * Pure reducer: VizEvent[] -> what each mutex and semaphore looks like after these events (spec S-6).
 *
 * Moved out of useSyncEvents so the Evidence section and the tree's second line read one derivation, from
 * whichever events the page shows: the live stream's, the stored history's, or the replay cursor's.
 * Queues are the latest observed queue. Permits are counted per holder, never numbered; permits that came
 * back from a coroutine that held none have no known holder (S-7) and are counted apart.
 */
import { SYNC_EVENT_KINDS } from '@/types/api'
import type {
  VizEvent,
  MutexCreated,
  MutexLockRequested,
  MutexLockAcquired,
  MutexUnlocked,
  MutexTryLockFailed,
  MutexQueueChanged,
  SemaphoreCreated,
  SemaphoreAcquireRequested,
  SemaphorePermitAcquired,
  SemaphorePermitReleased,
  SemaphoreTryAcquireFailed,
  SemaphoreStateChanged,
  DeadlockDetected,
  PotentialDeadlockWarning,
  SyncEvent,
} from '@/types/api'

/** A coroutine named in a lock or permit event. */
export interface Holder {
  id: string
  label: string | null
}

/** A semaphore holder and how many permits it holds. */
export interface SemaphoreHolder extends Holder {
  permits: number
}

/** Tracked state for a single mutex. */
export interface MutexState {
  mutexId: string
  label: string | null
  isLocked: boolean
  currentHolder: string | null
  currentHolderLabel: string | null
  waitQueue: Holder[]
  contentionCount: number
  lockAcquisitions: number
}

/** Tracked state for a single semaphore. */
export interface SemaphoreState {
  semaphoreId: string
  label: string | null
  totalPermits: number
  availablePermits: number
  activeHolders: SemaphoreHolder[]
  /** Permits in use whose holder is unknown: released by a coroutine that held none (S-7). */
  unknownHolderPermits: number
  waitQueue: Holder[]
}

export interface SyncProjection {
  syncEvents: SyncEvent[]
  mutexes: MutexState[]
  semaphores: SemaphoreState[]
  deadlocks: DeadlockDetected[]
  warnings: PotentialDeadlockWarning[]
  hasDeadlock: boolean
}

export function projectSync(events: readonly VizEvent[] | undefined): SyncProjection {
  const syncEvents: SyncEvent[] = []
  const mutexStates = new Map<string, MutexState>()
  const semaphoreStates = new Map<string, SemaphoreState>()
  const deadlocks: DeadlockDetected[] = []
  const warnings: PotentialDeadlockWarning[] = []

  if (!events || events.length === 0) {
    return {
      syncEvents: [],
      mutexes: [],
      semaphores: [],
      deadlocks: [],
      warnings: [],
      hasDeadlock: false,
    }
  }

  for (const event of events) {
    if (!SYNC_EVENT_KINDS.has(event.kind)) continue

    const syncEvent = event as unknown as SyncEvent
    syncEvents.push(syncEvent)

    switch (syncEvent.kind) {
      case 'MutexCreated': {
        const e = syncEvent as MutexCreated
        mutexStates.set(e.mutexId, {
          mutexId: e.mutexId,
          label: e.mutexLabel,
          isLocked: false,
          currentHolder: e.ownerCoroutineId,
          currentHolderLabel: null,
          waitQueue: [],
          contentionCount: 0,
          lockAcquisitions: 0,
        })
        break
      }
      case 'MutexLockRequested': {
        const e = syncEvent as MutexLockRequested
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        if (e.isLocked) {
          state.contentionCount++
        }
        break
      }
      case 'MutexLockAcquired': {
        const e = syncEvent as MutexLockAcquired
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        state.isLocked = true
        state.currentHolder = e.acquirerId
        state.currentHolderLabel = e.acquirerLabel
        state.lockAcquisitions++
        // Remove from wait queue
        state.waitQueue = state.waitQueue.filter((w) => w.id !== e.acquirerId)
        break
      }
      case 'MutexUnlocked': {
        const e = syncEvent as MutexUnlocked
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        state.isLocked = false
        state.currentHolder = null
        state.currentHolderLabel = null
        break
      }
      case 'MutexTryLockFailed': {
        const e = syncEvent as MutexTryLockFailed
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        mutexStates.get(e.mutexId)!.contentionCount++
        break
      }
      case 'MutexQueueChanged': {
        const e = syncEvent as MutexQueueChanged
        ensureMutex(mutexStates, e.mutexId, e.mutexLabel)
        const state = mutexStates.get(e.mutexId)!
        state.waitQueue = e.waitingCoroutineIds.map((id, i) => ({
          id,
          label: e.waitingLabels[i] ?? null,
        }))
        break
      }
      case 'SemaphoreCreated': {
        const e = syncEvent as SemaphoreCreated
        semaphoreStates.set(e.semaphoreId, {
          semaphoreId: e.semaphoreId,
          label: e.semaphoreLabel,
          totalPermits: e.totalPermits,
          availablePermits: e.totalPermits,
          activeHolders: [],
          unknownHolderPermits: 0,
          waitQueue: [],
        })
        break
      }
      case 'SemaphoreAcquireRequested': {
        const e = syncEvent as SemaphoreAcquireRequested
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        break
      }
      case 'SemaphorePermitAcquired': {
        const e = syncEvent as SemaphorePermitAcquired
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.remainingPermits
        break
      }
      case 'SemaphorePermitReleased': {
        const e = syncEvent as SemaphorePermitReleased
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.newAvailablePermits
        break
      }
      case 'SemaphoreTryAcquireFailed': {
        const e = syncEvent as SemaphoreTryAcquireFailed
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        break
      }
      case 'SemaphoreStateChanged': {
        const e = syncEvent as SemaphoreStateChanged
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.availablePermits
        state.totalPermits = e.totalPermits
        state.activeHolders = e.activeHolders.map((id, i) => ({
          id,
          label: e.activeHolderLabels[i] ?? null,
          // An older producer sends no counts: each listed holder held one.
          permits: e.activeHolderPermits?.[i] ?? 1,
        }))
        state.unknownHolderPermits = e.unknownHolderPermits ?? 0
        state.waitQueue = e.waitingCoroutines.map((id, i) => ({
          id,
          label: e.waitingLabels[i] ?? null,
        }))
        break
      }
      case 'DeadlockDetected': {
        deadlocks.push(syncEvent as DeadlockDetected)
        break
      }
      case 'PotentialDeadlockWarning': {
        warnings.push(syncEvent as PotentialDeadlockWarning)
        break
      }
    }
  }

  return {
    syncEvents,
    mutexes: Array.from(mutexStates.values()),
    semaphores: Array.from(semaphoreStates.values()),
    deadlocks,
    warnings,
    hasDeadlock: deadlocks.length > 0,
  }
}

function ensureMutex(map: Map<string, MutexState>, id: string, label: string | null) {
  if (!map.has(id)) {
    map.set(id, {
      mutexId: id,
      label,
      isLocked: false,
      currentHolder: null,
      currentHolderLabel: null,
      waitQueue: [],
      contentionCount: 0,
      lockAcquisitions: 0,
    })
  }
}

function ensureSemaphore(map: Map<string, SemaphoreState>, id: string, label: string | null) {
  if (!map.has(id)) {
    map.set(id, {
      semaphoreId: id,
      label,
      totalPermits: 0,
      availablePermits: 0,
      activeHolders: [],
      unknownHolderPermits: 0,
      waitQueue: [],
    })
  }
}

/**
 * Pure reducer: VizEvent[] -> what each mutex and semaphore looks like after these events (spec S-6).
 *
 * Moved out of useSyncEvents so the Evidence section and the tree's second line read one derivation, from
 * whichever events the page shows: the live stream's, the stored history's, or the replay cursor's.
 * Queues are the latest observed queue. Permits are counted per holder, never numbered; permits that came
 * back from a coroutine that held none have no known holder (S-7) and are counted apart.
 *
 * Reduced in seq order, whatever order the events arrived in. Two orderings the wrappers produce are handled:
 * VizMutex unlocks before it records the release, so the waiter it woke can record its acquisition first,
 * and the previous holder's late release must not free the lock; and a semaphore's state snapshot follows
 * each acquire and release, so the operation events themselves update the holders in between (a stream
 * flush or a replay cursor can fall between the two), and the next snapshot replaces what they implied.
 */
import { SYNC_EVENT_KINDS } from '@/types/api'
import { inSeqOrder } from './in-seq-order'
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
  /**
   * Listed permits beyond those in use (S-7): a permit came back from a coroutine that held none, so some
   * listed holder no longer holds one, and which is unknown. Not permits held by nobody known.
   */
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

  // Per lock, holders displaced by a later acquisition whose own release is still to be recorded: VizMutex
  // records a release after the lock has passed on. A multiset, as several handovers can come first.
  const pendingReleases = new Map<string, string[]>()

  for (const event of inSeqOrder(events)) {
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
        // A coroutine records its release before it can acquire again: none of its own is pending any more.
        const pending = (pendingReleases.get(e.mutexId) ?? []).filter(id => id !== e.acquirerId)
        if (state.isLocked && state.currentHolder && state.currentHolder !== e.acquirerId) pending.push(state.currentHolder)
        pendingReleases.set(e.mutexId, pending)
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
        // A displaced holder's release, recorded after the next acquisition: the lock is not free.
        const pending = pendingReleases.get(e.mutexId) ?? []
        const late = pending.indexOf(e.releaserId)
        if (late >= 0) {
          pendingReleases.set(e.mutexId, pending.filter((_, i) => i !== late))
          break
        }
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
        // Counted now; the snapshot that follows replaces the list (a flush or a cursor can fall between).
        // VizSemaphore records the holder before the event, so a snapshot may already list this permit: a
        // listed acquirer gains one only while fewer permits are listed than are in use.
        const held = state.activeHolders.find(h => h.id === e.acquirerId)
        if (!held) {
          state.activeHolders = [...state.activeHolders, { id: e.acquirerId, label: e.acquirerLabel, permits: 1 }]
        } else if (state.totalPermits === 0 || listedPermits(state) < permitsInUse(state)) {
          state.activeHolders = state.activeHolders.map(h => (h === held ? { ...h, permits: h.permits + 1 } : h))
        }
        state.waitQueue = state.waitQueue.filter(w => w.id !== e.acquirerId)
        reconcilePermits(state)
        break
      }
      case 'SemaphorePermitReleased': {
        const e = syncEvent as SemaphorePermitReleased
        ensureSemaphore(semaphoreStates, e.semaphoreId, e.semaphoreLabel)
        const state = semaphoreStates.get(e.semaphoreId)!
        state.availablePermits = e.newAvailablePermits
        // Its own release takes one back, unless a snapshot already did (the wrapper drops the record first).
        // A release by a coroutine that held none (S-7) frees a listed permit whose holder is unknown: the
        // listed permits beyond those in use say how many.
        const held = e.holderUnknown ? undefined : state.activeHolders.find(h => h.id === e.releaserId)
        if (held && (state.totalPermits === 0 || listedPermits(state) > permitsInUse(state))) {
          state.activeHolders = state.activeHolders
            .map(h => (h === held ? { ...h, permits: h.permits - 1 } : h))
            .filter(h => h.permits > 0)
        } else if (!held && state.totalPermits === 0) {
          state.unknownHolderPermits = Math.min(state.unknownHolderPermits + 1, listedPermits(state))
        }
        reconcilePermits(state)
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

/** Permits the holders are listed with. */
function listedPermits(state: SemaphoreState): number {
  return state.activeHolders.reduce((sum, h) => sum + h.permits, 0)
}

function permitsInUse(state: SemaphoreState): number {
  return state.totalPermits - state.availablePermits
}

/**
 * After an acquire or release: the listed permits beyond those in use have an unknown holder (the producer's
 * own definition), and with every permit free nobody holds one. Needs the total, which a trimmed history may
 * lack (`SemaphoreCreated` gone): then the counts are left as they are until the next snapshot.
 */
function reconcilePermits(state: SemaphoreState) {
  if (state.totalPermits === 0) return
  const inUse = permitsInUse(state)
  if (inUse <= 0) {
    state.activeHolders = []
    state.unknownHolderPermits = 0
    return
  }
  state.unknownHolderPermits = Math.max(0, listedPermits(state) - inUse)
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

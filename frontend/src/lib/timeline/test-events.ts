/** Event builders for the timeline's tests: one session, seqs and times in order unless a test sets them. */
import type { VizEvent } from '@/types/api'

export class Run {
  private seq = 0
  readonly events: VizEvent[] = []

  /** Adds an event; its time is seq × 1 ms unless `ts` says otherwise. */
  add(kind: string, fields: Record<string, unknown> = {}, ts?: number): VizEvent {
    this.seq += 1
    const event = { sessionId: 's', seq: this.seq, tsNanos: ts ?? this.seq * 1_000_000, kind, ...fields } as unknown as VizEvent
    this.events.push(event)
    return event
  }

  life(kind: string, id: string, extra: Record<string, unknown> = {}, ts?: number) {
    return this.add(`coroutine.${kind}`, { coroutineId: id, jobId: `job-${id}`, parentCoroutineId: null, scopeId: 'io', label: id, ...extra }, ts)
  }

  created(id: string, ts?: number) {
    return this.life('created', id, {}, ts)
  }

  lock(mutexId: string, id: string, ts?: number) {
    return this.add('MutexLockAcquired', { mutexId, mutexLabel: mutexId, acquirerId: id, acquirerLabel: id, waitDurationNanos: 0 }, ts)
  }

  unlock(mutexId: string, id: string, ts?: number) {
    return this.add('MutexUnlocked', { mutexId, mutexLabel: mutexId, releaserId: id, releaserLabel: id, nextWaiterId: null, holdDurationNanos: 0 }, ts)
  }

  semCreated(semaphoreId: string, totalPermits: number) {
    return this.add('SemaphoreCreated', { semaphoreId, semaphoreLabel: semaphoreId, totalPermits })
  }

  acquire(semaphoreId: string, id: string, remainingPermits: number) {
    return this.add('SemaphorePermitAcquired', { semaphoreId, semaphoreLabel: semaphoreId, acquirerId: id, acquirerLabel: id, remainingPermits, waitDurationNanos: 0 })
  }

  release(semaphoreId: string, id: string, newAvailablePermits: number, holderUnknown?: boolean) {
    return this.add('SemaphorePermitReleased', {
      semaphoreId,
      semaphoreLabel: semaphoreId,
      releaserId: id,
      releaserLabel: id,
      newAvailablePermits,
      holdDurationNanos: 0,
      ...(holderUnknown === undefined ? {} : { holderUnknown }),
    })
  }

  /** A state snapshot: `holders` is id → permits; `unknown` permits whose holder is unknown. */
  snapshot(semaphoreId: string, totalPermits: number, availablePermits: number, holders: Record<string, number>, unknown = 0) {
    return this.add('SemaphoreStateChanged', {
      semaphoreId,
      semaphoreLabel: semaphoreId,
      availablePermits,
      totalPermits,
      activeHolders: Object.keys(holders),
      activeHolderLabels: Object.keys(holders),
      activeHolderPermits: Object.values(holders),
      unknownHolderPermits: unknown,
      waitingCoroutines: [],
      waitingLabels: [],
    })
  }
}

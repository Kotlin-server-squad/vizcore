/**
 * Completeness regression test for the SSE listener list (REVIEW CR-01).
 *
 * A browser EventSource only delivers named SSE events that have a registered
 * listener — any backend event kind missing from SSE_EVENT_TYPES is SILENTLY
 * dropped from the live view. The original defect: only 17 PascalCase kinds
 * (plus 9 legacy kebab-case names) of the backend's 66 registered kinds had
 * listeners, so every Channel, Flow, Mutex, Semaphore, Actor, Select,
 * Deadlock, AntiPatternDetected and WaitingForChildren event vanished in
 * live mode.
 *
 * BACKEND_EVENT_KINDS below is a literal fixture mirroring the backend's
 * VizEventSerializersModule.kt (the polymorphic registry naming each SSE
 * event after its `kind`). It is intentionally NOT derived from the frontend
 * constants — deriving both sides from the same source would make the test
 * a tautology.
 */
import { describe, it, expect } from 'vitest'
import { SSE_EVENT_TYPES } from './use-event-stream'
import {
  COROUTINE_EVENT_KINDS,
  DISPATCHER_EVENT_KINDS,
  DEFERRED_EVENT_KINDS,
  JOB_EVENT_KINDS,
  CHANNEL_EVENT_KINDS,
  FLOW_EVENT_KINDS,
  SYNC_EVENT_KINDS,
  ACTOR_EVENT_KINDS,
  SELECT_EVENT_KINDS,
} from '@/types/api'

/** All 66 event kinds registered in backend VizEventSerializersModule.kt. */
const BACKEND_EVENT_KINDS: readonly string[] = [
  // coroutine package (8)
  'CoroutineBodyCompleted',
  'CoroutineCancelled',
  'CoroutineCompleted',
  'CoroutineCreated',
  'CoroutineFailed',
  'CoroutineResumed',
  'CoroutineStarted',
  'CoroutineSuspended',
  // job package (4)
  'JobCancellationRequested',
  'JobJoinCompleted',
  'JobJoinRequested',
  'JobStateChanged',
  // flow package (13)
  'FlowBackpressure',
  'FlowBufferOverflow',
  'FlowCollectionCancelled',
  'FlowCollectionCompleted',
  'FlowCollectionStarted',
  'FlowCreated',
  'FlowOperatorApplied',
  'FlowValueEmitted',
  'FlowValueFiltered',
  'FlowValueTransformed',
  'SharedFlowEmission',
  'SharedFlowSubscription',
  'StateFlowValueChanged',
  // dispatcher package (2)
  'DispatcherSelected',
  'ThreadAssigned',
  // deferred package (3)
  'DeferredAwaitCompleted',
  'DeferredAwaitStarted',
  'DeferredValueAvailable',
  // channel package (9)
  'ChannelBufferStateChanged',
  'ChannelClosed',
  'ChannelCreated',
  'ChannelReceiveCompleted',
  'ChannelReceiveStarted',
  'ChannelReceiveSuspended',
  'ChannelSendCompleted',
  'ChannelSendStarted',
  'ChannelSendSuspended',
  // mutex (6)
  'MutexCreated',
  'MutexLockAcquired',
  'MutexLockRequested',
  'MutexQueueChanged',
  'MutexTryLockFailed',
  'MutexUnlocked',
  // semaphore (6)
  'SemaphoreAcquireRequested',
  'SemaphoreCreated',
  'SemaphorePermitAcquired',
  'SemaphorePermitReleased',
  'SemaphoreStateChanged',
  'SemaphoreTryAcquireFailed',
  // actor (7)
  'ActorClosed',
  'ActorCreated',
  'ActorMailboxChanged',
  'ActorMessageProcessed',
  'ActorMessageProcessing',
  'ActorMessageSent',
  'ActorStateChanged',
  // select (4)
  'SelectClauseRegistered',
  'SelectClauseWon',
  'SelectCompleted',
  'SelectStarted',
  // deadlock (2)
  'DeadlockDetected',
  'PotentialDeadlockWarning',
  // anti-pattern (1)
  'AntiPatternDetected',
  // structured concurrency (1)
  'WaitingForChildren',
]

describe('SSE_EVENT_TYPES completeness (CR-01)', () => {
  it('the backend fixture itself lists all 66 registered kinds', () => {
    expect(BACKEND_EVENT_KINDS.length).toBe(66)
    expect(new Set(BACKEND_EVENT_KINDS).size).toBe(66)
  })

  it('registers a listener for every backend event kind', () => {
    const listeners = new Set(SSE_EVENT_TYPES)
    const missing = BACKEND_EVENT_KINDS.filter((kind) => !listeners.has(kind))
    expect(missing).toEqual([])
  })

  it('covers every member of the shared category kind sets in types/api.ts', () => {
    const listeners = new Set(SSE_EVENT_TYPES)
    const allSets: ReadonlySet<string>[] = [
      COROUTINE_EVENT_KINDS,
      DISPATCHER_EVENT_KINDS,
      DEFERRED_EVENT_KINDS,
      JOB_EVENT_KINDS,
      CHANNEL_EVENT_KINDS,
      FLOW_EVENT_KINDS,
      SYNC_EVENT_KINDS,
      ACTOR_EVENT_KINDS,
      SELECT_EVENT_KINDS,
    ]
    const missing = allSets.flatMap((set) =>
      Array.from(set).filter((kind) => !listeners.has(kind)),
    )
    expect(missing).toEqual([])
  })

  it('has no duplicate listener registrations', () => {
    expect(new Set(SSE_EVENT_TYPES).size).toBe(SSE_EVENT_TYPES.length)
  })

  it('keeps the legacy kebab-case names for backwards compatibility', () => {
    const listeners = new Set(SSE_EVENT_TYPES)
    const legacy = [
      'coroutine.created',
      'coroutine.started',
      'coroutine.suspended',
      'coroutine.resumed',
      'coroutine.body-completed',
      'coroutine.completed',
      'coroutine.cancelled',
      'coroutine.failed',
      'thread.assigned',
    ]
    expect(legacy.filter((k) => !listeners.has(k))).toEqual([])
  })
})

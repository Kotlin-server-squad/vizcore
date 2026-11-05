/**
 * SSE Event Types for the Coroutine Visualizer.
 *
 * These interfaces mirror the Kotlin data classes in
 * backend/src/main/kotlin/com/jh/proj/coroutineviz/events/.
 *
 * Events are delivered via Server-Sent Events on
 * GET /api/sessions/{id}/stream with the `event` field set to the `kind` value.
 */

// ---------------------------------------------------------------------------
// Base interfaces
// ---------------------------------------------------------------------------

/** Fields present on every event (mirrors VizEvent interface in Kotlin). */
export interface BaseVizEvent {
  sessionId: string;
  seq: number;
  tsNanos: number;
  kind: string;
}

/** Fields present on every coroutine-scoped event (mirrors CoroutineEvent). */
export interface BaseCoroutineEvent extends BaseVizEvent {
  coroutineId: string;
  jobId: string;
  parentCoroutineId: string | null;
  scopeId: string;
  label: string | null;
}

// ---------------------------------------------------------------------------
// Supporting types
// ---------------------------------------------------------------------------

/** Captures information about where a suspension occurred (mirrors SuspensionPoint). */
export interface SuspensionPoint {
  function: string;
  fileName: string | null;
  lineNumber: number | null;
  reason: string;
}

// ---------------------------------------------------------------------------
// Coroutine lifecycle events
// ---------------------------------------------------------------------------

export interface CoroutineCreated extends BaseCoroutineEvent {
  kind: "CoroutineCreated";
}

export interface CoroutineStarted extends BaseCoroutineEvent {
  kind: "CoroutineStarted";
}

export interface CoroutineBodyCompleted extends BaseCoroutineEvent {
  kind: "CoroutineBodyCompleted";
}

/** What a coroutine waited for when a mutex, semaphore or channel wrapper recorded the wait (spec 2026-11-05, S-7). */
export type WaitKind = "MUTEX" | "SEMAPHORE" | "CHANNEL_SEND" | "CHANNEL_RECEIVE";

export interface CoroutineSuspended extends BaseCoroutineEvent {
  kind: "CoroutineSuspended";
  reason: string;
  durationMillis: number | null;
  suspensionPoint: SuspensionPoint | null;
  /** Absent on other suspensions and on events from older producers. */
  waitKind?: WaitKind | null;
  /** The mutexId, semaphoreId or channelId waited for. */
  resourceId?: string | null;
}

export interface CoroutineResumed extends BaseCoroutineEvent {
  kind: "CoroutineResumed";
}

export interface CoroutineCompleted extends BaseCoroutineEvent {
  kind: "CoroutineCompleted";
}

export interface CoroutineCancelled extends BaseCoroutineEvent {
  kind: "CoroutineCancelled";
  cause: string | null;
}

export interface CoroutineFailed extends BaseCoroutineEvent {
  kind: "CoroutineFailed";
  exceptionType: string | null;
  message: string | null;
  stackTrace: string[];
}

// ---------------------------------------------------------------------------
// Job events
// ---------------------------------------------------------------------------

export interface JobStateChanged extends BaseCoroutineEvent {
  kind: "JobStateChanged";
  isActive: boolean;
  isCompleted: boolean;
  isCancelled: boolean;
  childrenCount: number;
}

export interface JobJoinRequested extends BaseCoroutineEvent {
  kind: "JobJoinRequested";
  waitingCoroutineId: string | null;
}

export interface JobJoinCompleted extends BaseCoroutineEvent {
  kind: "JobJoinCompleted";
  waitingCoroutineId: string | null;
}

export interface JobCancellationRequested extends BaseCoroutineEvent {
  kind: "JobCancellationRequested";
  requestedBy: string | null;
  cause: string | null;
}

// ---------------------------------------------------------------------------
// Structured concurrency events
// ---------------------------------------------------------------------------

export interface WaitingForChildren extends BaseCoroutineEvent {
  kind: "WaitingForChildren";
  activeChildrenCount: number;
  activeChildrenIds: string[];
}

// ---------------------------------------------------------------------------
// Dispatcher events
// ---------------------------------------------------------------------------

export interface DispatcherSelected extends BaseCoroutineEvent {
  kind: "DispatcherSelected";
  dispatcherId: string;
  dispatcherName: string;
  queueDepth: number | null;
}

export interface ThreadAssigned extends BaseCoroutineEvent {
  kind: "ThreadAssigned";
  threadId: number;
  threadName: string;
  dispatcherName: string | null;
}

// ---------------------------------------------------------------------------
// Deferred / Async events
// ---------------------------------------------------------------------------

export interface DeferredAwaitStarted extends BaseVizEvent {
  kind: "DeferredAwaitStarted";
  deferredId: string;
  coroutineId: string;
  awaiterId: string | null;
  scopeId: string;
  label: string | null;
}

export interface DeferredAwaitCompleted extends BaseVizEvent {
  kind: "DeferredAwaitCompleted";
  deferredId: string;
  coroutineId: string;
  awaiterId: string | null;
  scopeId: string;
  label: string | null;
}

export interface DeferredValueAvailable extends BaseCoroutineEvent {
  kind: "DeferredValueAvailable";
  deferredId: string;
}

// ---------------------------------------------------------------------------
// Flow events
// ---------------------------------------------------------------------------

export interface FlowCreated extends BaseVizEvent {
  kind: "FlowCreated";
  coroutineId: string;
  flowId: string;
  flowType: string; // "Cold" | "Hot" | "StateFlow" | "SharedFlow"
  label: string | null;
  scopeId: string | null;
}

export interface FlowValueEmitted extends BaseVizEvent {
  kind: "FlowValueEmitted";
  coroutineId: string;
  flowId: string;
  collectorId: string;
  sequenceNumber: number;
  valuePreview: string;
  valueType: string;
}

export interface FlowCollectionStarted extends BaseVizEvent {
  kind: "FlowCollectionStarted";
  coroutineId: string;
  flowId: string;
  collectorId: string;
  label: string | null;
}

export interface FlowCollectionCompleted extends BaseVizEvent {
  kind: "FlowCollectionCompleted";
  coroutineId: string;
  flowId: string;
  collectorId: string;
  totalEmissions: number;
  durationNanos: number;
}

export interface FlowCollectionCancelled extends BaseVizEvent {
  kind: "FlowCollectionCancelled";
  coroutineId: string;
  flowId: string;
  collectorId: string;
  reason: string | null;
  emittedCount: number;
}

export interface FlowOperatorApplied extends BaseVizEvent {
  kind: "FlowOperatorApplied";
  flowId: string;
  sourceFlowId: string;
  operatorName: string;
  operatorIndex: number;
  label: string | null;
  coroutineId: string | null;
}

export interface FlowValueFiltered extends BaseVizEvent {
  kind: "FlowValueFiltered";
  flowId: string;
  operatorName: string;
  valuePreview: string;
  valueType: string;
  passed: boolean;
  sequenceNumber: number;
  coroutineId: string | null;
  collectorId: string | null;
}

export interface FlowValueTransformed extends BaseVizEvent {
  kind: "FlowValueTransformed";
  flowId: string;
  operatorName: string;
  inputValuePreview: string;
  outputValuePreview: string;
  inputType: string;
  outputType: string;
  sequenceNumber: number;
  coroutineId: string | null;
  collectorId: string | null;
}

export interface FlowBackpressure extends BaseVizEvent {
  kind: "FlowBackpressure";
  flowId: string;
  collectorId: string;
  reason: string; // "slow_collector" | "buffer_full" | "conflated"
  pendingEmissions: number;
  bufferCapacity: number | null;
  durationNanos: number | null;
  coroutineId: string | null;
}

export interface FlowBufferOverflow extends BaseVizEvent {
  kind: "FlowBufferOverflow";
  coroutineId: string;
  flowId: string;
  droppedValue: string | null;
  bufferSize: number;
  overflowStrategy: string; // "SUSPEND" | "DROP_LATEST" | "DROP_OLDEST"
}

export interface SharedFlowEmission extends BaseVizEvent {
  kind: "SharedFlowEmission";
  flowId: string;
  valuePreview: string;
  valueType: string;
  subscriberCount: number;
  replayCache: number;
  extraBufferCapacity: number;
  coroutineId: string | null;
  label: string | null;
}

export interface SharedFlowSubscription extends BaseVizEvent {
  kind: "SharedFlowSubscription";
  flowId: string;
  collectorId: string;
  action: string; // "subscribed" | "unsubscribed"
  subscriberCount: number;
  coroutineId: string | null;
  label: string | null;
}

export interface StateFlowValueChanged extends BaseVizEvent {
  kind: "StateFlowValueChanged";
  flowId: string;
  oldValuePreview: string;
  newValuePreview: string;
  valueType: string;
  subscriberCount: number;
  coroutineId: string | null;
  label: string | null;
}

// ---------------------------------------------------------------------------
// Channel events
// ---------------------------------------------------------------------------

export interface ChannelCreated extends BaseVizEvent {
  kind: "ChannelCreated";
  channelId: string;
  name: string | null;
  capacity: number;
  channelType: string; // "RENDEZVOUS" | "BUFFERED" | "CONFLATED" | "UNLIMITED"
}

export interface ChannelSendStarted extends BaseVizEvent {
  kind: "ChannelSendStarted";
  channelId: string;
  coroutineId: string;
  valueDescription: string;
}

export interface ChannelSendCompleted extends BaseVizEvent {
  kind: "ChannelSendCompleted";
  channelId: string;
  coroutineId: string;
  valueDescription: string;
}

export interface ChannelSendSuspended extends BaseVizEvent {
  kind: "ChannelSendSuspended";
  channelId: string;
  coroutineId: string;
  bufferSize: number;
  capacity: number;
}

export interface ChannelReceiveStarted extends BaseVizEvent {
  kind: "ChannelReceiveStarted";
  channelId: string;
  coroutineId: string;
}

export interface ChannelReceiveCompleted extends BaseVizEvent {
  kind: "ChannelReceiveCompleted";
  channelId: string;
  coroutineId: string;
  valueDescription: string;
}

export interface ChannelReceiveSuspended extends BaseVizEvent {
  kind: "ChannelReceiveSuspended";
  channelId: string;
  coroutineId: string;
}

export interface ChannelClosed extends BaseVizEvent {
  kind: "ChannelClosed";
  channelId: string;
  cause: string | null;
}

export interface ChannelBufferStateChanged extends BaseVizEvent {
  kind: "ChannelBufferStateChanged";
  channelId: string;
  currentSize: number;
  capacity: number;
}

// ---------------------------------------------------------------------------
// Mutex events
// ---------------------------------------------------------------------------

export interface MutexCreated extends BaseVizEvent {
  kind: "MutexCreated";
  mutexId: string;
  mutexLabel: string | null;
  ownerCoroutineId: string | null;
}

export interface MutexLockRequested extends BaseVizEvent {
  kind: "MutexLockRequested";
  mutexId: string;
  mutexLabel: string | null;
  requesterId: string;
  requesterLabel: string | null;
  isLocked: boolean;
  queuePosition: number;
}

export interface MutexLockAcquired extends BaseVizEvent {
  kind: "MutexLockAcquired";
  mutexId: string;
  mutexLabel: string | null;
  acquirerId: string;
  acquirerLabel: string | null;
  waitDurationNanos: number;
}

export interface MutexUnlocked extends BaseVizEvent {
  kind: "MutexUnlocked";
  mutexId: string;
  mutexLabel: string | null;
  releaserId: string;
  releaserLabel: string | null;
  nextWaiterId: string | null;
  holdDurationNanos: number;
}

export interface MutexTryLockFailed extends BaseVizEvent {
  kind: "MutexTryLockFailed";
  mutexId: string;
  mutexLabel: string | null;
  requesterId: string;
  requesterLabel: string | null;
  currentOwnerId: string | null;
}

export interface MutexQueueChanged extends BaseVizEvent {
  kind: "MutexQueueChanged";
  mutexId: string;
  mutexLabel: string | null;
  waitingCoroutineIds: string[];
  waitingLabels: (string | null)[];
}

// ---------------------------------------------------------------------------
// Semaphore events
// ---------------------------------------------------------------------------

export interface SemaphoreCreated extends BaseVizEvent {
  kind: "SemaphoreCreated";
  semaphoreId: string;
  semaphoreLabel: string | null;
  totalPermits: number;
}

export interface SemaphoreAcquireRequested extends BaseVizEvent {
  kind: "SemaphoreAcquireRequested";
  semaphoreId: string;
  semaphoreLabel: string | null;
  requesterId: string;
  requesterLabel: string | null;
  availablePermits: number;
  permitsRequested: number;
}

export interface SemaphorePermitAcquired extends BaseVizEvent {
  kind: "SemaphorePermitAcquired";
  semaphoreId: string;
  semaphoreLabel: string | null;
  acquirerId: string;
  acquirerLabel: string | null;
  remainingPermits: number;
  waitDurationNanos: number;
}

export interface SemaphorePermitReleased extends BaseVizEvent {
  kind: "SemaphorePermitReleased";
  semaphoreId: string;
  semaphoreLabel: string | null;
  releaserId: string;
  releaserLabel: string | null;
  newAvailablePermits: number;
  holdDurationNanos: number;
  /** True when the releasing coroutine held no permit: whose permit came back is unknown. Absent on older events. */
  holderUnknown?: boolean;
}

export interface SemaphoreTryAcquireFailed extends BaseVizEvent {
  kind: "SemaphoreTryAcquireFailed";
  semaphoreId: string;
  semaphoreLabel: string | null;
  requesterId: string;
  requesterLabel: string | null;
  availablePermits: number;
  permitsRequested: number;
}

export interface SemaphoreStateChanged extends BaseVizEvent {
  kind: "SemaphoreStateChanged";
  semaphoreId: string;
  semaphoreLabel: string | null;
  availablePermits: number;
  totalPermits: number;
  activeHolders: string[];
  activeHolderLabels: (string | null)[];
  waitingCoroutines: string[];
  waitingLabels: (string | null)[];
  /** Permits each of `activeHolders` holds; absent on older events. */
  activeHolderPermits?: number[];
  /** Listed permits that other coroutines released: their holder is unknown. */
  unknownHolderPermits?: number;
}

// ---------------------------------------------------------------------------
// Deadlock detection events
// ---------------------------------------------------------------------------

export interface DeadlockDetected extends BaseVizEvent {
  kind: "DeadlockDetected";
  involvedCoroutines: string[];
  involvedCoroutineLabels: (string | null)[];
  involvedMutexes: string[];
  involvedMutexLabels: (string | null)[];
  waitGraph: Record<string, string>; // coroutineId -> waitingForMutexId
  holdGraph: Record<string, string>; // mutexId -> heldByCoroutineId
  cycleDescription: string;
}

export interface PotentialDeadlockWarning extends BaseVizEvent {
  kind: "PotentialDeadlockWarning";
  coroutineId: string;
  coroutineLabel: string | null;
  holdingMutex: string;
  holdingMutexLabel: string | null;
  requestingMutex: string;
  requestingMutexLabel: string | null;
  recommendation: string;
}

// ---------------------------------------------------------------------------
// Discriminated union of all event types
// ---------------------------------------------------------------------------

/** All possible event kind string literals. */
export type VizEventKind =
  // Coroutine lifecycle
  | "CoroutineCreated"
  | "CoroutineStarted"
  | "CoroutineBodyCompleted"
  | "CoroutineSuspended"
  | "CoroutineResumed"
  | "CoroutineCompleted"
  | "CoroutineCancelled"
  | "CoroutineFailed"
  // Job
  | "JobStateChanged"
  | "JobJoinRequested"
  | "JobJoinCompleted"
  | "JobCancellationRequested"
  // Structured concurrency
  | "WaitingForChildren"
  // Dispatcher
  | "DispatcherSelected"
  | "ThreadAssigned"
  // Deferred
  | "DeferredAwaitStarted"
  | "DeferredAwaitCompleted"
  | "DeferredValueAvailable"
  // Flow
  | "FlowCreated"
  | "FlowValueEmitted"
  | "FlowCollectionStarted"
  | "FlowCollectionCompleted"
  | "FlowCollectionCancelled"
  | "FlowOperatorApplied"
  | "FlowValueFiltered"
  | "FlowValueTransformed"
  | "FlowBackpressure"
  | "FlowBufferOverflow"
  | "SharedFlowEmission"
  | "SharedFlowSubscription"
  | "StateFlowValueChanged"
  // Channel
  | "ChannelCreated"
  | "ChannelSendStarted"
  | "ChannelSendCompleted"
  | "ChannelSendSuspended"
  | "ChannelReceiveStarted"
  | "ChannelReceiveCompleted"
  | "ChannelReceiveSuspended"
  | "ChannelClosed"
  | "ChannelBufferStateChanged"
  // Mutex
  | "MutexCreated"
  | "MutexLockRequested"
  | "MutexLockAcquired"
  | "MutexUnlocked"
  | "MutexTryLockFailed"
  | "MutexQueueChanged"
  // Semaphore
  | "SemaphoreCreated"
  | "SemaphoreAcquireRequested"
  | "SemaphorePermitAcquired"
  | "SemaphorePermitReleased"
  | "SemaphoreTryAcquireFailed"
  | "SemaphoreStateChanged"
  // Deadlock
  | "DeadlockDetected"
  | "PotentialDeadlockWarning";

/** Discriminated union of every SSE event type. Discriminant field is `kind`. */
export type VizEvent =
  // Coroutine lifecycle
  | CoroutineCreated
  | CoroutineStarted
  | CoroutineBodyCompleted
  | CoroutineSuspended
  | CoroutineResumed
  | CoroutineCompleted
  | CoroutineCancelled
  | CoroutineFailed
  // Job
  | JobStateChanged
  | JobJoinRequested
  | JobJoinCompleted
  | JobCancellationRequested
  // Structured concurrency
  | WaitingForChildren
  // Dispatcher
  | DispatcherSelected
  | ThreadAssigned
  // Deferred
  | DeferredAwaitStarted
  | DeferredAwaitCompleted
  | DeferredValueAvailable
  // Flow
  | FlowCreated
  | FlowValueEmitted
  | FlowCollectionStarted
  | FlowCollectionCompleted
  | FlowCollectionCancelled
  | FlowOperatorApplied
  | FlowValueFiltered
  | FlowValueTransformed
  | FlowBackpressure
  | FlowBufferOverflow
  | SharedFlowEmission
  | SharedFlowSubscription
  | StateFlowValueChanged
  // Channel
  | ChannelCreated
  | ChannelSendStarted
  | ChannelSendCompleted
  | ChannelSendSuspended
  | ChannelReceiveStarted
  | ChannelReceiveCompleted
  | ChannelReceiveSuspended
  | ChannelClosed
  | ChannelBufferStateChanged
  // Mutex
  | MutexCreated
  | MutexLockRequested
  | MutexLockAcquired
  | MutexUnlocked
  | MutexTryLockFailed
  | MutexQueueChanged
  // Semaphore
  | SemaphoreCreated
  | SemaphoreAcquireRequested
  | SemaphorePermitAcquired
  | SemaphorePermitReleased
  | SemaphoreTryAcquireFailed
  | SemaphoreStateChanged
  // Deadlock
  | DeadlockDetected
  | PotentialDeadlockWarning;

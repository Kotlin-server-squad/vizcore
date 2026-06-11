// API Types matching backend DTOs
//
// This module re-exports shared event types from @vizcor/api-types where possible,
// and provides frontend-specific types for API responses, UI state, and compatibility.

// Re-export all shared event types and interfaces
import type {
  BaseVizEvent as SharedBaseVizEvent,
  BaseCoroutineEvent as SharedBaseCoroutineEvent,
  SuspensionPoint as SharedSuspensionPoint,
  VizEventKind as SharedVizEventKind,
  VizEvent as SharedVizEvent,
  // Coroutine lifecycle
  CoroutineCreated,
  CoroutineStarted,
  CoroutineBodyCompleted,
  CoroutineSuspended,
  CoroutineResumed,
  CoroutineCompleted,
  CoroutineCancelled,
  CoroutineFailed,
  // Job events
  JobStateChanged,
  JobJoinRequested,
  JobJoinCompleted,
  JobCancellationRequested,
  // Structured concurrency
  WaitingForChildren,
  // Dispatcher
  DispatcherSelected,
  ThreadAssigned,
  // Deferred
  DeferredAwaitStarted,
  DeferredAwaitCompleted,
  DeferredValueAvailable,
  // Flow events
  FlowCreated,
  FlowValueEmitted,
  FlowCollectionStarted,
  FlowCollectionCompleted,
  FlowCollectionCancelled,
  FlowOperatorApplied,
  FlowValueFiltered,
  FlowValueTransformed,
  FlowBackpressure,
  FlowBufferOverflow,
  SharedFlowEmission,
  SharedFlowSubscription,
  StateFlowValueChanged,
  // Channel events
  ChannelCreated,
  ChannelSendStarted,
  ChannelSendCompleted,
  ChannelSendSuspended,
  ChannelReceiveStarted,
  ChannelReceiveCompleted,
  ChannelReceiveSuspended,
  ChannelClosed,
  ChannelBufferStateChanged,
  // Mutex events
  MutexCreated,
  MutexLockRequested,
  MutexLockAcquired,
  MutexUnlocked,
  MutexTryLockFailed,
  MutexQueueChanged,
  // Semaphore events
  SemaphoreCreated,
  SemaphoreAcquireRequested,
  SemaphorePermitAcquired,
  SemaphorePermitReleased,
  SemaphoreTryAcquireFailed,
  SemaphoreStateChanged,
  // Deadlock events
  DeadlockDetected,
  PotentialDeadlockWarning,
} from '@vizcor/api-types'

// Re-export shared types for consumers
export type {
  SharedBaseVizEvent,
  SharedBaseCoroutineEvent,
  SharedSuspensionPoint,
  SharedVizEventKind,
  SharedVizEvent,
  // Coroutine lifecycle
  CoroutineCreated,
  CoroutineStarted,
  CoroutineBodyCompleted,
  CoroutineSuspended,
  CoroutineResumed,
  CoroutineCompleted,
  CoroutineCancelled,
  CoroutineFailed,
  // Job events
  JobStateChanged,
  JobJoinRequested,
  JobJoinCompleted,
  JobCancellationRequested,
  // Structured concurrency
  WaitingForChildren,
  // Dispatcher
  DispatcherSelected,
  ThreadAssigned,
  // Deferred
  DeferredAwaitStarted,
  DeferredAwaitCompleted,
  DeferredValueAvailable,
  // Flow events
  FlowCreated,
  FlowValueEmitted,
  FlowCollectionStarted,
  FlowCollectionCompleted,
  FlowCollectionCancelled,
  FlowOperatorApplied,
  FlowValueFiltered,
  FlowValueTransformed,
  FlowBackpressure,
  FlowBufferOverflow,
  SharedFlowEmission,
  SharedFlowSubscription,
  StateFlowValueChanged,
  // Channel events
  ChannelCreated,
  ChannelSendStarted,
  ChannelSendCompleted,
  ChannelSendSuspended,
  ChannelReceiveStarted,
  ChannelReceiveCompleted,
  ChannelReceiveSuspended,
  ChannelClosed,
  ChannelBufferStateChanged,
  // Mutex events
  MutexCreated,
  MutexLockRequested,
  MutexLockAcquired,
  MutexUnlocked,
  MutexTryLockFailed,
  MutexQueueChanged,
  // Semaphore events
  SemaphoreCreated,
  SemaphoreAcquireRequested,
  SemaphorePermitAcquired,
  SemaphorePermitReleased,
  SemaphoreTryAcquireFailed,
  SemaphoreStateChanged,
  // Deadlock events
  DeadlockDetected,
  PotentialDeadlockWarning,
}

// ---------------------------------------------------------------------------
// Frontend-specific types (kept for backwards compatibility)
// ---------------------------------------------------------------------------

export enum CoroutineState {
  CREATED = 'CREATED',
  ACTIVE = 'ACTIVE',
  SUSPENDED = 'SUSPENDED',
  WAITING_FOR_CHILDREN = 'WAITING_FOR_CHILDREN',
  COMPLETED = 'COMPLETED',
  CANCELLED = 'CANCELLED',
  FAILED = 'FAILED',
}

export interface CoroutineNode {
  id: string
  jobId: string
  parentId: string | null
  scopeId: string
  label: string | null
  state: CoroutineState
}

export interface SessionInfo {
  sessionId: string
  coroutineCount: number
}

export interface SessionSnapshot {
  sessionId: string
  coroutineCount: number
  eventCount: number
  coroutines: CoroutineNode[]
}

export interface ScenarioCompletion {
  success: boolean
  sessionId: string
  message: string
  coroutineCount: number
  eventCount: number
}

export interface Scenario {
  id: string
  name: string
  description: string
  endpoint: string
  category?: 'realistic' | 'basic'
  duration?: string
}

// Custom Scenario Configuration Types
export type ActionType = 'delay' | 'throw' | 'log' | 'custom'

export interface ActionConfig {
  type: ActionType
  params: Record<string, string>
}

export interface CoroutineConfigNode {
  id: string
  label: string
  parentId?: string | null
  actions: ActionConfig[]
  children: CoroutineConfigNode[]
}

export interface ScenarioConfigRequest {
  name: string
  description?: string | null
  sessionId?: string | null
  root: CoroutineConfigNode
}

export interface ScenarioExecutionResponse {
  success: boolean
  sessionId: string
  message: string
  coroutineCount: number
  eventCount: number
  errors?: string[]
}

// ---------------------------------------------------------------------------
// Event Types (frontend-local, compatible with existing consumers)
// ---------------------------------------------------------------------------

// Frontend event kind union - includes both legacy kebab-case and shared PascalCase kinds
export type VizEventKind =
  | 'coroutine.created'
  | 'coroutine.started'
  | 'coroutine.suspended'
  | 'coroutine.resumed'
  | 'coroutine.body-completed'
  | 'coroutine.completed'
  | 'coroutine.cancelled'
  | 'coroutine.failed'
  | 'thread.assigned'
  | 'DispatcherSelected'
  | 'DeferredValueAvailable'
  | 'DeferredAwaitStarted'
  | 'DeferredAwaitCompleted'
  | 'JobStateChanged'
  | 'JobCancellationRequested'
  | 'JobJoinRequested'
  | 'JobJoinCompleted'
  | 'WaitingForChildren'
  // New shared event kinds (PascalCase from shared types)
  | 'CoroutineCreated'
  | 'CoroutineStarted'
  | 'CoroutineBodyCompleted'
  | 'CoroutineSuspended'
  | 'CoroutineResumed'
  | 'CoroutineCompleted'
  | 'CoroutineCancelled'
  | 'CoroutineFailed'
  | 'ThreadAssigned'
  // Flow event kinds
  | 'FlowCreated'
  | 'FlowValueEmitted'
  | 'FlowCollectionStarted'
  | 'FlowCollectionCompleted'
  | 'FlowCollectionCancelled'
  | 'FlowOperatorApplied'
  | 'FlowValueFiltered'
  | 'FlowValueTransformed'
  | 'FlowBackpressure'
  | 'FlowBufferOverflow'
  | 'SharedFlowEmission'
  | 'SharedFlowSubscription'
  | 'StateFlowValueChanged'
  // Channel event kinds
  | 'ChannelCreated'
  | 'ChannelSendStarted'
  | 'ChannelSendCompleted'
  | 'ChannelSendSuspended'
  | 'ChannelReceiveStarted'
  | 'ChannelReceiveCompleted'
  | 'ChannelReceiveSuspended'
  | 'ChannelClosed'
  | 'ChannelBufferStateChanged'
  // Mutex event kinds
  | 'MutexCreated'
  | 'MutexLockRequested'
  | 'MutexLockAcquired'
  | 'MutexUnlocked'
  | 'MutexTryLockFailed'
  | 'MutexQueueChanged'
  // Semaphore event kinds
  | 'SemaphoreCreated'
  | 'SemaphoreAcquireRequested'
  | 'SemaphorePermitAcquired'
  | 'SemaphorePermitReleased'
  | 'SemaphoreTryAcquireFailed'
  | 'SemaphoreStateChanged'
  // Deadlock event kinds
  | 'DeadlockDetected'
  | 'PotentialDeadlockWarning'
  // Actor event kinds
  | 'ActorCreated'
  | 'ActorMessageProcessed'
  | 'ActorStateChanged'
  | 'ActorMailboxChanged'
  | 'ActorClosed'
  // Select event kinds
  | 'SelectStarted'
  | 'SelectClauseRegistered'
  | 'SelectClauseWon'
  | 'SelectCompleted'

export interface BaseVizEvent {
  sessionId: string
  seq: number
  tsNanos: number
  kind: VizEventKind
}

export interface CoroutineEvent extends BaseVizEvent {
  coroutineId: string
  jobId: string
  parentCoroutineId: string | null
  scopeId: string
  label: string | null
}

// Job-related event types
export interface JobStateChangedEvent extends CoroutineEvent {
  kind: 'JobStateChanged'
  isActive: boolean
  isCompleted: boolean
  isCancelled: boolean
  childrenCount: number
}

export interface JobCancellationRequestedEvent extends CoroutineEvent {
  kind: 'JobCancellationRequested'
  requestedBy: string | null
  cause: string | null
}

export interface JobJoinRequestedEvent extends CoroutineEvent {
  kind: 'JobJoinRequested'
  waitingCoroutineId: string | null
}

export interface JobJoinCompletedEvent extends CoroutineEvent {
  kind: 'JobJoinCompleted'
  waitingCoroutineId: string | null
}

// Structured Concurrency event
export interface WaitingForChildrenEvent extends CoroutineEvent {
  kind: 'WaitingForChildren'
  activeChildrenCount: number
  activeChildrenIds: string[]
}

// Dispatcher-related events
export interface DispatcherSelectedEvent extends CoroutineEvent {
  kind: 'DispatcherSelected'
  dispatcherId: string
  dispatcherName: string
  queueDepth?: number | null
}

export interface ThreadAssignedEvent extends CoroutineEvent {
  kind: 'thread.assigned'
  threadId: number
  threadName: string
  dispatcherName: string
}

// Deferred/Async events
export interface DeferredValueAvailableEvent extends CoroutineEvent {
  kind: 'DeferredValueAvailable'
  deferredId: string
}

export interface DeferredAwaitStartedEvent extends CoroutineEvent {
  kind: 'DeferredAwaitStarted'
  deferredId: string
  awaitingCoroutineId: string
}

export interface DeferredAwaitCompletedEvent extends CoroutineEvent {
  kind: 'DeferredAwaitCompleted'
  deferredId: string
  awaitingCoroutineId: string
}

export type VizEvent =
  | CoroutineEvent
  | CoroutineSuspendedEvent
  | JobStateChangedEvent
  | JobCancellationRequestedEvent
  | JobJoinRequestedEvent
  | JobJoinCompletedEvent
  | WaitingForChildrenEvent
  | DispatcherSelectedEvent
  | ThreadAssignedEvent
  | DeferredValueAvailableEvent
  | DeferredAwaitStartedEvent
  | DeferredAwaitCompletedEvent

// Event filtering and pagination
export interface EventFilter {
  coroutineId?: string
  scopeId?: string
  kind?: VizEventKind | VizEventKind[]
  fromTimestamp?: number
  toTimestamp?: number
}

export interface PaginatedEventsRequest {
  sessionId: string
  sinceStep?: number
  limit?: number
  filter?: EventFilter
}

export interface PaginatedEventsResponse {
  events: VizEvent[]
  nextStep?: number | null
  hasMore: boolean
  total: number
}

// API Response types
export interface CreateSessionResponse {
  sessionId: string
  message: string
}

export interface ErrorResponse {
  error: string
}

// Thread activity types
export interface ThreadEvent {
  coroutineId: string
  threadId: number
  threadName: string
  timestamp: number
  eventType: 'ASSIGNED' | 'RELEASED'
}

export interface ThreadActivity {
  [threadId: string]: ThreadEvent[]
}

// Hierarchy types (Enhanced for new backend API)
export interface HierarchyNode {
  id: string
  parentId: string | null
  children: string[] | HierarchyNode[]
  name: string
  scopeId: string
  state: CoroutineState
  createdAtNanos: number
  completedAtNanos?: number | null
  currentThreadId?: number | null
  currentThreadName?: string | null
  dispatcherId?: string | null
  dispatcherName?: string | null
  jobId: string
  // Structured concurrency tracking
  activeChildrenIds?: string[]
  activeChildrenCount?: number
  // Additional metadata for enhanced visualization
  suspensionPoints?: SuspensionPoint[]
  activeTime?: number  // Total active time in nanos
  suspendedTime?: number  // Total suspended time in nanos
}

// Hierarchy tree response (nested structure from backend)
export interface HierarchyTree {
  root: HierarchyNodeTree
}

export interface HierarchyNodeTree extends HierarchyNode {
  children: HierarchyNodeTree[]  // Full nested children, not just IDs
}

// Suspension Point tracking
export interface SuspensionPoint {
  function: string
  fileName?: string | null
  lineNumber?: number | null
  reason: string  // "delay", "withContext", "join", etc.
  timestamp: number
}

// Enhanced Coroutine Suspended event with suspension point
export interface CoroutineSuspendedEvent extends CoroutineEvent {
  kind: 'coroutine.suspended'
  suspensionPoint?: SuspensionPoint | null
}

// Timeline data structures
export interface CoroutineTimeline {
  coroutineId: string
  name: string | null
  state: CoroutineState
  parentId: string | null
  childrenIds: string[]
  totalDuration: number  // in nanos
  activeTime: number  // in nanos
  suspendedTime: number  // in nanos
  events: TimelineEvent[]
}

export interface TimelineEvent {
  seq: number
  timestamp: number  // in nanos
  kind: VizEventKind
  threadId?: number | null
  threadName?: string | null
  dispatcherId?: string | null
  dispatcherName?: string | null
  suspensionPoint?: SuspensionPoint | null
  duration?: number | null  // For computed durations (suspend -> resume)
}

// Thread Activity - Enhanced structure
export interface ThreadActivityResponse {
  threads: ThreadLaneData[]
  dispatcherInfo: DispatcherInfo[]
}

export interface ThreadLaneData {
  threadId: number
  threadName: string
  dispatcherId?: string | null
  dispatcherName?: string | null
  segments: ThreadSegment[]
  utilization: number  // 0-1 representing how busy the thread was
}

export interface ThreadSegment {
  coroutineId: string
  coroutineName: string | null
  startNanos: number
  endNanos?: number | null
  state: 'ACTIVE' | 'SUSPENDED'
}

export interface DispatcherInfo {
  id: string
  name: string
  threadIds: number[]
  queueDepth?: number | null
}

// ---------------------------------------------------------------------------
// NEW: Event category union types (from shared @vizcor/api-types)
// ---------------------------------------------------------------------------

/** Union of all channel-related events */
export type ChannelEvent =
  | ChannelCreated
  | ChannelSendStarted
  | ChannelSendCompleted
  | ChannelSendSuspended
  | ChannelReceiveStarted
  | ChannelReceiveCompleted
  | ChannelReceiveSuspended
  | ChannelClosed
  | ChannelBufferStateChanged

/** Union of all flow operator-related events */
export type FlowOperatorEvent =
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

/** Union of all synchronization primitive events (mutex + semaphore + deadlock) */
export type SyncEvent =
  | MutexCreated
  | MutexLockRequested
  | MutexLockAcquired
  | MutexUnlocked
  | MutexTryLockFailed
  | MutexQueueChanged
  | SemaphoreCreated
  | SemaphoreAcquireRequested
  | SemaphorePermitAcquired
  | SemaphorePermitReleased
  | SemaphoreTryAcquireFailed
  | SemaphoreStateChanged
  | DeadlockDetected
  | PotentialDeadlockWarning

/** Validation response from the backend validation API (POST /api/validate/session/{id}) */
export interface ValidationResponse {
  sessionId: string
  results: ValidationRuleResult[]
  timing: BackendTimingReport
}

/** Individual rule result — type discriminator is 'Pass' | 'Fail' (no Warning variant) */
export interface ValidationRuleResult {
  type: 'Pass' | 'Fail'
  ruleName: string
  message: string
  details?: string  // present on Fail only
}

/** Timing report returned by the validation API — values are in milliseconds */
export interface BackendTimingReport {
  coroutineDurations: Record<string, number>       // Map<coroutineId, durationMs>
  suspensionDurations: Record<string, number[]>    // Map<coroutineId, List<durationMs>>
  totalDuration: number                            // milliseconds
}

// ---------------------------------------------------------------------------
// Session Comparison (from GET /api/compare)
// ---------------------------------------------------------------------------

/** Result of comparing two visualization sessions */
export interface SessionComparison {
  sessionA: string
  sessionB: string
  coroutineCountDiff: number
  eventCountDiff: number
  totalDurationDiffNanos: number
  coroutinesOnlyInA: string[]
  coroutinesOnlyInB: string[]
  commonCoroutines: CoroutineComparison[]
}

/** Comparison of a single coroutine that exists in both sessions */
export interface CoroutineComparison {
  coroutineId: string
  label: string | null
  stateA: string
  stateB: string
  eventCountA: number
  eventCountB: number
}

// ---------------------------------------------------------------------------
// Event kind constants for category detection
// ---------------------------------------------------------------------------

/** Channel event kinds for use in category detection */
export const CHANNEL_EVENT_KINDS: ReadonlySet<string> = new Set([
  'ChannelCreated',
  'ChannelSendStarted',
  'ChannelSendCompleted',
  'ChannelSendSuspended',
  'ChannelReceiveStarted',
  'ChannelReceiveCompleted',
  'ChannelReceiveSuspended',
  'ChannelClosed',
  'ChannelBufferStateChanged',
])

/** Flow event kinds for use in category detection */
export const FLOW_EVENT_KINDS: ReadonlySet<string> = new Set([
  'FlowCreated',
  'FlowValueEmitted',
  'FlowCollectionStarted',
  'FlowCollectionCompleted',
  'FlowCollectionCancelled',
  'FlowOperatorApplied',
  'FlowValueFiltered',
  'FlowValueTransformed',
  'FlowBackpressure',
  'FlowBufferOverflow',
  'SharedFlowEmission',
  'SharedFlowSubscription',
  'StateFlowValueChanged',
])

/** Sync primitive event kinds for use in category detection */
export const SYNC_EVENT_KINDS: ReadonlySet<string> = new Set([
  'MutexCreated',
  'MutexLockRequested',
  'MutexLockAcquired',
  'MutexUnlocked',
  'MutexTryLockFailed',
  'MutexQueueChanged',
  'SemaphoreCreated',
  'SemaphoreAcquireRequested',
  'SemaphorePermitAcquired',
  'SemaphorePermitReleased',
  'SemaphoreTryAcquireFailed',
  'SemaphoreStateChanged',
  'DeadlockDetected',
  'PotentialDeadlockWarning',
])

/** Job event kinds for use in category detection */
export const JOB_EVENT_KINDS: ReadonlySet<string> = new Set([
  'JobStateChanged',
  'JobJoinRequested',
  'JobJoinCompleted',
  'JobCancellationRequested',
  'WaitingForChildren',
])

// ============================================================================
// Actor Events
// ============================================================================

export interface ActorCreated extends BaseVizEvent {
  kind: 'ActorCreated'
  actorId: string
  coroutineId: string
  label?: string
  name?: string | null
  mailboxCapacity: number
}

export interface ActorStateChanged extends BaseVizEvent {
  kind: 'ActorStateChanged'
  actorId: string
  state: string
  newStatePreview: string
}

export interface ActorMailboxChanged extends BaseVizEvent {
  kind: 'ActorMailboxChanged'
  actorId: string
  mailboxSize: number
  capacity: number
  currentSize: number
  pendingSenders: number
}

export interface ActorClosed extends BaseVizEvent {
  kind: 'ActorClosed'
  actorId: string
  processedCount: number
  reason?: string | null
  totalMessagesProcessed?: number
}

export interface ActorMessageProcessed extends BaseVizEvent {
  kind: 'ActorMessageProcessed'
  actorId: string
}

export type ActorEvent = ActorCreated | ActorMessageProcessed | ActorStateChanged | ActorMailboxChanged | ActorClosed

export const ACTOR_EVENT_KINDS: ReadonlySet<string> = new Set([
  'ActorCreated',
  'ActorMessageProcessed',
  'ActorStateChanged',
  'ActorMailboxChanged',
  'ActorClosed',
])

// ============================================================================
// Select Events
// ============================================================================

export interface SelectStarted extends BaseVizEvent {
  kind: 'SelectStarted'
  selectId: string
  coroutineId: string
}

export interface SelectClauseRegistered extends BaseVizEvent {
  kind: 'SelectClauseRegistered'
  selectId: string
  coroutineId?: string
  clauseIndex: number
  clauseType: string
  description?: string
  channelId?: string | null
  deferredId?: string | null
  timeoutMillis?: number | null
  label?: string | null
}

export interface SelectClauseWon extends BaseVizEvent {
  kind: 'SelectClauseWon'
  selectId: string
  coroutineId?: string
  winningClauseIndex: number
  winnerClauseIndex: number
  winnerClauseType: string
  waitDurationNanos: number
}

export interface SelectCompleted extends BaseVizEvent {
  kind: 'SelectCompleted'
  selectId: string
  coroutineId?: string
  totalDurationNanos: number
}

export type SelectEvent = SelectStarted | SelectClauseRegistered | SelectClauseWon | SelectCompleted

export const SELECT_EVENT_KINDS: ReadonlySet<string> = new Set([
  'SelectStarted',
  'SelectClauseRegistered',
  'SelectClauseWon',
  'SelectCompleted',
])

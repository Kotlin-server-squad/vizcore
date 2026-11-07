/**
 * The session health of `GET /api/sessions/{id}/health` (triage C-3, `SessionHealth` in core's `HealthModel.kt`), and the
 * last checks run of `GET /api/validate/session/{id}` (C-5). The page presents these; it derives no problem (D-8).
 */
import type { Capped, IncidentKind, SuspensionPoint } from './api'

export type HealthState = 'AWAITING_FIRST_DATA' | 'PROBLEMS' | 'NO_PROBLEMS' | 'NO_FAILURES_PARTIAL' | 'NO_APP_COROUTINES'

export type AsOfKind = 'WATERMARK' | 'INGEST' | 'LAST_EVENT' | 'REPLAY_CURSOR'

/** What a health statement is about (H-2). */
export interface AsOf {
  kind: AsOfKind
  appNanos: number
  /** The app's wall clock at that observation, when known (only a watermark carries it). */
  appEpochMs?: number | null
  /** When the backend received it. */
  receivedEpochMs?: number | null
  seq: number
  /** A replay of trimmed history: `appNanos` is a lower bound. */
  lowerBound?: boolean
}

export type NotEvaluatedReason = 'NO_WATERMARK' | 'DEMO' | 'REPLAY' | 'OVERFLOW' | 'EVENTS_DROPPED' | 'HISTORY_TRIMMED'

export interface PossiblyBlockedEvaluation {
  status: 'EVALUATED' | 'NOT_EVALUATED'
  reason?: NotEvaluatedReason | null
}

export interface FailureEntry {
  coroutineId: string
  name: string | null
  exceptionType: string | null
  message: string | null
  failedAtSeq: number
  detailsDropped: boolean
}

export type TransitionKind =
  | 'CREATED'
  | 'STARTED'
  | 'SUSPENDED'
  | 'RESUMED'
  | 'BODY_COMPLETED'
  | 'COMPLETED'
  | 'CANCELLED'
  | 'FAILED'

export interface Transition {
  kind: TransitionKind
  appNanos: number
  seq: number
}

export interface ThreadObservation {
  threadId: number
  threadName: string
  seq: number
}

export interface FailedRoundIncident {
  type: 'FAILED_ROUND'
  id: string
  roundId: string
  anchorId: string
  failures: Capped<FailureEntry>
  cancelledAfter: Capped<string>
  firstFailureSeq: number
  sortSeq: number
}

export interface UngroupedFailureIncident {
  type: 'UNGROUPED_FAILURE'
  id: string
  failure: FailureEntry
  sortSeq: number
}

export interface PossiblyBlockedIncident {
  type: 'POSSIBLY_BLOCKED'
  id: string
  coroutineId: string
  name: string | null
  roundId: string | null
  anchorId: string | null
  lastThread: ThreadObservation | null
  dispatcher: string | null
  lastSuspension: SuspensionPoint | null
  lastSuspensionProvenance: 'REAL_FRAME' | 'CREATION_FALLBACK' | 'STALE' | null
  lastRunStart: Transition
  runningForNanos: number
  transitions: Transition[]
  transitionsGap: boolean
  firstObservedRunning: boolean
  eventLoopClue: string | null
  sortSeq: number
}

export type Incident = FailedRoundIncident | UngroupedFailureIncident | PossiblyBlockedIncident

export interface ObservedAge {
  coroutineId: string
  name: string | null
  ageNanos: number
}

export interface SessionHealth {
  state: HealthState
  asOf: AsOf | null
  asOfSeq: number
  sessionOrigin: 'REPORTED' | 'UNKNOWN'
  problemCount: number
  failedRoundCount: number
  ungroupedFailureCount: number
  possiblyBlockedCount: number
  incidents: Capped<Incident>
  possiblyBlocked: PossiblyBlockedEvaluation
  observations: { longLived: Capped<ObservedAge>; longSuspended: Capped<ObservedAge> }
  counts: { app: number; vizcore: number; unknown: number; untracked: number }
  failuresAtLeast: boolean
  vizcoreConnection: { captured: number; failed: number; failures: Capped<FailureEntry> }
  transport: { lostEvents?: number; deliveryUncertain?: boolean; sinceSeq?: number | null; revision?: number }
  roundCount: number
  problemRoundCount: number
  problemRounds: Capped<{ roundId: string; anchorId: string; anchorCreatedNanos: number; kinds: IncidentKind[] }>
  asOfWatermark?: { appNanos: number; appEpochMs: number; receivedEpochMs: number } | null
  retention?: { oldestRetainedSeq: number | null; oldestRetainedAppNanos: number | null; trimmed: boolean } | null
}

/** The last checks run (C-5, T-8). */
export interface ChecksRun {
  checkedSeq: number
  ranAtEpochMs: number
  rules: Array<{ name: string; passed: boolean; failures: number }>
  failedRuleCount: number
}

export interface ChecksStatus {
  sessionId: string
  lastRun: ChecksRun | null
  newEventsSince: number | null
}

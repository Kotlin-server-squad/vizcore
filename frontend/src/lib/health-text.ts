/**
 * The words the page says about a session's health (S-8, triage T-1 and "Health strip states"). Pure presentation of
 * what `GET …/health` derived: nothing here decides what a problem is (D-8). Every "as of" names the observation time on
 * the app's clock, never the browser's (H-2).
 */
import { ApiError, RateLimitedError } from './api-errors'
import { formatDuration } from './format-duration'
import type {
  AsOf,
  FailureEntry,
  NotEvaluatedReason,
  PossiblyBlockedIncident,
  SessionHealth,
} from '@/types/health'

const count = (n: number) => n.toLocaleString('en-US')
const plural = (n: number, one: string, many = `${one}s`) => `${count(n)} ${n === 1 ? one : many}`

/** Time zone and locale for wall-clock times; tests pin them. */
export interface ClockOptions {
  timeZone?: string
}

/** A wall-clock time from another clock (the app's, the backend's), shown in the browser's locale. */
export function clockTime(epochMs: number, options: ClockOptions = {}): string {
  return new Date(epochMs).toLocaleTimeString('en-GB', { hour12: false, timeZone: options.timeZone })
}

/**
 * "as of …" (H-2): the app's wall clock when the observation carries it; else time into the capture; else the event
 * number. A replay cursor has no wall-clock time (events carry only monotonic time): its time into the capture and its
 * event number.
 */
export function asOfText(asOf: AsOf | null, captureStartAppNanos: number | null | undefined, clock: ClockOptions = {}): string {
  if (!asOf) return ''
  const floor = asOf.lowerBound ? 'at least ' : ''
  const intoCapture =
    captureStartAppNanos != null ? `${floor}${formatDuration(asOf.appNanos - captureStartAppNanos)} into the capture` : null
  if (asOf.kind === 'REPLAY_CURSOR') {
    return intoCapture ? `as of ${intoCapture} · event ${count(asOf.seq)}` : `as of event ${count(asOf.seq)}`
  }
  if (asOf.appEpochMs != null) return `as of ${floor}${clockTime(asOf.appEpochMs, clock)}`
  return intoCapture ? `as of ${intoCapture}` : `as of event ${count(asOf.seq)}`
}

export type HeadlineTone = 'problem' | 'clean' | 'neutral'

export interface Headline {
  tone: HeadlineTone
  text: string
  /** Per-kind counts, with problems only. */
  detail?: string
}

/** The tab's first line (S-8): problems, nothing detected (green only when provably clean), or what is missing. */
export function headline(health: SessionHealth, captureStartAppNanos: number | null | undefined, clock: ClockOptions = {}): Headline {
  const asOf = asOfText(health.asOf, captureStartAppNanos, clock)
  const withAsOf = (text: string) => (asOf ? `${text} · ${asOf}` : text)
  switch (health.state) {
    case 'AWAITING_FIRST_DATA':
      return { tone: 'neutral', text: 'Waiting for the first data from the app' }
    case 'PROBLEMS': {
      const atLeast = health.failuresAtLeast ? 'at least ' : ''
      const failed = health.failedRoundCount + health.ungroupedFailureCount
      const parts = [
        ...(failed > 0
          ? [
              `Failed ${atLeast}${count(failed)} (${plural(health.failedRoundCount, 'round')} · ${count(health.ungroupedFailureCount)} ungrouped)`,
            ]
          : []),
        ...(health.possiblyBlockedCount > 0 ? [`Possibly blocked ${count(health.possiblyBlockedCount)}`] : []),
      ]
      return {
        tone: 'problem',
        text: withAsOf(`${atLeast}${plural(health.problemCount, 'problem')} detected`),
        detail: parts.join(' · '),
      }
    }
    case 'NO_PROBLEMS':
      return { tone: 'clean', text: withAsOf('No problems detected in the captured coroutines') }
    case 'NO_APP_COROUTINES':
      return { tone: 'neutral', text: withAsOf('No application coroutines captured · nothing to evaluate yet') }
    case 'NO_FAILURES_PARTIAL':
    default:
      return { tone: 'neutral', text: withAsOf('No failures detected in the captured coroutines') }
  }
}

/** Why possibly blocked was not evaluated, in words (triage "Health strip states"). */
export function notEvaluatedText(reason: NotEvaluatedReason | null | undefined): string {
  switch (reason) {
    case 'NO_WATERMARK':
      return 'no poll data from this app'
    case 'DEMO':
      return 'demo session: the in-process wrappers do not observe every suspension'
    case 'REPLAY':
      return 'replay'
    case 'OVERFLOW':
      return 'more coroutines than the session can track (capacity)'
    case 'EVENTS_DROPPED':
      return 'some events were lost on the way from the app'
    case 'HISTORY_TRIMMED':
      return "this session's early events were trimmed"
    default:
      return 'reason not reported'
  }
}

/** The quiet lines under the headline: possibly blocked, and every reason the result may be incomplete. */
export function qualifierLines(health: SessionHealth, captureStartAppNanos: number | null | undefined): string[] {
  const lines: string[] = []
  if (health.state !== 'AWAITING_FIRST_DATA') {
    lines.push(
      health.possiblyBlocked.status === 'EVALUATED'
        ? health.possiblyBlockedCount === 0
          ? 'Nothing possibly blocked'
          : `Possibly blocked: ${count(health.possiblyBlockedCount)}`
        : `Possibly blocked: not evaluated (${notEvaluatedText(health.possiblyBlocked.reason)})`,
    )
  }
  if ((health.transport.lostEvents ?? 0) > 0 || health.transport.deliveryUncertain) {
    lines.push('some events may have been lost on the way from the app; results may be incomplete')
  }
  if (health.counts.untracked > 0) {
    const tracked = health.counts.app + health.counts.vizcore + health.counts.unknown
    lines.push(
      `${plural(health.counts.untracked, 'coroutine')} beyond capacity were not tracked: health covers the first ${count(tracked)}`,
    )
  }
  if (health.sessionOrigin === 'UNKNOWN') lines.push("origin not available: vizcore's own connection may be included")
  const retention = health.retention
  if (health.asOf?.kind === 'REPLAY_CURSOR' && retention?.trimmed) {
    const at =
      retention.oldestRetainedAppNanos != null && captureStartAppNanos != null
        ? formatDuration(retention.oldestRetainedAppNanos - captureStartAppNanos)
        : `event ${count(retention.oldestRetainedSeq ?? 0)}`
    lines.push(`replay starts at ${at}; earlier events were dropped, so this may be incomplete`)
  }
  return lines
}

/** A coroutine's problem in the health, for the Inspector's order (T-3). */
export type CoroutineProblem =
  | {
      kind: 'FAILED'
      failure: FailureEntry
      /** Its failed round's figures; null for an ungrouped failure. */
      round: { failed: number; cancelledAfter: number } | null
    }
  | { kind: 'POSSIBLY_BLOCKED'; incident: PossiblyBlockedIncident }

/** The problem the health lists for [coroutineId], or null (also for a coroutine past a list's first 50). */
export function problemOf(health: SessionHealth | undefined | null, coroutineId: string): CoroutineProblem | null {
  if (!health) return null
  for (const incident of health.incidents.items) {
    if (incident.type === 'FAILED_ROUND') {
      const failure = incident.failures.items.find(f => f.coroutineId === coroutineId)
      if (failure) {
        return { kind: 'FAILED', failure, round: { failed: incident.failures.total, cancelledAfter: incident.cancelledAfter.total } }
      }
    } else if (incident.type === 'UNGROUPED_FAILURE') {
      if (incident.failure.coroutineId === coroutineId) return { kind: 'FAILED', failure: incident.failure, round: null }
    } else if (incident.coroutineId === coroutineId) {
      return { kind: 'POSSIBLY_BLOCKED', incident }
    }
  }
  return null
}

/** Why the health could not be read, in words (H-3: unknown is never shown as healthy). */
export function healthErrorText(error: unknown): string {
  if (error instanceof RateLimitedError) return 'too many requests'
  if (error instanceof ApiError) {
    if (error.code === 'summaries-unavailable') return 'the session keeps no usable summaries'
    if (error.code === 'history-trimmed') return "this session's early events were trimmed"
    if (error.status === 404) return 'not known at this moment'
  }
  return 'the health could not be read'
}

import { CoroutineState, type SessionSnapshot, type VizEvent } from '@/types/api'

/** Terminal coroutine states — no further transitions expected. */
const TERMINAL_STATES = new Set<string>([
  CoroutineState.COMPLETED,
  CoroutineState.CANCELLED,
  CoroutineState.FAILED,
])

/**
 * A demo run's outcome (spec S-3):
 *   notStarted — no coroutines seen yet
 *   failed     — at least one coroutine FAILED, whether or not others still
 *                run. Never green "completed": a run that failed something did
 *                not succeed, and a supervisor's surviving children do not undo it.
 *   running    — none failed, at least one is not terminal
 *   finished   — all terminal, none failed
 */
export type RunOutcome = 'notStarted' | 'running' | 'finished' | 'failed'

export interface RunOutcomeFacts {
  outcome: RunOutcome
  failedCount: number
  /** Every coroutine is terminal: nothing more will happen in this run. */
  ended: boolean
}

export function deriveRunOutcome(session: SessionSnapshot): RunOutcomeFacts {
  if (session.coroutineCount === 0 || session.coroutines.length === 0) {
    return { outcome: 'notStarted', failedCount: 0, ended: false }
  }
  const failedCount = session.coroutines.filter(c => c.state === CoroutineState.FAILED).length
  const ended = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
  if (failedCount > 0) return { outcome: 'failed', failedCount, ended }
  return { outcome: ended ? 'finished' : 'running', failedCount: 0, ended }
}

/**
 * From the first to the last event the page holds, across every list given
 * (the history loaded with the page and the events streamed since), on the
 * app's own clock. Null when there is nothing to measure. Event timestamps are
 * compared with each other only, never with the browser's clock.
 */
export function recordedSpanNanos(...lists: ReadonlyArray<readonly VizEvent[]>): number | null {
  let first = Infinity
  let last = -Infinity
  for (const events of lists) {
    for (const e of events) {
      if (!Number.isFinite(e.tsNanos)) continue
      if (e.tsNanos < first) first = e.tsNanos
      if (e.tsNanos > last) last = e.tsNanos
    }
  }
  return last > first ? last - first : null
}

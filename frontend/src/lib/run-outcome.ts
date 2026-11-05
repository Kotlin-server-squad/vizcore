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

export function deriveRunOutcome(session: SessionSnapshot): { outcome: RunOutcome; failedCount: number } {
  if (session.coroutineCount === 0 || session.coroutines.length === 0) {
    return { outcome: 'notStarted', failedCount: 0 }
  }
  const failedCount = session.coroutines.filter(c => c.state === CoroutineState.FAILED).length
  if (failedCount > 0) return { outcome: 'failed', failedCount }
  const allTerminal = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
  return { outcome: allTerminal ? 'finished' : 'running', failedCount: 0 }
}

/**
 * From the first to the last recorded event, on the app's own clock. Null when
 * there is nothing to measure. Event timestamps are compared with each other
 * only, never with the browser's clock.
 */
export function recordedSpanNanos(events: readonly VizEvent[]): number | null {
  if (events.length < 2) return null
  let first = Infinity
  let last = -Infinity
  for (const e of events) {
    if (!Number.isFinite(e.tsNanos)) continue
    if (e.tsNanos < first) first = e.tsNanos
    if (e.tsNanos > last) last = e.tsNanos
  }
  return last > first ? last - first : null
}

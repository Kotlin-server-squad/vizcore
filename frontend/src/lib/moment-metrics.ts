import { UNKNOWN_STATE } from './cursor-coroutines'
import type { CoroutineNode, VizEvent } from '@/types/api'

/** The throughput window of the backend's metrics projection (`MetricsProjection.THROUGHPUT_WINDOW_NANOS`). */
const WINDOW_NANOS = 10e9

const TERMINAL_KINDS = new Set(['coroutine.completed', 'coroutine.cancelled', 'coroutine.failed'])
const TERMINAL_STATES = new Set<string>(['COMPLETED', 'CANCELLED', 'FAILED'])

/** The session metrics at a moment: the tiles of `/metrics`, without the long-lived list (not evaluated at a moment). */
export interface MomentMetrics {
  /** Coroutines the tree shows at the moment that have not finished and whose state is known. */
  active: number
  /** Coroutines whose state at the moment is not known (H-3): neither active nor finished. */
  unknown: number
  /** The most coroutines created and not yet finished at once, over the replayed events. */
  peak: number
  /** Replayed events per second in the 10 s of app time before the moment. */
  throughputPerSec: number
  /** Active coroutines by dispatcher (their `scopeId`, as `MetricsProjection` reads it). */
  dispatcherUtilization: Record<string, number>
}

/**
 * The metrics at a moment (Part 8, M-1). Active and dispatcher load come from [coroutines], the ones the tree shows at
 * that moment, so the two agree (they include coroutines the server adds from its summaries when the replay does not
 * start at the beginning). Peak and throughput are folded from [events], the replayed events up to the moment, the way
 * the backend's `MetricsProjection` folds them: a coroutine counts from its creation to its terminal event, and the
 * throughput window is the 10 s before the latest event time so far, on the app's clock.
 */
export function metricsAt(events: readonly VizEvent[], coroutines: readonly CoroutineNode[]): MomentMetrics {
  const open = new Set<string>()
  let peak = 0
  let now = -Infinity
  for (const e of events) {
    now = Math.max(now, e.tsNanos)
    const id = (e as { coroutineId?: string }).coroutineId
    if (!id) continue
    if (e.kind === 'coroutine.created') {
      open.add(id)
      peak = Math.max(peak, open.size)
    } else if (TERMINAL_KINDS.has(e.kind)) {
      open.delete(id)
    }
  }
  let recent = 0
  for (const e of events) if (e.tsNanos > now - WINDOW_NANOS) recent++

  let active = 0
  let unknown = 0
  const dispatcherUtilization: Record<string, number> = {}
  for (const c of coroutines) {
    if (c.state === UNKNOWN_STATE) unknown++
    else if (!TERMINAL_STATES.has(c.state)) {
      active++
      const name = c.scopeId || 'unknown'
      dispatcherUtilization[name] = (dispatcherUtilization[name] ?? 0) + 1
    }
  }
  return { active, unknown, peak, throughputPerSec: recent / (WINDOW_NANOS / 1e9), dispatcherUtilization }
}

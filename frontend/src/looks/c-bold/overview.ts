/**
 * What C's "Right now" overview shows (sketch K-C-Bold): a line per coroutine
 * that holds or waits for something, and a bar of the session's states.
 *
 * Read only from what the page already has (its activity lines and its state
 * counts, passed through the SessionBody slot); nothing is fetched or
 * re-derived from events here.
 */
import type { ActivityLine } from '@/lib/activity'
import type { StateCounts } from '@/lib/state-counts'
import type { CoroutineNode } from '@/types/api'

export interface RightNowLine {
  /** Keyed by the coroutine's id; never shown. */
  id: string
  /** Its name; never its id (an unnamed one is called that). */
  name: string
  text: string
  waiting: boolean
}

/** At most this many lines; the rest are counted ("and 4 more"). */
export const ROW_CAP = 6

/** What a coroutine without a label is called: its id is internal. */
export const UNNAMED = 'An unnamed coroutine'

const TERMINAL = new Set(['COMPLETED', 'CANCELLED', 'FAILED'])

/**
 * One line per coroutine that is still live and holds or waits for something:
 * holders first, then waiters (as the sketch reads: "order-4 holds the lock.
 * order-5 waits for it."), in the page's order within each.
 */
export function rightNowLines(
  coroutines: readonly CoroutineNode[],
  activity: ReadonlyMap<string, ActivityLine>
): RightNowLine[] {
  const lines: RightNowLine[] = []
  for (const c of coroutines) {
    if (TERMINAL.has(c.state)) continue
    const line = activity.get(c.id)
    if (!line) continue
    lines.push({ id: c.id, name: c.label || UNNAMED, text: line.text, waiting: line.waiting })
  }
  return [...lines.filter(l => !l.waiting), ...lines.filter(l => l.waiting)]
}

export interface StateSegment {
  key: 'active' | 'waiting' | 'suspended' | 'completed' | 'cancelled' | 'failed'
  count: number
  /** As the state chips name it, lower case: "waiting for children". */
  label: string
  /** The same token the state chip and the node outline use. */
  color: string
}

/**
 * The bar's segments: the non-zero state buckets, in the state chips' order.
 * Colours follow the chips (StateBar) and the node outlines
 * (coroutine-state-colors): waiting and cancelled are their state's colour,
 * lighter, as their outlines are.
 */
export function stateSegments(counts: StateCounts): StateSegment[] {
  const all: StateSegment[] = [
    { key: 'active', count: counts.active, label: 'active', color: 'var(--primary)' },
    {
      key: 'waiting',
      count: counts.waiting,
      label: 'waiting for children',
      color: 'color-mix(in srgb, var(--primary) 60%, transparent)',
    },
    { key: 'suspended', count: counts.suspended, label: 'suspended', color: 'var(--warning)' },
    { key: 'completed', count: counts.completed, label: 'completed', color: 'var(--success)' },
    {
      key: 'cancelled',
      count: counts.cancelled,
      label: 'cancelled',
      color: 'color-mix(in srgb, var(--warning) 60%, transparent)',
    },
    { key: 'failed', count: counts.failed, label: 'failed', color: 'var(--danger)' },
  ]
  return all.filter(s => s.count > 0)
}

/** The bar's text alternative: "3 active, 3 suspended, 1 completed". */
export function segmentsLabel(segments: readonly StateSegment[]): string {
  return segments.map(s => `${s.count} ${s.label}`).join(', ')
}

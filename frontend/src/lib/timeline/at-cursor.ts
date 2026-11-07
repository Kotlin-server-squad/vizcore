/**
 * The timeline at a cursor (plan T-8): what the events up to the cursor's event show is filled, the rest hollow.
 * Decided by event index — two events can share a time on the running-max clock — and placed by time.
 */
import type { HoldBand, Range, StateSpan, Timeline, TimelineRow } from './build-timeline'

const END_STATE: Record<string, string> = {
  'coroutine.completed': 'COMPLETED',
  'coroutine.failed': 'FAILED',
  'coroutine.cancelled': 'CANCELLED',
}

/** A piece's filled and hollow parts as [from, to] nanos on the axis; null when it has none. */
export interface Pieces {
  filled: [number, number] | null
  hollow: [number, number] | null
}

/**
 * Where a span, band or range lies, split at the cursor (`null`: live or loaded, everything is shown as known). A
 * piece that starts after the cursor's event is hollow even when drawn at the cursor's time.
 */
export function pieces(timeline: Timeline, piece: Range | StateSpan | HoldBand, cursor: number | null): Pieces {
  const { times, spanNanos } = timeline
  const start = times[piece.from] ?? 0
  const end = piece.to === null ? spanNanos : (times[piece.to] ?? spanNanos)
  if (cursor === null || (piece.to !== null && piece.to <= cursor)) return { filled: [start, end], hollow: null }
  if (piece.from > cursor) return { filled: null, hollow: [start, end] }
  const at = times[cursor] ?? start
  return { filled: [start, at], hollow: [at, end] }
}

/** The row's state after the event at [cursor], as the tree has it; null before its creation or without drawn states. */
export function stateAt(timeline: Timeline, row: TimelineRow, cursor: number): string | null {
  let current: StateSpan | undefined
  for (const span of row.spans) {
    if (span.from > cursor) break
    current = span
  }
  if (!current) return null
  if (current.to === null || current.to > cursor) return current.state
  // Ended at or before the cursor: by its terminal event.
  return END_STATE[timeline.events[current.to]!.kind] ?? null
}

export interface HoldAt {
  resourceId: string
  kind: 'lock' | 'permit'
  /** False while whose permit it is is not known ("may hold"). */
  certain: boolean
}

const covers = (r: Range, at: number) => r.from <= at && (r.to === null || r.to > at)

/** What the coroutine holds after the event at [cursor]: one entry per open band. */
export function holdsAt(timeline: Timeline, coroutineId: string, cursor: number): HoldAt[] {
  const row = timeline.rows.get(coroutineId)
  if (!row) return []
  return row.holds
    .filter(band => covers(band, cursor))
    .map(band => ({ resourceId: band.resourceId, kind: band.kind, certain: !band.uncertain.some(r => covers(r, cursor)) }))
}

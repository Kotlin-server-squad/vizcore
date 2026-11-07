/**
 * The "may hold" pieces of a band as drawn (plan T-6, T-8): at the drawing's resolution, so a long run with an
 * unsettled moment after every permit operation does not draw one element per operation. Pieces shorter than a
 * thousandth of the axis are not drawn, and visible pieces closer than that are merged (at most a thousand per band); the exact ranges stay in the data. At
 * the cursor (live: the last event), "may hold" is decided by event index and always marked, however short.
 */
import { pieces } from './at-cursor'
import type { HoldBand, Timeline } from './build-timeline'

export interface UncertainPiece {
  range: [number, number]
  /** After the replay cursor: drawn hollow. */
  after: boolean
  /** The holder is unknown at the cursor itself (drawn with a minimum width). */
  atCursor?: true
}

const RESOLUTION = 1000

export function uncertainPieces(timeline: Timeline, band: HoldBand, cursor: number | null): UncertainPiece[] {
  const unit = timeline.spanNanos / RESOLUTION
  const out: UncertainPiece[] = []
  for (const r of band.uncertain) {
    const p = pieces(timeline, r, cursor)
    for (const [range, after] of [
      [p.filled, false],
      [p.hollow, true],
    ] as const) {
      // Too short to see: not drawn (and never merged into a longer piece). An instant never is.
      if (!range || range[1] <= range[0] || range[1] - range[0] < unit) continue
      const last = out[out.length - 1]
      if (last && last.after === after && range[0] - last.range[1] < unit) last.range = [last.range[0], Math.max(last.range[1], range[1])]
      else out.push({ range: [range[0], range[1]], after })
    }
  }
  const shown = out
  const at = cursor ?? timeline.events.length - 1
  const coversAt = (r: { from: number; to: number | null }) => r.from <= at && (r.to === null || r.to > at)
  if (coversAt(band) && band.uncertain.some(coversAt)) {
    const t = timeline.times[at] ?? 0
    if (!shown.some(p => !p.after && p.range[0] <= t && p.range[1] >= t)) shown.push({ range: [t, t], after: false, atCursor: true })
  }
  return shown
}

export interface Mark {
  /** The first event index it stands for. */
  at: number
  /** Where it is drawn. */
  nanos: number
  /** How many marks it stands for. */
  count: number
  after: boolean
}

/**
 * Marks at events ([indices], in order) at the drawing's resolution: at most one per [buckets]th of the axis (a
 * hundredth by default), either side of the cursor, each counting the marks it stands for.
 */
export function bucketMarks(timeline: Timeline, indices: readonly number[], cursor: number | null, buckets = 100): Mark[] {
  const unit = timeline.spanNanos / buckets
  const out: Mark[] = []
  for (const at of indices) {
    const nanos = timeline.times[at] ?? 0
    const after = cursor !== null && at > cursor
    const last = out[out.length - 1]
    // "<=": on a zero-length axis every mark is at one time, and they merge.
    if (last && last.after === after && nanos - last.nanos <= unit) last.count++
    else out.push({ at, nanos, count: 1, after })
  }
  return out
}

import type { VizEvent } from '@/types/api'

/**
 * The events in seq order. The live stream appends events as they arrive, and accepts one whose seq is lower
 * than one already held (use-event-stream.ts); the projections reduce in order, so an earlier event that
 * arrived late must not undo a later one (a suspension after its own resume). Returns the same array, not a
 * copy, when it is already in order — the usual case — and never sorts the caller's array in place.
 */
export function inSeqOrder(events: readonly VizEvent[]): readonly VizEvent[] {
  for (let i = 1; i < events.length; i++) {
    if (events[i]!.seq < events[i - 1]!.seq) return [...events].sort((a, b) => a.seq - b.seq)
  }
  return events
}

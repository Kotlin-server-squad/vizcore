import type { VizEvent } from '@/types/api'

/**
 * What the replay slider announces (S-14): "4.2 seconds of 12.0", from the
 * first event of the frozen replay to the cursor's, over the whole replay.
 * The slider still steps by event (seconds arrive with Part 8); this only
 * says where in time each step is.
 */
export function replayTimeText(events: readonly VizEvent[], index: number): string | undefined {
  const first = events[0]
  const last = events[events.length - 1]
  const at = events[Math.min(Math.max(index, 0), events.length - 1)]
  if (!first || !last || !at) return undefined
  const s = (nanos: number) => (nanos / 1e9).toFixed(1)
  return `${s(at.tsNanos - first.tsNanos)} seconds of ${s(last.tsNanos - first.tsNanos)}`
}

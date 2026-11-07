import type { VizEvent } from '@/types/api'
import { replayClock, seconds } from './replay-seek'

/**
 * What the replay slider announces (S-14): "4.2 seconds of 12.0", on the replay clock (Part 8, M-2): from the first
 * event of the frozen replay to the cursor, never going back when threads' events interleave.
 */
export function replayTimeText(events: readonly VizEvent[], index: number): string | undefined {
  if (events.length === 0) return undefined
  const clock = replayClock(events)
  const at = clock.times[Math.min(Math.max(index, 0), events.length - 1)]!
  return `${seconds(at)} seconds of ${seconds(clock.spanNanos)}`
}

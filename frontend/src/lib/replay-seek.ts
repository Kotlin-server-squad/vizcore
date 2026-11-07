import type { VizEvent } from '@/types/api'

/**
 * The replay's clock (Part 8, M-2): each event's time since the replay's first event, as a running maximum in seq
 * order. Two threads' events can interleave out of time order; the running maximum keeps the replay's time from going
 * back, so a later seq is never at an earlier time and seeking by time is well defined.
 */
export interface ReplayClock {
  /** Nanos since the first event, one per event, never decreasing. */
  times: number[]
  /** The last event's time: how long the replay is. */
  spanNanos: number
}

export function replayClock(events: readonly VizEvent[]): ReplayClock {
  const first = events[0]
  if (!first) return { times: [], spanNanos: 0 }
  const times: number[] = []
  let at = 0
  for (const e of events) {
    at = Math.max(at, e.tsNanos - first.tsNanos)
    times.push(at)
  }
  return { times, spanNanos: at }
}

/** The last event at or before [nanos] into the replay (binary search over the clock). */
export function indexAtTime(clock: ReplayClock, nanos: number): number {
  const { times } = clock
  if (times.length === 0) return 0
  let lo = 0
  let hi = times.length - 1
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1
    if (times[mid]! <= nanos) lo = mid
    else hi = mid - 1
  }
  return lo
}

/**
 * How the slider moves: in tenths of a second, in milliseconds when the whole replay is shorter than two seconds, and
 * by event when no time passes at all (every event at the same instant).
 */
export type SliderScale = { mode: 'time'; unitNanos: number; max: number } | { mode: 'event' }

export function sliderScale(spanNanos: number): SliderScale {
  if (spanNanos <= 0) return { mode: 'event' }
  const unitNanos = spanNanos < 2e9 ? 1e6 : 1e8
  return { mode: 'time', unitNanos, max: Math.ceil(spanNanos / unitNanos) }
}

/** Seconds with one decimal, the slider's and the time bar's unit ("4.2"). */
export function seconds(nanos: number): string {
  return (nanos / 1e9).toFixed(1)
}

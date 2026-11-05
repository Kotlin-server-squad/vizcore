import { useState } from 'react'
import type { VizEvent } from '@/types/api'

/**
 * From the first to the last event this page has held, on the app's own clock
 * (the run's length in the header, S-3). The bounds only ever widen: pausing
 * the view or clearing the live list empties a list, but the run did not get
 * shorter. Null until two distinct times have been seen.
 */
export function useRecordedSpan(...lists: ReadonlyArray<readonly VizEvent[]>): number | null {
  let first = Infinity
  let last = -Infinity
  for (const events of lists) {
    for (const e of events) {
      if (!Number.isFinite(e.tsNanos)) continue
      if (e.tsNanos < first) first = e.tsNanos
      if (e.tsNanos > last) last = e.tsNanos
    }
  }
  const [bounds, setBounds] = useState({ first: Infinity, last: -Infinity })
  // Widen during render (React's "adjust state on prop change" pattern), so
  // the span shown is never one render behind the events.
  if (first < bounds.first || last > bounds.last) {
    setBounds({ first: Math.min(first, bounds.first), last: Math.max(last, bounds.last) })
  }
  const lo = Math.min(first, bounds.first)
  const hi = Math.max(last, bounds.last)
  return hi > lo ? hi - lo : null
}

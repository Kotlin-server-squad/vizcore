/**
 * The timeline in words, for keyboard and screen-reader users (S-14; plan T-11): what a row's bars, bands and marks
 * show, and what a lane shows, up to the cursor in replay. The tooltips say the same per piece.
 */
import { stateLabel } from '../coroutine-state-colors'
import { seconds } from '../replay-seek'
import type { WaitKind } from '@/types/api'
import { pieces } from './at-cursor'
import type { HoldBand, Timeline, TimelineLane, TimelineRow } from './build-timeline'
import { aPermit } from './row-sentence'

/** The most entries a description lists; the rest are counted. */
const MAX_ENTRIES = 30

export function waitWords(kind: WaitKind, resource: string | null): string {
  switch (kind) {
    case 'MUTEX':
      return `waits for ${resource ?? 'a lock'}`
    case 'SEMAPHORE':
      return `waits for ${resource ? aPermit(resource) : 'a permit'}`
    case 'CHANNEL_SEND':
      return resource ? `waits to send to ${resource}` : 'waits to send'
    case 'CHANNEL_RECEIVE':
      return resource ? `waits to receive from ${resource}` : 'waits to receive'
    default:
      return 'waits'
  }
}

/** "1.2–3.4 s". */
export function between(range: [number, number]): string {
  return `${seconds(range[0])}–${seconds(range[1])} s`
}

/** What a hold band is of: "inventory-lock", "a payment-gateway permit". */
export function holdWhat(band: HoldBand, name: string): string {
  return band.kind === 'lock' ? name : aPermit(name)
}

/** The row's terminal event that a band outlasted, if any. */
export function endOutlasted(row: TimelineRow, band: HoldBand): { state: string; at: number } | undefined {
  return row.ends.find(e => e.at >= band.from && (band.to === null || band.to > e.at))
}

function finish(entries: string[], later: boolean): string {
  const shown = entries.slice(0, MAX_ENTRIES)
  const more = entries.length - shown.length
  return [
    ...shown,
    ...(more > 0 ? [`and ${more} more`] : []),
    ...(later ? ['more after the cursor'] : []),
  ].join('; ')
}

export function describeRow(
  timeline: Timeline,
  row: TimelineRow | undefined,
  cursor: number | null,
  nameOf: (resourceId: string) => string,
): string {
  if (!row) return 'no recorded intervals in these events'
  if (row.createdBefore) return 'created before these events: its states are not drawn'
  const entries: string[] = []
  let later = false
  for (const span of row.spans) {
    const { filled, hollow } = pieces(timeline, span, cursor)
    if (hollow) later = true
    if (!filled) continue
    const wait = span.wait ? `, ${waitWords(span.wait.kind, span.wait.resourceId ? nameOf(span.wait.resourceId) : null)}` : ''
    entries.push(`${stateLabel(span.state)}${wait}, ${between(filled)}`)
  }
  for (const band of row.holds) {
    const { filled, hollow } = pieces(timeline, band, cursor)
    if (hollow) later = true
    if (!filled) continue
    const unsure = band.uncertain.some(r => pieces(timeline, r, cursor).filled)
    entries.push(`holds ${holdWhat(band, nameOf(band.resourceId))} ${between(filled)}${unsure ? ' (holder unknown for part of it)' : ''}`)
  }
  for (const end of row.ends) {
    if (cursor !== null && end.at > cursor) {
      later = true
      continue
    }
    entries.push(`${stateLabel(end.state)} at ${seconds(timeline.times[end.at] ?? 0)} s`)
  }
  return finish(entries, later)
}

export function describeLane(
  timeline: Timeline,
  lane: TimelineLane,
  slot: number,
  cursor: number | null,
  labelOf: (coroutineId: string) => string,
): string {
  const entries: string[] = []
  let later = false
  for (const band of lane.bands) {
    if (band.slot !== slot) continue
    const { filled, hollow } = pieces(timeline, band, cursor)
    if (hollow) later = true
    if (!filled) continue
    const unsure = band.uncertain.some(r => pieces(timeline, r, cursor).filled)
    entries.push(`${labelOf(band.coroutineId)} ${unsure ? 'may hold' : 'holds'} it ${between(filled)}${band.end === 'unknown' && band.to !== null && (cursor === null || band.to <= cursor) ? ', then holder unknown' : ''}`)
  }
  if (slot === 0) {
    for (const at of lane.unknownMarks) {
      if (cursor !== null && at > cursor) continue
      entries.push(`a permit came back with an unknown holder at ${seconds(timeline.times[at] ?? 0)} s`)
    }
  }
  const title = lane.kind === 'lock' ? lane.name : `${lane.name} permits`
  return `${title}: ${entries.length > 0 ? finish(entries, later) : later ? 'nothing held yet' : 'never held'}`
}

/**
 * The Inspector's one sentence for a timeline row (S-10; plan T-10), told from the same spans the row draws.
 *
 * Positions ("at 9.6 s") are on the view's clock, as the slider. Durations are differences of the span's own events'
 * raw `tsNanos`, the basis the Timing card's figures use, so another thread's earlier timestamp does not change them.
 * In replay it is told up to the cursor; when events were lost or trimmed it says "in the observed events" (S-6).
 */
import type { WaitKind } from '@/types/api'
import { formatDuration } from '../format-duration'
import { seconds } from '../replay-seek'
import type { HoldBand, StateSpan, Timeline, TimelineRow } from './build-timeline'

export interface SentenceInput {
  timeline: Timeline
  row: TimelineRow | undefined
  name: string
  /** The tree's state for it at the moment. */
  treeState: string
  /** The replay cursor's event index; null live or loaded. */
  cursor: number | null
  /** Events were lost or trimmed. */
  observed: boolean
  nameOf: (resourceId: string) => string
}

const ENDED: Record<string, string> = { COMPLETED: 'completed', FAILED: 'failed', CANCELLED: 'was cancelled' }

/** "a payment-gateway permit", "an inventory permit". */
export function aPermit(name: string): string {
  return `${/^[aeiou]/i.test(name) ? 'an' : 'a'} ${name} permit`
}

function waitText(kind: WaitKind, resourceId: string | null, nameOf: (id: string) => string): string {
  const name = resourceId ? nameOf(resourceId) : null
  switch (kind) {
    case 'MUTEX':
      return `waiting for ${name ?? 'a lock'}`
    case 'SEMAPHORE':
      return `waiting for ${name ? aPermit(name) : 'a permit'}`
    case 'CHANNEL_SEND':
      return name ? `waiting to send to ${name}` : 'waiting to send'
    case 'CHANNEL_RECEIVE':
      return name ? `waiting to receive from ${name}` : 'waiting to receive'
    default:
      return 'waiting'
  }
}

/** The total length of possibly overlapping [from, to] ranges. */
function union(ranges: [number, number][]): number {
  let total = 0
  let end = -Infinity
  for (const [from, to] of [...ranges].sort((a, b) => a[0] - b[0])) {
    if (to <= end) continue
    total += to - Math.max(from, end)
    end = to
  }
  return total
}

const capitalise = (text: string) => text.charAt(0).toUpperCase() + text.slice(1)

/** "a, b, and c"; "a and b" never gets a comma before "and" when there are two (we always have the end clause last). */
function list(parts: string[]): string {
  if (parts.length <= 1) return parts.join('')
  return `${parts.slice(0, -1).join(', ')}, and ${parts[parts.length - 1]}`
}

export function rowSentence({ timeline, row, name, cursor, observed, nameOf }: SentenceInput): string {
  const { events, times } = timeline
  const last = cursor ?? events.length - 1
  const lead: string[] = []
  if (observed) lead.push('in the observed events')
  if (cursor !== null) lead.push(`by ${seconds(times[cursor] ?? 0)} s`)
  const prefix = lead.length > 0 ? `${capitalise(lead.join(', '))}, ` : ''

  if (!row || (row.spans.length === 0 && row.holds.length === 0)) {
    return row?.createdBefore
      ? `${prefix}${name} was created before these events, so its states are not drawn.`
      : `${prefix}${name} has no recorded intervals in these events.`
  }
  if (row.createdBefore) return `${prefix}${name} was created before these events, so its states are not drawn.`

  const raw = (index: number) => events[index]?.tsNanos ?? 0
  /** A piece's duration on its own events' raw clock, up to the cursor. */
  const lasting = (piece: { from: number; to: number | null }) => {
    const end = piece.to === null || piece.to > last ? last : piece.to
    return Math.max(0, raw(end) - raw(piece.from))
  }
  const dur = (nanos: number) => formatDuration(nanos)

  const spans = row.spans.filter(s => s.from <= last)
  if (spans.length === 0 && row.holds.every(b => b.from > last)) return `${prefix}${name} is not created yet at this moment.`
  const isOpen = (p: { to: number | null }) => p.to === null || p.to > last
  const current: StateSpan | undefined = spans.length > 0 && isOpen(spans[spans.length - 1]!) ? spans[spans.length - 1] : undefined
  const past = current ? spans.slice(0, -1) : spans

  const total = (state: string) => past.filter(s => s.state === state).reduce((sum, s) => sum + lasting(s), 0)
  const has = (state: string) => past.some(s => s.state === state)
  const parts: string[] = []
  if (has('ACTIVE')) parts.push(`was active for ${dur(total('ACTIVE'))}`)
  if (has('SUSPENDED')) {
    const waits = new Map<string, number>()
    for (const s of past) {
      if (s.state !== 'SUSPENDED' || !s.wait) continue
      const text = waitText(s.wait.kind, s.wait.resourceId, nameOf)
      waits.set(text, (waits.get(text) ?? 0) + lasting(s))
    }
    const detail = [...waits].map(([text, nanos]) => `${dur(nanos)} of it ${text}`).join(', ')
    parts.push(`was suspended for ${dur(total('SUSPENDED'))}${detail ? ` (${detail})` : ''}`)
  }
  if (has('WAITING_FOR_CHILDREN')) parts.push(`waited ${dur(total('WAITING_FOR_CHILDREN'))} for its children`)

  // Holds, per resource, up to the cursor.
  // Its latest end up to the cursor, unless it was created again since.
  const lastEnd = [...row.ends].reverse().find(e => e.at <= last)
  const ended = lastEnd && !spans.some(sp => sp.from > lastEnd.at) ? lastEnd : null
  const stillHeld: string[] = []
  const byResource = new Map<string, HoldBand[]>()
  for (const band of row.holds) {
    if (band.from > last) continue
    byResource.set(band.resourceId, [...(byResource.get(band.resourceId) ?? []), band])
  }
  for (const [resourceId, bands] of byResource) {
    const resource = nameOf(resourceId)
    const what = bands[0]!.kind === 'lock' ? resource : bands.length === 1 ? aPermit(resource) : `${resource} permits`
    // Permits held at once count once: the time it held any.
    const nanos = union(bands.map(b => [raw(b.from), raw(b.from) + lasting(b)] as [number, number]))
    // A permit that came back with an unknown holder made whose it is unknown (S-7). The brief unsettled moment between an
    // operation and the snapshot after it is bookkeeping, not doubt about the holder: it is drawn, not said.
    const uncertain = bands.some(
      b => b.unknownMarks.some(m => m <= last) || (b.end === 'unknown' && b.to !== null && b.to <= last),
    )
    const open = bands.some(isOpen)
    // At the cursor whose permit it is may be unknown: then it "may hold" it, as Evidence and the node's line say.
    const unsureNow = bands.some(b => isOpen(b) && b.uncertain.some(r => r.from <= last && (r.to === null || r.to > last)))
    const unknown = 'whose permit was unknown for part of it'
    if (open && !ended && unsureNow) parts.push(`may hold ${what} (holder unknown; listed for ${dur(nanos)} so far)`)
    else if (open && !ended) parts.push(`holds ${what} (for ${dur(nanos)} so far${uncertain ? `; ${unknown}` : ''})`)
    else parts.push(`held ${what} for ${dur(nanos)}${uncertain ? ` (${unknown})` : ''}`)
    if (open && ended) stillHeld.push(`${unsureNow ? 'may still hold' : 'still holds'} ${bands[0]!.kind === 'lock' ? resource : aPermit(resource)}`)
  }

  if (ended) {
    parts.push(`${ENDED[ended.state]} at ${seconds(times[ended.at] ?? 0)} s`)
  } else if (current) {
    const now = lasting(current)
    const waiting = current.wait ? `, ${waitText(current.wait.kind, current.wait.resourceId, nameOf)}` : ''
    const label: Record<string, [string, string]> = {
      ACTIVE: ['is active', 'has been active for'],
      SUSPENDED: ['is suspended', 'has been suspended for'],
      WAITING_FOR_CHILDREN: ['is waiting for its children', 'has been waiting for its children for'],
      CREATED: ['is created, not started yet', 'has been created, not started, for'],
    }
    const [is, hasBeen] = label[current.state] ?? [`is ${current.state.toLowerCase()}`, `has been ${current.state.toLowerCase()} for`]
    parts.push(now > 0 ? `${hasBeen} ${dur(now)}${waiting}` : `${is}${waiting}`)
  }

  const after = stillHeld.length > 0 && ended ? `; it ${list(stillHeld)} after it ${ENDED[ended.state]}` : ''
  return `${prefix}${name} ${list(parts)}${after}.`
}

import type { CoroutineNode, VizEvent } from '@/types/api'
import { LIVE_EVENTS_MAX } from './live-events-max'
import type { AnchorEvent, StoryAnchor } from '@/content/gallery/story-anchors'
import { projectCoroutines } from './projections/project-coroutines'
import { replayClock } from './replay-seek'

/**
 * Story mode's reading of the page's events (spec "Story anchors", plan P-7, P-9). Pure: the
 * panel and the page wire it up.
 */

/** The page's event kind for each anchor event (lifecycle events are kebab-case on the page). */
const KIND: Record<AnchorEvent, string> = {
  CoroutineCreated: 'coroutine.created',
  CoroutineStarted: 'coroutine.started',
  CoroutineSuspended: 'coroutine.suspended',
  CoroutineResumed: 'coroutine.resumed',
  CoroutineBodyCompleted: 'coroutine.body-completed',
  CoroutineCompleted: 'coroutine.completed',
  CoroutineCancelled: 'coroutine.cancelled',
  CoroutineFailed: 'coroutine.failed',
}

/** The two lifecycle events the stream may shed under load (`StructuralClassifier.kt`); the others never are. */
const SHEDDABLE = new Set<AnchorEvent>(['CoroutineSuspended', 'CoroutineResumed'])

const TERMINAL = new Set(['coroutine.completed', 'coroutine.cancelled', 'coroutine.failed'])

/** What the page holds to read a story from. */
export interface HeldEvents {
  /** In seq order. */
  events: readonly VizEvent[]
  /** The live stream is on: events not here yet may still come. */
  streaming: boolean
  /** Events the stream reported shed (`droppedCount`). */
  shedCount: number
  /** The server dropped old events, or the page's live list hit its cap. */
  trimmed: boolean
  /** Labels of every coroutine the session has (its snapshot, which keeps trimmed ones). */
  sessionLabels: ReadonlySet<string>
}

/** One list of events the page holds, and how many the stream shed before it. */
export interface EventSource {
  events: readonly VizEvent[]
  shedCount: number
}

const lastSeq = (events: readonly VizEvent[]) => events[events.length - 1]?.seq ?? 0

/**
 * What the story reads (plan P-7): the live list (or the stored history), or the frozen replay when
 * that reaches further, so pausing after a Reveal never makes a moment "disappear". Each source
 * keeps its own shed count: the live one restarts with the stream.
 */
export function storyHeld({
  live,
  frozen,
  streaming,
  serverTrimmed,
  coroutines,
}: {
  live: EventSource
  frozen: EventSource | null
  streaming: boolean
  serverTrimmed: boolean
  /** The session snapshot's coroutines, which keep trimmed ones. */
  coroutines: readonly CoroutineNode[]
}): HeldEvents {
  const source = frozen && lastSeq(frozen.events) > lastSeq(live.events) ? frozen : live
  return {
    events: source.events,
    streaming,
    shedCount: source.shedCount,
    trimmed: serverTrimmed || source.events.length >= LIVE_EVENTS_MAX,
    sessionLabels: new Set(coroutines.map(c => c.label).filter((l): l is string => !!l)),
  }
}

/** A chapter's moment, as far as the held events tell. */
export type AnchorStatus =
  | { kind: 'ready'; seq: number; coroutineId: string }
  /** Not reached yet; the stream is on. */
  | { kind: 'waiting' }
  /** Not in the held events, and the view is paused. */
  | { kind: 'not-held' }
  /** The held events contain the end of the run, and the moment is not in them. */
  | { kind: 'missed' }
  /** The coroutine is in the session but its creation is not held: its early events were trimmed. */
  | { kind: 'trimmed' }
  /** The anchor counts events the stream shed some of, so its count cannot be trusted. */
  | { kind: 'incomplete' }

/** True when the events contain the end of the run: every coroutine created in them has ended in them. */
export function runEnded(events: readonly VizEvent[]): boolean {
  const open = new Set<string>()
  let any = false
  for (const e of events) {
    const id = (e as { coroutineId?: string }).coroutineId
    if (!id) continue
    if (e.kind === 'coroutine.created') {
      any = true
      open.add(id)
    } else if (TERMINAL.has(e.kind)) {
      open.delete(id)
    }
  }
  return any && open.size === 0
}

/**
 * Finds the anchor in the held events: the labelled coroutine by its creation, then its
 * [StoryAnchor.occurrence]th event of that kind. Trimming drops the oldest events first, so when
 * the creation is held, every later event of it is held too.
 */
export function resolveAnchor(anchor: StoryAnchor, held: HeldEvents): AnchorStatus {
  const { events } = held
  const creation = events.find(e => e.kind === 'coroutine.created' && (e as { label?: string | null }).label === anchor.coroutine)
  const unreliable = SHEDDABLE.has(anchor.event) && held.shedCount > 0
  if (!creation) {
    if (held.trimmed && held.sessionLabels.has(anchor.coroutine)) return { kind: 'trimmed' }
    return notFound(held)
  }
  const id = (creation as { coroutineId: string }).coroutineId
  if (unreliable) return { kind: 'incomplete' }
  const kind = KIND[anchor.event]
  let count = 0
  for (const e of events) {
    if (e.seq < creation.seq || e.kind !== kind || (e as { coroutineId?: string }).coroutineId !== id) continue
    count += 1
    if (count === anchor.occurrence) return { kind: 'ready', seq: e.seq, coroutineId: id }
  }
  return notFound(held)
}

function notFound(held: HeldEvents): AnchorStatus {
  if (runEnded(held.events)) return { kind: 'missed' }
  return held.streaming ? { kind: 'waiting' } : { kind: 'not-held' }
}

/** One expected state, and what the tree shows at the anchor (null: no such coroutine yet). */
export interface StateCheck {
  label: string
  expected: string
  actual: string | null
}

/** What was recorded at a chapter's moment: when, on the replay clock, and the expected states. */
export interface MomentFacts {
  /** Since the first of [events], as the replay slider counts. */
  atNanos: number
  checks: StateCheck[]
}

/** The tree at event [index] of [events] (`projectCoroutines` of the events up to it), checked against [expect]. */
export function momentFacts(events: readonly VizEvent[], index: number, expect: Record<string, string> = {}): MomentFacts {
  const tree = projectCoroutines(events.slice(0, index + 1) as VizEvent[])
  const byLabel = new Map<string, string>()
  for (const node of tree) if (node.label && !byLabel.has(node.label)) byLabel.set(node.label, node.state)
  return {
    atNanos: replayClock(events).times[index] ?? 0,
    checks: Object.entries(expect).map(([label, expected]) => ({ label, expected, actual: byLabel.get(label) ?? null })),
  }
}

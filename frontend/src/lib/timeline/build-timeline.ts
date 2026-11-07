/**
 * The Timeline's data (spec 2026-11-05, S-10; plan 2026-11-07-part10-timeline T-2 to T-7): one pass over the page's
 * events, in seq order, into a row per coroutine and a lane per lock or permit slot.
 *
 * Everything is kept by **event index** (into `events`, the events in seq order): what a cursor shows is decided by
 * index, and `times` (the replay clock's rule, Part 8 M-2: a running maximum of `tsNanos` since the first event) only
 * places it on the axis.
 *
 * - A row's **state spans** follow `projectCoroutines`' transitions, so at any event they give the state the tree shows.
 *   Lifecycle events of a coroutine whose creation is not in the events are ignored, as the tree does: such a row has
 *   no spans (`createdBefore`), and its state comes from the tree.
 * - **Holds** are `projectSync`'s facts over time (the same fold, `syncFold`, run event by event): a lock band runs while
 *   the coroutine holds the lock; a permit band while it is a listed holder, one per permit when the counts are
 *   settled. A permit band is uncertain ("may hold") exactly while the Evidence tab says so. A hold is not ended by
 *   its coroutine's end.
 * - A permit that came back with an unknown holder (S-7) ends the certain part of every open band of that semaphore
 *   with an unknown mark; a band whose holder stops being listed without a release of its own ends with one too.
 */
import type { CoroutineSuspendedEvent, SemaphorePermitReleased, VizEvent, WaitKind } from '@/types/api'
import { inSeqOrder } from '../projections/in-seq-order'
import { syncFold, type SemaphoreState } from '../projections/project-sync'
import { replayClock } from '../replay-seek'
import { resourceNames } from '../selection'

export interface StateSpan {
  /** A `CoroutineState` name. */
  state: string
  /** Index of the event that started it. */
  from: number
  /** Index of the event that ended it; null while it is still open after the last event. */
  to: number | null
  /** What a wrapper recorded this suspension waited for (S-7). */
  wait: { kind: WaitKind; resourceId: string | null } | null
}

export type EndState = 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface Range {
  from: number
  /** Null: until the band ends. */
  to: number | null
}

export interface HoldBand {
  coroutineId: string
  resourceId: string
  kind: 'lock' | 'permit'
  /** The lane slot it is drawn in: 0 for a lock; for permits, the lowest slot free when it began. */
  slot: number
  from: number
  to: number | null
  /**
   * Its holder released it ('released'); it stopped being held with no release of the holder's own ('unknown'); or the
   * same permit goes on in another slot ('moved': no end is drawn).
   */
  end: 'released' | 'unknown' | 'moved' | null
  /** Where whose permit it is is not known ("may hold"). */
  uncertain: Range[]
  /** Releases with an unknown holder that ended a certain part of it (S-7). */
  unknownMarks: number[]
}

export interface TimelineRow {
  id: string
  spans: StateSpan[]
  /** Its terminal event, when it has finished (and was not created again since). */
  end: { state: EndState; at: number } | null
  /** Every terminal event it had, in order: a coroutine created again keeps its earlier end. */
  ends: { state: EndState; at: number }[]
  /** Its creation is not in the events: its states are not drawn (the tree's state is what is known). */
  createdBefore: boolean
  holds: HoldBand[]
}

export interface TimelineLane {
  resourceId: string
  kind: 'lock' | 'permit'
  name: string
  /** How many slots its bands use: 1 for a lock; for permits, the most in use at once. */
  slots: number
  bands: HoldBand[]
  /** Releases with an unknown holder (permits only). */
  unknownMarks: number[]
  /** A semaphore's permits, when an event said; null for a lock or when unknown. */
  permits: number | null
}

/** A resource's open bands, indexed. */
interface OpenBands {
  all: Set<HoldBand>
  byHolder: Map<string, HoldBand[]>
  slots: Set<number>
}

export interface Timeline {
  /** The events in seq order: every index here is into this list. */
  events: readonly VizEvent[]
  /** Nanos since the first event, one per event, never decreasing. */
  times: number[]
  spanNanos: number
  rows: ReadonlyMap<string, TimelineRow>
  lanes: TimelineLane[]
}

const STATE_OF: Record<string, string> = {
  'coroutine.created': 'CREATED',
  'coroutine.started': 'ACTIVE',
  'coroutine.resumed': 'ACTIVE',
  'coroutine.suspended': 'SUSPENDED',
  'coroutine.body-completed': 'WAITING_FOR_CHILDREN',
}

const END_OF: Record<string, EndState> = {
  'coroutine.completed': 'COMPLETED',
  'coroutine.failed': 'FAILED',
  'coroutine.cancelled': 'CANCELLED',
}

interface Fields {
  coroutineId?: string
  mutexId?: string
  semaphoreId?: string
  releaserId?: string
}

export function buildTimeline(input: readonly VizEvent[]): Timeline {
  const events = inSeqOrder(input)
  const { times, spanNanos } = replayClock(events)
  const names = resourceNames(events)

  // Coroutines whose creation is in the events; the others have no drawn states (T-4).
  const createdIds = new Set<string>()
  for (const e of events) if (e.kind === 'coroutine.created') createdIds.add((e as Fields).coroutineId!)

  const rows = new Map<string, TimelineRow>()
  const rowOf = (id: string): TimelineRow => {
    let row = rows.get(id)
    if (!row) {
      row = { id, spans: [], end: null, ends: [], createdBefore: !createdIds.has(id), holds: [] }
      rows.set(id, row)
    }
    return row
  }

  const lanes = new Map<string, TimelineLane>()
  const laneOf = (resourceId: string, kind: 'lock' | 'permit'): TimelineLane => {
    let lane = lanes.get(resourceId)
    if (!lane) {
      lane = {
        resourceId,
        kind,
        name: names.get(resourceId) ?? resourceId,
        slots: kind === 'lock' ? 1 : 0,
        bands: [],
        unknownMarks: [],
        permits: null,
      }
      lanes.set(resourceId, lane)
    }
    return lane
  }

  const fold = syncFold()
  // Open bands per resource: in order, by holder (oldest first) and the slots they take — indexed, so a semaphore
  // with many listed holders costs each event only its own holders.
  const open = new Map<string, OpenBands>()
  const openOf = (resourceId: string): OpenBands => {
    let set = open.get(resourceId)
    if (!set) {
      set = { all: new Set(), byHolder: new Map(), slots: new Set() }
      open.set(resourceId, set)
    }
    return set
  }
  // Per semaphore, holder -> indices of its own attributed releases not yet matched to a band's end.
  const releases = new Map<string, Map<string, number[]>>()
  // Semaphores whose holders are unsettled or partly unknown after the last event.
  const ambiguous = new Set<string>()

  const openBand = (resourceId: string, kind: 'lock' | 'permit', coroutineId: string, at: number, uncertain: boolean) => {
    const set = openOf(resourceId)
    let slot = 0
    while (set.slots.has(slot)) slot++
    const band: HoldBand = {
      coroutineId,
      resourceId,
      kind,
      slot,
      from: at,
      to: null,
      end: null,
      uncertain: uncertain ? [{ from: at, to: null }] : [],
      unknownMarks: [],
    }
    set.all.add(band)
    set.slots.add(slot)
    set.byHolder.set(coroutineId, [...(set.byHolder.get(coroutineId) ?? []), band])
    const lane = laneOf(resourceId, kind)
    lane.bands.push(band)
    lane.slots = Math.max(lane.slots, slot + 1)
    rowOf(coroutineId).holds.push(band)
  }

  /** Ends a band's open "may hold" range: only its last one can be open. */
  const closeRange = (band: HoldBand, at: number) => {
    const last = band.uncertain[band.uncertain.length - 1]
    if (last && last.to === null) last.to = at
  }

  const closeBand = (band: HoldBand, at: number, end: 'released' | 'unknown' | 'moved') => {
    band.to = at
    band.end = end
    closeRange(band, at)
    const set = openOf(band.resourceId)
    set.all.delete(band)
    set.slots.delete(band.slot)
    const left = (set.byHolder.get(band.coroutineId) ?? []).filter(b => b !== band)
    if (left.length > 0) set.byHolder.set(band.coroutineId, left)
    else set.byHolder.delete(band.coroutineId)
  }

  /** The lock's holder after this event, as `projectSync` has it. */
  const reconcileLock = (mutexId: string, event: VizEvent, at: number) => {
    const m = fold.mutexes.get(mutexId)
    const holder = m?.isLocked ? m.currentHolder : null
    const current = open.get(mutexId)?.all.values().next().value
    if (current && current.coroutineId === holder) return
    if (current) {
      // Its own unlock, or the lock passing on (VizMutex records the release after the next acquisition): released.
      const f = event as Fields & { acquirerId?: string }
      const released =
        (event.kind === 'MutexUnlocked' && f.releaserId === current.coroutineId) ||
        (event.kind === 'MutexLockAcquired' && f.acquirerId === holder)
      closeBand(current, at, released ? 'released' : 'unknown')
    }
    if (holder) openBand(mutexId, 'lock', holder, at, false)
    else laneOf(mutexId, 'lock')
  }

  /** The semaphore's listed holders after this event, as `projectSync` has them. */
  const reconcileSemaphore = (state: SemaphoreState, at: number) => {
    const id = state.semaphoreId
    const unsure = state.unsettled || state.unknownHolderPermits > 0
    const own = releases.get(id) ?? new Map<string, number[]>()
    releases.set(id, own)
    /** "released" only for the holder's own attributed release since this band began. */
    const endOf = (band: HoldBand): 'released' | 'unknown' => {
      const pending = own.get(band.coroutineId) ?? []
      const i = pending.findIndex(r => r >= band.from)
      if (i < 0) return 'unknown'
      own.set(band.coroutineId, pending.filter((_, n) => n !== i))
      return 'released'
    }

    const set = openOf(id)
    const listed = new Map(state.activeHolders.map(h => [h.id, h.permits]))
    const want = (holder: string, held: number) => (unsure ? Math.max(held, 1) : (listed.get(holder) ?? 0))
    // Ends first, so a reshuffle between two holders reuses the slots it frees.
    for (const [holder, bands] of [...set.byHolder]) {
      if (!listed.has(holder)) for (const band of bands) closeBand(band, at, endOf(band))
    }
    for (const holder of listed.keys()) {
      const held = set.byHolder.get(holder) ?? []
      // Fewer permits than bands: the oldest end (VizSemaphore releases a coroutine's oldest permit first).
      for (const band of held.slice(0, Math.max(0, held.length - want(holder, held.length)))) closeBand(band, at, endOf(band))
    }
    for (const holder of listed.keys()) {
      const held = set.byHolder.get(holder)?.length ?? 0
      // While unsure no count is trusted: a holder keeps the bands it has, and a newly listed one gets one.
      for (let n = held; n < want(holder, held); n++) openBand(id, 'permit', holder, at, unsure)
    }
    laneOf(id, 'permit')

    // "May hold" begins and ends with the semaphore's ambiguity.
    const wasUnsure = ambiguous.has(id)
    if (unsure && !wasUnsure) {
      for (const band of set.all) {
        if (band.from !== at || band.uncertain.length === 0) band.uncertain.push({ from: at, to: null })
      }
      ambiguous.add(id)
    } else if (!unsure && wasUnsure) {
      for (const band of set.all) closeRange(band, at)
      ambiguous.delete(id)
    }
    if (!unsure) {
      // Settled: a release not matched to a band's end by now ended none.
      own.clear()
      // A band left beyond the permits while candidates outnumbered them moves into a free slot (T-7).
      const total = state.totalPermits
      if (total > 0 && set.slots.size > 0) {
        for (const band of [...set.all]) {
          if (band.slot < total) continue
          let free = 0
          while (free < total && set.slots.has(free)) free++
          if (free >= total) break
          closeBand(band, at, 'moved')
          openBand(id, 'permit', band.coroutineId, at, false)
        }
      }
    }
  }

  events.forEach((event, at) => {
    const f = event as Fields

    // Holds: projectSync's state after this event.
    if (f.mutexId || f.semaphoreId) {
      let unknownRelease = false
      if (event.kind === 'SemaphorePermitReleased') {
        const e = event as unknown as SemaphorePermitReleased
        const before = fold.semaphores.get(e.semaphoreId)
        const listed = !!before?.activeHolders.some(h => h.id === e.releaserId)
        unknownRelease = e.holderUnknown === true || (e.holderUnknown === undefined && !listed)
        const holding = (open.get(e.semaphoreId)?.byHolder.get(e.releaserId)?.length ?? 0) > 0
        if (!unknownRelease && holding) {
          const own = releases.get(e.semaphoreId) ?? new Map<string, number[]>()
          own.set(e.releaserId, [...(own.get(e.releaserId) ?? []), at])
          releases.set(e.semaphoreId, own)
        }
      }
      if (fold.apply(event)) {
        if (f.mutexId) reconcileLock(f.mutexId, event, at)
        else if (f.semaphoreId) {
          const state = fold.semaphores.get(f.semaphoreId)
          if (unknownRelease) {
            // A permit came back and whose is unknown: every open band's certain part ends here (S-7).
            laneOf(f.semaphoreId, 'permit').unknownMarks.push(at)
            for (const band of open.get(f.semaphoreId)?.all ?? []) band.unknownMarks.push(at)
          }
          if (state) reconcileSemaphore(state, at)
        }
      }
      return
    }

    // States: projectCoroutines' transitions, for coroutines created in these events.
    const id = f.coroutineId
    if (!id) return
    const next = STATE_OF[event.kind]
    const ended = END_OF[event.kind]
    if (!next && !ended) return
    if (!createdIds.has(id)) {
      rowOf(id)
      return
    }
    if (!rows.get(id)?.spans.length && event.kind !== 'coroutine.created') return // before its creation, as the tree
    const row = rowOf(id)
    const last = row.spans[row.spans.length - 1]
    if (last && last.to === null) last.to = at
    if (ended) {
      row.end = { state: ended, at }
      row.ends.push(row.end)
      return
    }
    row.end = null
    const suspended = event as CoroutineSuspendedEvent
    row.spans.push({
      state: next!,
      from: at,
      to: null,
      wait: event.kind === 'coroutine.suspended' && suspended.waitKind ? { kind: suspended.waitKind, resourceId: suspended.resourceId ?? null } : null,
    })
  })

  for (const lane of lanes.values()) {
    const total = lane.kind === 'permit' ? fold.semaphores.get(lane.resourceId)?.totalPermits : undefined
    if (total) lane.permits = total
  }
  return { events, times, spanNanos, rows, lanes: [...lanes.values()] }
}

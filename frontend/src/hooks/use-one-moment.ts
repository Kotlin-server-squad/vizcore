import { useMemo } from 'react'
import type { Moment } from '@/components/workspace/MomentNote'
import type { MomentRead, MomentSource } from '@/lib/moment-read'
import { coroutineChangesBetween, indexReplay } from '@/lib/replay-index'
import { replayClock } from '@/lib/replay-seek'
import type { CoroutineNode, VizEvent } from '@/types/api'
import type { MomentView } from './use-moment'
import type { UseReplayReturn } from './use-replay'

/** How long the cursor must stay put before a read (M-4). */
const SESSION_SETTLE_MS = 300

interface Args {
  sessionId: string
  readOnly: boolean
  replayActive: boolean
  replay: UseReplayReturn
  /** The coroutines projected from the replayed events up to the cursor. */
  projectedAtCursor: CoroutineNode[]
  /** The events the page holds outside replay (a shared link: the loaded cut's). */
  allEvents: readonly VizEvent[]
}

/**
 * The page's one moment (Part 8, S-11): in replay the cursor, in a shared link the loaded snapshot, live none. It says
 * what every part shows there: the cursor's coroutines, the replay clock's time (M-2), the marker (M-6) and how the
 * Inspector's reads bind (M-3, M-4). A shared link's reads bind to its loaded cut in Part 8b.
 */
export function useOneMoment({ sessionId, readOnly, replayActive, replay, projectedAtCursor, allEvents }: Args) {
  const { events, currentIndex, currentEvent } = replay
  const clock = useMemo(() => replayClock(events), [events])
  const index = useMemo(() => indexReplay(events), [events])
  const source: MomentSource = useMemo(() => ({ kind: 'session', sessionId }), [sessionId])
  const cursorSeq = currentEvent?.seq ?? 0
  // The events behind the moment do not start at the session's first (seq 1): the replay's, or a shared link's.
  const momentFirstSeq = replayActive ? events[0]?.seq : readOnly ? allEvents[0]?.seq : undefined
  const incomplete = momentFirstSeq !== undefined && momentFirstSeq > 1
  const replayRead: MomentRead | null = useMemo(
    () => (replayActive && currentEvent ? { source, binding: { kind: 'replay', atSeq: cursorSeq } } : null),
    [replayActive, currentEvent, source, cursorSeq],
  )
  const settleMs = SESSION_SETTLE_MS

  const replayAtNanos = replayActive && events.length > 0 ? (clock.times[currentIndex] ?? 0) : null
  const loadedSeq = allEvents[allEvents.length - 1]?.seq ?? 0
  const moment: Moment | null = useMemo(() => {
    if (replayActive && events.length > 0) {
      return { kind: 'replay', atNanos: replayAtNanos ?? 0, index: currentIndex, total: events.length }
    }
    return readOnly ? { kind: 'loaded', seq: loadedSeq } : null
  }, [replayActive, events.length, replayAtNanos, currentIndex, readOnly, loadedSeq])

  const view: MomentView | null = useMemo(() => {
    if (!moment) return null
    if (replayRead) {
      return {
        read: replayRead,
        cursorSeq,
        changesBetween: (id: string, a: number, b: number) => coroutineChangesBetween(index, id, a, b),
        settleMs,
        hold: false,
        moment,
      }
    }
    return null
  }, [moment, replayRead, cursorSeq, index, settleMs])

  // Every coroutine the replay holds at any point, so a selection the cursor has not reached yet is kept (M-5).
  const { replayCoroutineIds, replayLabels } = useMemo(() => {
    const ids = new Set<string>()
    const labels = new Map<string, string>()
    for (const e of events) {
      if (e.kind !== 'coroutine.created') continue
      const created = e as { coroutineId: string; label?: string | null }
      ids.add(created.coroutineId)
      if (created.label) labels.set(created.coroutineId, created.label)
    }
    return { replayCoroutineIds: ids, replayLabels: labels }
  }, [events])

  return {
    coroutines: projectedAtCursor,
    replayAtNanos,
    moment,
    view,
    incomplete,
    replayCoroutineIds,
    replayLabels,
  }
}

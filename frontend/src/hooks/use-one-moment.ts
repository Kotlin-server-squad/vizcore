import { useMemo } from 'react'
import type { Moment } from '@/components/workspace/MomentNote'
import type { MomentBinding, MomentRead, MomentSource } from '@/lib/moment-read'
import { coroutineChangesBetween, indexReplay } from '@/lib/replay-index'
import { replayClock } from '@/lib/replay-seek'
import type { CoroutineNode, VizEvent } from '@/types/api'
import { useCursorCoroutines, useKnownSummaryNodes } from './use-cursor-coroutines'
import type { MomentView } from './use-moment'
import type { UseReplayReturn } from './use-replay'

/** A shared link's token and the cut its payload was loaded under (Part 8, M-11). */
export interface ShareBinding {
  token: string
  /** The loaded health's `asOfSeq`; null when the payload carried no health. */
  asOfSeq: number | null
  /** The health's cut id (`healthCut`), when there is one. */
  cut: string | null
  /** Why the payload carried no health (`healthUnavailable`), when it did not. */
  unavailable: string | null
}

/** How long the cursor must stay put before a read: session reads, and the smaller shared bucket (M-4). */
const SESSION_SETTLE_MS = 300
const SHARED_SETTLE_MS = 2000

interface Args {
  sessionId: string
  share?: ShareBinding
  readOnly: boolean
  replayActive: boolean
  replay: UseReplayReturn
  /** The coroutines projected from the replayed events up to the cursor. */
  projectedAtCursor: CoroutineNode[]
  /** The events the page holds outside replay (a shared link: the loaded cut's). */
  allEvents: readonly VizEvent[]
}

/**
 * The page's one moment (Part 8, S-11): in replay the cursor, in a shared link the loaded cut, live none. It says what
 * every part shows there: the cursor's coroutines (with the summaries' when the replay is incomplete, M-9), the replay
 * clock's time (M-2), the marker (M-6), how the Inspector's reads bind (M-3, M-4, M-11) and the note on where an
 * incomplete replay starts (M-8).
 */
export function useOneMoment({ sessionId, share, readOnly, replayActive, replay, projectedAtCursor, allEvents }: Args) {
  const { events, currentIndex, currentEvent, isPlaying } = replay
  const clock = useMemo(() => replayClock(events), [events])
  const index = useMemo(() => indexReplay(events), [events])
  const source: MomentSource = useMemo(
    () => (share ? { kind: 'shared', sessionId, token: share.token } : { kind: 'session', sessionId }),
    [share, sessionId],
  )
  const cursorSeq = currentEvent?.seq ?? 0
  // The events behind the moment do not start at the session's first (seq 1): the replay's, or a shared link's.
  const momentFirstSeq = replayActive ? events[0]?.seq : readOnly ? allEvents[0]?.seq : undefined
  const incomplete = momentFirstSeq !== undefined && momentFirstSeq > 1
  // The replay itself starts after the session's beginning (M-8, M-9).
  const firstSeq = events[0]?.seq ?? 1
  const replayIncomplete = replayActive && events.length > 0 && firstSeq > 1
  const replayRead: MomentRead | null = useMemo(
    () => (replayActive && currentEvent ? { source, binding: { kind: 'replay', atSeq: cursorSeq } } : null),
    [replayActive, currentEvent, source, cursorSeq],
  )
  const settleMs = share ? SHARED_SETTLE_MS : SESSION_SETTLE_MS
  // The shared bucket is 60 reads a minute: a shared replay reads only while it is paused.
  const hold = !!share && isPlaying

  const cursor = useCursorCoroutines({
    read: replayRead,
    projected: projectedAtCursor,
    cursorSeq,
    index,
    incomplete: replayIncomplete,
    settleMs,
    hold,
  })

  const replayAtNanos = replayActive && events.length > 0 ? (clock.times[currentIndex] ?? 0) : null
  const loadedSeq = share?.asOfSeq ?? allEvents[allEvents.length - 1]?.seq ?? 0
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
        hold,
        moment,
      }
    }
    if (share && share.asOfSeq != null) {
      const binding: MomentBinding = { kind: 'bound', asOfSeq: share.asOfSeq, asOfCut: share.cut }
      return { read: { source, binding }, cursorSeq: share.asOfSeq, changesBetween: () => false, settleMs: 0, hold: false, moment }
    }
    return null
  }, [moment, replayRead, cursorSeq, index, settleMs, hold, share, source])

  // A shared link without a health has no cut: its Inspector does not guess one (M-11).
  const unavailableReason =
    readOnly && !replayActive && share && share.asOfSeq == null ? (share.unavailable ?? 'no health in the link') : null

  // Coroutines the summaries gave the tree in this replay: kept while a later read updates (M-5).
  const summaryNodes = useKnownSummaryNodes(cursor.coroutines, events)
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
    for (const [id, label] of summaryNodes) {
      ids.add(id)
      labels.set(id, label)
    }
    return { replayCoroutineIds: ids, replayLabels: labels }
  }, [events, summaryNodes])

  const startNote = replayIncomplete
    ? `This replay starts at event ${firstSeq}: earlier events are not in it, so it may be incomplete.${
        cursor.status === 'ready'
          ? ' Coroutines created before it come from the server’s summaries.'
          : cursor.status === 'error'
            ? ' The coroutines created before it could not be read.'
            : ' Updating the coroutines created before it…'
      }`
    : undefined

  return {
    coroutines: cursor.coroutines,
    replayAtNanos,
    moment,
    view,
    unavailableReason,
    incomplete,
    startNote,
    replayCoroutineIds,
    replayLabels,
    summaryNodes,
  }
}

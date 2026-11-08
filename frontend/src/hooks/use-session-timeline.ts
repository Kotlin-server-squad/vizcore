import { useMemo } from 'react'
import { buildTimeline, type Timeline } from '@/lib/timeline/build-timeline'
import { missingNote } from '@/lib/timeline/missing-note'
import { rowSentence } from '@/lib/timeline/row-sentence'
import { resourceNames } from '@/lib/selection'
import type { CoroutineNode, VizEvent } from '@/types/api'

interface Args {
  /** The Timeline is the main view: nothing is built otherwise. */
  enabled: boolean
  replayActive: boolean
  /** Every frozen replay event (the Timeline draws the whole replay, plan T-8). */
  replayEvents: readonly VizEvent[]
  /** The replay cursor's event. */
  cursorEvent: VizEvent | null
  /** Outside replay, the events the page shows: live, paused, or a shared link's loaded cut. */
  events: readonly VizEvent[]
  /** Events the server shed under load, from the stream (S-10's loss count). */
  droppedCount: number
  /** The session's first event's seq when the server never trimmed it (`lib/session-first-seq.ts`); unknown: 1. */
  sessionFirstSeq?: number
  /** The rows: the coroutines the tree shows (T-3). */
  rows: readonly CoroutineNode[]
  /** The selected coroutine, for the Inspector's sentence (T-10). */
  selected: CoroutineNode | null
}

export interface SessionTimeline {
  timeline: Timeline | null
  /** The cursor's index into `timeline.events`; null live or loaded. */
  cursor: number | null
  note: string | null
  sentence: string | null
}

/**
 * What the Timeline (Part 10) draws and says, from the page's own events and moment (Part 8): the whole replay with its
 * cursor, or the events the page shows; the "may be missing" note; and the selected row's sentence. Rounds and groups
 * (Part 6) plug in through `rows`.
 */
export function useSessionTimeline({
  enabled,
  replayActive,
  replayEvents,
  cursorEvent,
  events,
  droppedCount,
  sessionFirstSeq,
  rows,
  selected,
}: Args): SessionTimeline {
  // One pass over at most 10,000 events, in step with the rest of the page (never a moment behind it).
  const source = replayActive ? replayEvents : events
  const timeline = useMemo(() => (enabled ? buildTimeline(source) : null), [enabled, source])
  const names = useMemo(() => (timeline ? resourceNames(timeline.events) : new Map<string, string>()), [timeline])

  const cursor = useMemo(() => {
    if (!timeline || !replayActive || !cursorEvent) return null
    const at = timeline.events.findIndex(e => e.seq === cursorEvent.seq)
    return at >= 0 ? at : null
  }, [timeline, replayActive, cursorEvent])

  const note = useMemo(() => {
    if (!timeline) return null
    const createdBefore = rows.filter(r => {
      const row = timeline.rows.get(r.id)
      return !row || row.createdBefore
    }).length
    return missingNote({ droppedCount, firstSeq: timeline.events[0]?.seq ?? null, sessionFirstSeq, createdBefore })
  }, [timeline, rows, droppedCount, sessionFirstSeq])

  const sentence = useMemo(() => {
    if (!timeline || !selected) return null
    return rowSentence({
      timeline,
      row: timeline.rows.get(selected.id),
      name: selected.label ?? selected.id,
      treeState: selected.state,
      cursor,
      observed: note !== null,
      nameOf: id => names.get(id) ?? id,
    })
  }, [timeline, selected, cursor, note, names])

  return { timeline, cursor, note, sentence }
}

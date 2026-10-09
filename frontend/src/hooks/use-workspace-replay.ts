import { useState, useEffect, useRef, useCallback } from 'react'
import { useReplay } from './use-replay'
import { useRecordReplay } from './use-record-replay'
import { useEventStream } from './use-event-stream'
import type { VizEvent } from '@/types/api'

interface UseWorkspaceReplayArgs {
  sessionId: string
  /** Whether the SSE stream is driving the live view. */
  streamEnabled: boolean
  storedEvents: VizEvent[] | undefined
  /** The region the recorder captures. */
  getPanelEl: () => HTMLElement | null
}

/**
 * Replay + scripted-recording orchestration for the session workspace (D-01..04,
 * D-23, EXPT-02).
 *
 * Lives in one hook because the three are not separable: the recorder freezes
 * the SAME snapshot the replay cursor drives, the auto-seek-to-end that replay
 * needs is the exact thing recording must suppress, and the SSE stream must be
 * told when replay is active so its cache invalidations stay gated (D-02).
 * That last one is why the event stream is opened here rather than by the
 * caller — `useEventStream` needs `replayActive`, and `replayActive` lives here.
 */
export function useWorkspaceReplay({
  sessionId,
  streamEnabled,
  storedEvents,
  getPanelEl,
}: UseWorkspaceReplayArgs) {
  // Replay mode (D-01): when active, panels render from the frozen snapshot's
  // replay cursor instead of the live/stored events. The snapshot is captured
  // at replay entry so live SSE events do not mutate the frozen view (D-02).
  const [replayActive, setReplayActive] = useState(false)
  const [replaySnapshot, setReplaySnapshot] = useState<VizEvent[]>([])

  // The SSE gate: while replaying, the stream stays connected but its cache
  // invalidations are suppressed, so buffered events apply on exit (D-02/D-04).
  const { events: liveEvents, isConnected, clearEvents } = useEventStream(
    sessionId,
    streamEnabled,
    replayActive,
  )

  // Replay drives over the FROZEN snapshot taken at entry (never the live
  // event list), so live SSE events buffer for the badge without re-rendering
  // the replay panels (D-02).
  const replay = useReplay(replaySnapshot)
  const { seekTo: replaySeekTo } = replay

  // Number of live events appended since replay entry — drives the "● N new
  // events" badge (D-02). The SSE stream stays connected during replay (the
  // gate only suppresses invalidation, not the EventSource), so any live
  // events beyond the frozen snapshot are buffered and counted here.
  const newEventsCount = replayActive
    ? Math.max(liveEvents.length - replaySnapshot.length, 0)
    : 0

  // Enter replay: freeze the given snapshot and activate replay. The seek to
  // the end (D-03) is performed by the effect below once useReplay has applied
  // the new snapshot (it resets to index 0 on events-identity change, so the
  // seek must follow that reset).
  //
  // The snapshot is passed EXPLICITLY (WR-08): the replay-toggle button computes
  // it inline from the latest closure, while the recorder computes it ONCE from
  // a ref at click time and passes the SAME list here — so the frozen view and
  // the recorder's auto-stop boundary can never diverge.
  const enterReplay = useCallback(
    (snapshot?: readonly VizEvent[]) => {
      // Defensive: only honor an explicit array snapshot (a stray PressEvent
      // from an onPress handler must fall through to the closure source). The
      // explicit snapshot is copied into a mutable array — it is frozen on
      // entry and never mutated, but `replaySnapshot`/`useReplay` are typed
      // mutable, so a defensive copy keeps the readonly hook contract intact.
      const frozen: VizEvent[] = Array.isArray(snapshot)
        ? [...snapshot]
        : streamEnabled
          ? liveEvents
          : storedEvents || []
      setReplaySnapshot(frozen)
      setReplayActive(true)
    },
    [streamEnabled, liveEvents, storedEvents],
  )

  // Exit replay: drop the cursor and apply buffered events (the gated
  // useEventStream flush re-validates the live panels) — D-04.
  const exitReplay = useCallback(() => {
    setReplayActive(false)
    setReplaySnapshot([])
  }, [])

  // Scripted WebM recording (EXPT-02, plan 02-08): the ExportMenu Record item
  // drives this one-click pipeline — enter replay → seek 0 → record the active
  // panel at 2x while auto-playing → auto-stop at the last event → download.
  // The estimate + auto-stop read whichever events will be frozen on entry: the
  // live snapshot if already replaying, otherwise the source the toggle would
  // freeze (so a one-click record from the live view records the full timeline).
  //
  // WR-08: keep the snapshot source behind a ref so the hook reads it ONCE at
  // click time and freezes exactly that list (rather than two independently
  // closed-over sources that can drift apart by any events arriving in between).
  const recordEvents = replayActive
    ? replaySnapshot
    : streamEnabled
      ? liveEvents
      : storedEvents || []
  const recordEventsRef = useRef<VizEvent[]>(recordEvents)
  recordEventsRef.current = recordEvents
  const recordReplay = useRecordReplay({
    getPanelEl,
    getRecordSnapshot: () => recordEventsRef.current,
    replay,
    enterReplay,
    sessionId,
  })

  // On entering replay (snapshot applied), jump to the end and stay paused
  // (D-03). Keyed on the snapshot identity so re-entry re-seeks; useReplay's
  // own reset-to-0 effect runs first on the same identity change.
  //
  // CR-01: this auto-seek-to-end MUST be suppressed while a recording is being
  // armed or is active. The record flow freezes the SAME snapshot and then
  // seeks to 0 to record the full timeline; if this effect also fired it would
  // clobber the cursor to the LAST index and the recorder would capture only
  // the final frame (a ~0-duration video). Gating on isArming/isRecording lets
  // the record run own the post-enter seek.
  const seekedSnapshotRef = useRef<VizEvent[] | null>(null)
  useEffect(() => {
    if (!replayActive) {
      seekedSnapshotRef.current = null
      return
    }
    if (recordReplay.isArming || recordReplay.isRecording) return
    if (replaySnapshot.length === 0) return
    if (seekedSnapshotRef.current === replaySnapshot) return
    seekedSnapshotRef.current = replaySnapshot
    replaySeekTo(replaySnapshot.length - 1)
  }, [
    replayActive,
    replaySnapshot,
    replaySeekTo,
    recordReplay.isArming,
    recordReplay.isRecording,
  ])

  return {
    liveEvents,
    isConnected,
    clearEvents,
    replayActive,
    replaySnapshot,
    enterReplay,
    exitReplay,
    replay,
    recordReplay,
    newEventsCount,
  }
}

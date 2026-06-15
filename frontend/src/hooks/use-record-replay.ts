/**
 * useRecordReplay — React glue for the EXPT-02 WebM recording tier (D-05..08 / D-23..27).
 *
 * Drives a one-click scripted recording of the active visualization panel:
 *   enter replay → seek 0 → start the recorder → auto-play at the current speed,
 *   capturing one mirror-canvas frame per rendered replay step → auto-stop at the
 *   last event → download `.webm` + success toast.
 *
 * This hook is the only stateful, React-aware layer: it owns the elapsed timer,
 * the confirm-modal state (D-26, >120s), the visibilitychange→hidden abort
 * (D-24), and the controller Stop discard (D-23). All browser-API work lives in
 * the pure `record-replay.ts` pipeline so this hook stays mock-testable.
 *
 * Codec gating (D-25): when `pickMimeType()` returns null (Safari) the hook is
 * inert — `canRecord` is false and `startRecording` constructs no recorder.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import type { UseReplayReturn } from './use-replay'
import {
  createReplayRecorder,
  estimateDurationMs,
  pickMimeType,
  type ReplayRecorder,
} from '@/lib/record-replay'
import { toastError, toastSuccess } from '@/lib/toast'

/** The >2-minute confirm threshold (D-26). */
const CONFIRM_THRESHOLD_MS = 120_000

/** The locked abort copy shown when the tab is backgrounded mid-recording (D-24). */
const ABORT_COPY =
  'Recording cancelled — keep the tab visible while recording.'

/** The locked manual-Stop copy (D-23). */
const DISCARD_COPY = 'Recording discarded'

/** Elapsed-timer tick interval (ms). */
const ELAPSED_TICK_MS = 250

/** The minimal slice of a VizEvent the estimate + auto-stop need. */
interface TimedEvent {
  tsNanos: number
}

export interface UseRecordReplayOptions<E extends TimedEvent> {
  /** Getter for the active visualization panel element (captured lazily). */
  getPanelEl: () => HTMLElement | null
  /**
   * Snapshot the events to freeze + record, read ONCE at click time (WR-08).
   * Returning from a ref (not a closure value) guarantees the frozen
   * `replaySnapshot` and the recorder's auto-stop boundary see the same list.
   */
  getRecordSnapshot: () => readonly E[]
  /** The mounted replay controls (seekTo/play + currentIndex/total). */
  replay: UseReplayReturn
  /**
   * Enter replay mode by freezing the EXPLICIT snapshot (WR-08). Passing the
   * snapshot — rather than letting the caller re-derive it from a stale closure
   * — keeps `replaySnapshot` and `recordEvents` from diverging.
   */
  enterReplay: (snapshot: readonly E[]) => void
  /** Session id used to build the `{id}-replay-{ts}.webm` filename. */
  sessionId: string
}

export interface UseRecordReplayResult {
  /** True when a supported codec exists — gates the Record menu item (D-25). */
  canRecord: boolean
  /** True while a recording is in progress (drives the controller cluster). */
  isRecording: boolean
  /** Elapsed recording time in ms (rendered as m:ss by the controller). */
  elapsedMs: number
  /** Begin the scripted recording (opens the confirm modal when >120s). */
  startRecording: () => void
  /** Stop-and-discard the in-progress recording (controller Stop, D-23). */
  stopRecording: () => void
  /** Whether the D-26 confirm modal is open. */
  confirmOpen: boolean
  /** The estimated duration (ms) shown in the confirm modal. */
  confirmEstimateMs: number
  /** The playback speed shown in the confirm modal. */
  confirmSpeed: number
  /** Proceed past the confirm modal and record (D-26). */
  confirmRecord: () => void
  /** Dismiss the confirm modal without recording. */
  cancelConfirm: () => void
  /**
   * True from the moment a recording is requested until the recorder has been
   * armed and playback started (CR-01). SessionDetails suppresses its D-03
   * auto-seek-to-end while this is set so the record run starts from index 0.
   */
  isArming: boolean
}

/**
 * Drive a scripted WebM recording over the mounted replay.
 *
 * @param mimeTypeOverride - test seam for `pickMimeType` (null ⇒ Safari path).
 */
export function useRecordReplay<E extends TimedEvent>(
  {
    getPanelEl,
    getRecordSnapshot,
    replay,
    enterReplay,
    sessionId,
  }: UseRecordReplayOptions<E>,
  mimeTypeOverride?: string | null,
): UseRecordReplayResult {
  const mimeType = mimeTypeOverride !== undefined ? mimeTypeOverride : pickMimeType()
  const canRecord = mimeType !== null

  const [isRecording, setIsRecording] = useState(false)
  const [isArming, setIsArming] = useState(false)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmEstimateMs, setConfirmEstimateMs] = useState(0)

  const recorderRef = useRef<ReplayRecorder | null>(null)
  const elapsedTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const startedAtRef = useRef<number>(0)
  // Idempotency guard for the terminal stop/discard paths (CR-02 / WR-07): once
  // set, the auto-stop branch and discardRecording become no-ops so stop() can
  // never fire twice and a hidden-tab abort cannot race the in-flight download.
  const stoppingRef = useRef(false)
  // Pending arm request: the snapshot length + recorder config captured at click
  // time. The arm effect (below) fires once replay.totalEvents settles to this
  // length, then seeks to 0, builds the recorder, and starts playback (CR-01).
  const pendingArmRef = useRef<{ length: number; mimeType: string } | null>(null)
  // Mirror current values for the visibilitychange listener (registered once).
  const isRecordingRef = useRef(false)
  isRecordingRef.current = isRecording

  // Keep live references the scripted flow reads without re-subscribing.
  const replayRef = useRef(replay)
  replayRef.current = replay
  const getRecordSnapshotRef = useRef(getRecordSnapshot)
  getRecordSnapshotRef.current = getRecordSnapshot
  const getPanelElRef = useRef(getPanelEl)
  getPanelElRef.current = getPanelEl

  const speed = replay.speed

  const stopElapsedTimer = useCallback(() => {
    if (elapsedTimerRef.current !== null) {
      clearInterval(elapsedTimerRef.current)
      elapsedTimerRef.current = null
    }
  }, [])

  /** Tear down recorder + timer state without touching the recorder itself. */
  const resetRecordingState = useCallback(() => {
    stopElapsedTimer()
    recorderRef.current = null
    pendingArmRef.current = null
    stoppingRef.current = false
    setIsRecording(false)
    setIsArming(false)
    setElapsedMs(0)
  }, [stopElapsedTimer])

  /** Abort + discard the partial recording (shared by hidden-tab + manual Stop). */
  const discardRecording = useCallback(
    (copy: string) => {
      // WR-07: once the auto-stop path has begun (stop() awaiting + download in
      // flight), discard MUST be a no-op so it cannot cancel the download or
      // race the recorder teardown. The stoppingRef guard provides that mutual
      // exclusion; the auto-stop branch sets it before awaiting stop().
      if (stoppingRef.current) return
      const recorder = recorderRef.current
      if (!recorder) return
      // Claim the terminal path synchronously and clear recorderRef before any
      // async work so a second discard / a late auto-stop cannot re-enter.
      stoppingRef.current = true
      recorderRef.current = null
      recorder.discard()
      resetRecordingState()
      toastError(copy)
    },
    [resetRecordingState],
  )

  const stopRecording = useCallback(() => {
    discardRecording(DISCARD_COPY)
  }, [discardRecording])

  /**
   * Arm a scripted record run (post-confirm or sub-threshold). CR-01/WR-08:
   * compute the snapshot ONCE here, freeze it via enterReplay(snapshot), and
   * record a pending-arm request. The actual seekTo(0) → recorder.start() →
   * play() is driven from the arm effect below, once replay.totalEvents has
   * settled to the snapshot length — so the record run can never be clobbered
   * by SessionDetails' D-03 auto-seek-to-end (which is suppressed while arming).
   */
  const beginRecording = useCallback(() => {
    if (!canRecord || !mimeType) return
    const panelEl = getPanelElRef.current()
    if (!panelEl) return
    // WR-08: read the snapshot ONCE from the ref so the frozen view and the
    // recorder boundary are computed from the same list.
    const snapshot = getRecordSnapshotRef.current()
    if (snapshot.length < 2) return

    setIsArming(true)
    pendingArmRef.current = { length: snapshot.length, mimeType }
    enterReplay(snapshot)
  }, [canRecord, mimeType, enterReplay])

  const startRecording = useCallback(() => {
    if (!canRecord) return
    const estimate = estimateDurationMs(
      getRecordSnapshotRef.current(),
      replayRef.current.speed,
    )
    if (estimate > CONFIRM_THRESHOLD_MS) {
      setConfirmEstimateMs(estimate)
      setConfirmOpen(true)
      return
    }
    beginRecording()
  }, [canRecord, beginRecording])

  const confirmRecord = useCallback(() => {
    setConfirmOpen(false)
    beginRecording()
  }, [beginRecording])

  const cancelConfirm = useCallback(() => {
    setConfirmOpen(false)
  }, [])

  // Arm effect (CR-01): once enterReplay has applied the frozen snapshot AND
  // useReplay's own reset-to-0 effect has run (so totalEvents reflects the new
  // snapshot), seek to 0, build + start the recorder, and begin playback. This
  // runs AFTER the SessionDetails D-03 effect is suppressed (isArming gates it),
  // so the record run is guaranteed to start at index 0 rather than the end.
  const currentIndex = replay.currentIndex
  const totalEvents = replay.totalEvents
  useEffect(() => {
    const pending = pendingArmRef.current
    if (!pending || isRecording) return
    // Wait until the frozen snapshot is the one we armed against.
    if (totalEvents !== pending.length) return
    const panelEl = getPanelElRef.current()
    if (!panelEl) {
      // Panel vanished between click and arm — abandon cleanly.
      resetRecordingState()
      return
    }
    pendingArmRef.current = null
    const currentReplay = replayRef.current

    currentReplay.seekTo(0)

    const recorder = createReplayRecorder({
      panelEl,
      speed: currentReplay.speed,
      mimeType: pending.mimeType,
      sessionId,
    })
    recorderRef.current = recorder
    stoppingRef.current = false
    recorder.start()

    startedAtRef.current = Date.now()
    setElapsedMs(0)
    setIsArming(false)
    setIsRecording(true)
    elapsedTimerRef.current = setInterval(() => {
      setElapsedMs(Date.now() - startedAtRef.current)
    }, ELAPSED_TICK_MS)

    // Drive playback; the per-frame capture + auto-stop run from the effect that
    // watches currentIndex while recording (below).
    currentReplay.play()
  }, [isArming, isRecording, totalEvents, sessionId, resetRecordingState])

  // Per-frame capture + auto-stop. While recording, every currentIndex change
  // (the scripted play() advancing one event) renders + pushes one mirror-canvas
  // frame; on reaching the last event we stop() → download + success toast.
  useEffect(() => {
    if (!isRecording) return
    // CR-02: capture the recorder handle ONCE; the auto-stop branch uses the
    // stoppingRef guard so stop() fires exactly once even if a trailing
    // currentIndex/totalEvents change re-runs this effect mid-stop.
    const recorder = recorderRef.current
    if (!recorder) return

    let cancelled = false
    void (async () => {
      await recorder.captureFrame()
      if (cancelled) return
      // Auto-stop at the last event (D-06) — guarded so it runs exactly once.
      if (
        totalEvents > 0 &&
        currentIndex >= totalEvents - 1 &&
        !stoppingRef.current
      ) {
        // Claim the terminal path synchronously BEFORE awaiting stop() so a
        // hidden-tab discard (WR-07) or a re-run of this effect (CR-02) cannot
        // re-enter and double-stop.
        stoppingRef.current = true
        recorderRef.current = null
        const filename = await recorder.stop()
        resetRecordingState()
        toastSuccess(`Saved ${filename}`)
      }
    })()

    return () => {
      cancelled = true
    }
  }, [isRecording, currentIndex, totalEvents, resetRecordingState])

  // Abort-on-hidden (D-24): backgrounding the tab freezes rAF while the recorder
  // clock runs, so discard the partial rather than ship a frozen-frame video.
  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'hidden' && isRecordingRef.current) {
        discardRecording(ABORT_COPY)
      }
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [discardRecording])

  // Clean up the elapsed timer on unmount.
  useEffect(() => stopElapsedTimer, [stopElapsedTimer])

  return {
    canRecord,
    isRecording,
    elapsedMs,
    startRecording,
    stopRecording,
    confirmOpen,
    confirmEstimateMs,
    confirmSpeed: speed,
    confirmRecord,
    cancelConfirm,
    isArming,
  }
}

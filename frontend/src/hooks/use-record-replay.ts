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
  /** The frozen replay event list (sorted by seq ascending). */
  events: readonly E[]
  /** The mounted replay controls (seekTo/play + currentIndex/total). */
  replay: UseReplayReturn
  /** Enter replay mode (freezes the snapshot) before recording begins. */
  enterReplay: () => void
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
}

/**
 * Drive a scripted WebM recording over the mounted replay.
 *
 * @param mimeTypeOverride - test seam for `pickMimeType` (null ⇒ Safari path).
 */
export function useRecordReplay<E extends TimedEvent>(
  {
    getPanelEl,
    events,
    replay,
    enterReplay,
    sessionId,
  }: UseRecordReplayOptions<E>,
  mimeTypeOverride?: string | null,
): UseRecordReplayResult {
  const mimeType = mimeTypeOverride !== undefined ? mimeTypeOverride : pickMimeType()
  const canRecord = mimeType !== null

  const [isRecording, setIsRecording] = useState(false)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [confirmEstimateMs, setConfirmEstimateMs] = useState(0)

  const recorderRef = useRef<ReplayRecorder | null>(null)
  const elapsedTimerRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const startedAtRef = useRef<number>(0)
  // Mirror current values for the visibilitychange listener (registered once).
  const isRecordingRef = useRef(false)
  isRecordingRef.current = isRecording

  // Keep live references the scripted flow reads without re-subscribing.
  const replayRef = useRef(replay)
  replayRef.current = replay
  const eventsRef = useRef(events)
  eventsRef.current = events
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
    setIsRecording(false)
    setElapsedMs(0)
  }, [stopElapsedTimer])

  /** Abort + discard the partial recording (shared by hidden-tab + manual Stop). */
  const discardRecording = useCallback(
    (copy: string) => {
      const recorder = recorderRef.current
      if (!recorder) return
      recorder.discard()
      resetRecordingState()
      toastError(copy)
    },
    [resetRecordingState],
  )

  const stopRecording = useCallback(() => {
    discardRecording(DISCARD_COPY)
  }, [discardRecording])

  /** The actual scripted record run (post-confirm or sub-threshold). */
  const beginRecording = useCallback(() => {
    if (!canRecord || !mimeType) return
    const panelEl = getPanelElRef.current()
    if (!panelEl) return
    const currentReplay = replayRef.current
    const evts = eventsRef.current
    if (evts.length < 2) return

    enterReplay()
    currentReplay.seekTo(0)

    const recorder = createReplayRecorder({
      panelEl,
      speed: currentReplay.speed,
      mimeType,
      sessionId,
    })
    recorderRef.current = recorder
    recorder.start()

    startedAtRef.current = Date.now()
    setElapsedMs(0)
    setIsRecording(true)
    elapsedTimerRef.current = setInterval(() => {
      setElapsedMs(Date.now() - startedAtRef.current)
    }, ELAPSED_TICK_MS)

    // Drive playback; the per-frame capture + auto-stop run from the effect that
    // watches currentIndex while recording (below).
    currentReplay.play()
  }, [canRecord, mimeType, enterReplay, sessionId])

  const startRecording = useCallback(() => {
    if (!canRecord) return
    const estimate = estimateDurationMs(eventsRef.current, replayRef.current.speed)
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

  // Per-frame capture + auto-stop. While recording, every currentIndex change
  // (the scripted play() advancing one event) renders + pushes one mirror-canvas
  // frame; on reaching the last event we stop() → download + success toast.
  const currentIndex = replay.currentIndex
  const totalEvents = replay.totalEvents
  useEffect(() => {
    if (!isRecording) return
    const recorder = recorderRef.current
    if (!recorder) return

    let cancelled = false
    void (async () => {
      await recorder.captureFrame()
      if (cancelled) return
      // Auto-stop at the last event (D-06).
      if (totalEvents > 0 && currentIndex >= totalEvents - 1) {
        const filename = await recorder.stop()
        if (cancelled) return
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
  }
}

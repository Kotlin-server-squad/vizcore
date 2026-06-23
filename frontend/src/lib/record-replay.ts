/**
 * Pure WebM recording pipeline (EXPT-02 video tier, D-05..08 / D-23..27).
 *
 * This module is deliberately React-free and mock-testable in jsdom: it wraps
 * the browser MediaRecorder / canvas.captureStream / html2canvas APIs behind a
 * small interface so the React glue (`use-record-replay.ts`) can drive a
 * scripted replay recording and the unit tests can mock every browser API.
 *
 * Pipeline shape (mirrors RESEARCH Pattern 5):
 *   - `pickMimeType` — the D-25 vp9 → vp8 → bare-webm codec cascade; returns
 *     null when none are supported (Safari → the caller disables the option).
 *   - `estimateDurationMs` — the D-26 duration estimate using the SAME
 *     50–2000ms base-gap clamp ÷ speed as useReplay (ADR-017 / RPLY-02).
 *   - `createReplayRecorder` — builds a 2x mirror `<canvas>` (D-27), a
 *     `captureStream(0)` + MediaRecorder, and exposes `captureFrame()` (SVG
 *     fast path vs html2canvas DOM path → `track.requestFrame()`), `start()`,
 *     `stop()` (assemble Blob + download `.webm`), and `discard()` (stop, no
 *     download). The captured region is the mirror canvas — the active panel
 *     ONLY, never the controller's recording indicator (D-08).
 *
 * `captureStream(0)` + `requestFrame()` is used (not a positive fps) so a slow
 * html2canvas frame is HELD, not dropped (RESEARCH Pitfall 3) — the recorder
 * advances exactly one frame per scripted replay step.
 */

import html2canvas from 'html2canvas'
import { findSvgRoot } from './export-svg'
import { buildExportFilename } from './export-filename'

/** D-25 codec cascade, highest-quality first. */
const CODEC_CASCADE = [
  'video/webm;codecs=vp9',
  'video/webm;codecs=vp8',
  'video/webm',
] as const

/** Target bitrate for the recorded stream (2.5 Mbps). */
const VIDEO_BITS_PER_SECOND = 2_500_000

/** The same readable inter-event clamp window as useReplay (ADR-017). */
const MIN_GAP_MS = 50
const MAX_GAP_MS = 2000

/** Capture region scale factor (D-27: 2x resolution). */
const CAPTURE_SCALE = 2

/** A minimal slice of VizEvent needed for the duration estimate. */
interface TimedEvent {
  tsNanos: number
}

/**
 * Pick the first codec the platform's MediaRecorder approves (D-25 cascade).
 *
 * @param isTypeSupported - injectable for tests; defaults to the real
 *   `MediaRecorder.isTypeSupported` when available.
 * @returns the supported mime type, or `null` when none are (Safari) — the
 *   caller MUST then disable the recording option and never build a recorder.
 */
export function pickMimeType(
  isTypeSupported?: (type: string) => boolean
): string | null {
  const check =
    isTypeSupported ??
    (typeof MediaRecorder !== 'undefined' &&
    typeof MediaRecorder.isTypeSupported === 'function'
      ? (t: string) => MediaRecorder.isTypeSupported(t)
      : null)
  if (!check) return null
  for (const codec of CODEC_CASCADE) {
    if (check(codec)) return codec
  }
  return null
}

/**
 * Estimate the recorded video duration in ms (D-26).
 *
 * Sums, over each consecutive event pair, the inter-event gap clamped to
 * [50, 2000] ms then divided by the playback speed — IDENTICAL to the per-tick
 * delay useReplay applies during playback (so the estimate matches the real
 * recording length the MediaRecorder wall-clock produces).
 *
 * @param events - the replay event list (sorted by seq ascending).
 * @param speed - the active playback speed multiplier.
 */
export function estimateDurationMs(events: readonly TimedEvent[], speed: number): number {
  if (events.length < 2) return 0
  let total = 0
  for (let i = 0; i < events.length - 1; i++) {
    const cur = events[i]!
    const next = events[i + 1]!
    const gapMs = (next.tsNanos - cur.tsNanos) / 1_000_000
    const clamped = Math.min(Math.max(gapMs, MIN_GAP_MS), MAX_GAP_MS)
    total += clamped / speed
  }
  return total
}

/** Options for {@link createReplayRecorder}. */
export interface CreateReplayRecorderOptions {
  /** The active visualization panel element to capture (panel ONLY, D-08). */
  panelEl: HTMLElement
  /** The active playback speed (recorded length tracks this via the clock). */
  speed: number
  /** The mime type chosen by {@link pickMimeType} (never null here). */
  mimeType: string
  /** Session id used to build the `{id}-replay-{ts}.webm` filename. */
  sessionId: string
}

/** The recorder handle returned by {@link createReplayRecorder}. */
export interface ReplayRecorder {
  /** Begin capturing into the MediaRecorder. */
  start: () => void
  /** Capture one frame of the panel into the mirror canvas + requestFrame. */
  captureFrame: () => Promise<void>
  /** Stop, assemble the Blob, and download the `.webm`; resolves the filename. */
  stop: () => Promise<string>
  /** Stop WITHOUT downloading (abort/discard path — D-23/D-24). */
  discard: () => void
}

/** Trigger an in-browser download for the assembled WebM blob. */
function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  } finally {
    URL.revokeObjectURL(url)
  }
}

/**
 * Build a scripted replay recorder over the given panel.
 *
 * The returned handle owns a 2x mirror canvas, a `captureStream(0)` track, and
 * a MediaRecorder; `captureFrame()` renders the current panel state into the
 * mirror canvas and pushes exactly one frame to the recorder.
 */
export function createReplayRecorder({
  panelEl,
  speed: _speed,
  mimeType,
  sessionId,
}: CreateReplayRecorderOptions): ReplayRecorder {
  const canvas = document.createElement('canvas')
  canvas.width = panelEl.clientWidth * CAPTURE_SCALE
  canvas.height = panelEl.clientHeight * CAPTURE_SCALE
  const ctx = canvas.getContext('2d')

  const stream = (
    canvas as unknown as { captureStream: (fps?: number) => MediaStream }
  ).captureStream(0)
  const [track] = stream.getVideoTracks() as unknown as Array<{
    requestFrame?: () => void
  }>

  const recorder = new MediaRecorder(stream, {
    mimeType,
    videoBitsPerSecond: VIDEO_BITS_PER_SECOND,
  })

  const chunks: Blob[] = []
  recorder.ondataavailable = (e: BlobEvent) => {
    if (e.data && e.data.size > 0) chunks.push(e.data)
  }

  /** Render the panel into the mirror canvas via the SVG fast path or html2canvas. */
  async function renderToCanvas(): Promise<void> {
    if (!ctx) return
    const svgRoot = findSvgRoot(panelEl)
    if (svgRoot) {
      // SVG fast path: serialize the live SVG into an Image, then draw it.
      const serialized = new XMLSerializer().serializeToString(svgRoot)
      const svgBlob = new Blob([serialized], { type: 'image/svg+xml' })
      const url = URL.createObjectURL(svgBlob)
      try {
        await new Promise<void>((resolve, reject) => {
          const img = new Image()
          img.onload = () => {
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
            resolve()
          }
          img.onerror = () => reject(new Error('Failed to rasterize SVG frame'))
          img.src = url
        })
      } finally {
        URL.revokeObjectURL(url)
      }
      return
    }
    // DOM path: html2canvas at 2x to match the mirror-canvas resolution.
    const rendered = await html2canvas(panelEl, {
      backgroundColor: '#18181b',
      scale: CAPTURE_SCALE,
      useCORS: true,
      logging: false,
    })
    ctx.drawImage(rendered, 0, 0, canvas.width, canvas.height)
  }

  async function captureFrame(): Promise<void> {
    await renderToCanvas()
    // captureStream(0) holds until requestFrame(); push exactly one frame so a
    // slow html2canvas pass is never dropped (RESEARCH Pitfall 3).
    track?.requestFrame?.()
  }

  function start(): void {
    recorder.start()
  }

  function stop(): Promise<string> {
    return new Promise<string>((resolve) => {
      const filename = buildExportFilename(sessionId, 'replay', 'webm')
      recorder.onstop = () => {
        const blob = new Blob(chunks, { type: mimeType })
        downloadBlob(blob, filename)
        resolve(filename)
      }
      if (recorder.state !== 'inactive') {
        recorder.stop()
      } else {
        recorder.onstop?.(new Event('stop'))
      }
    })
  }

  function discard(): void {
    // Stop the recorder but never assemble/download — drop the partial.
    recorder.onstop = null
    if (recorder.state !== 'inactive') {
      recorder.stop()
    }
    chunks.length = 0
  }

  return { start, captureFrame, stop, discard }
}

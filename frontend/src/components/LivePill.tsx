import { Chip } from '@heroui/react'

/**
 * Streaming indicator for the workspace header strip (Phase 08.1, sketch 001-C).
 *
 * It says only what the browser knows (#145):
 * - stream on and connected      → accent LIVE pill;
 * - stream on, not connected     → amber CONNECTING pill;
 * - stream off                   → neutral NOT LIVE pill.
 *
 * The old "~150ms poll" sub-label is gone: 150 ms is the DebugProbes sampling
 * interval of one source, and was wrong for push-instrumented sessions.
 * DEMO/ATTACHED/INSTRUMENTED is owned by the rung badge (see
 * `fidelity-rung.ts`), so this pill speaks only about streaming.
 *
 * Literal Tailwind classes only (IN-12) — no runtime class construction.
 */
export function LivePill({
  streamEnabled,
  connected = true,
}: {
  streamEnabled: boolean
  /** Whether the SSE stream is currently open. Defaults to true for callers that do not track it. */
  connected?: boolean
}) {
  if (streamEnabled && connected) {
    return (
      <Chip
        size="sm"
        className="bg-success/10 text-success border-success/20"
        startContent={<span className="inline-block h-2 w-2 rounded-full bg-success" />}
      >
        LIVE
      </Chip>
    )
  }

  if (streamEnabled) {
    return (
      <Chip size="sm" className="bg-warning/10 text-warning">
        CONNECTING
      </Chip>
    )
  }

  return (
    <Chip size="sm" className="bg-default-100 text-default-500">
      NOT LIVE
    </Chip>
  )
}

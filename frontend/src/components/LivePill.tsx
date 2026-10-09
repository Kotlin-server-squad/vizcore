import { Chip } from '@heroui/react'

/**
 * Streaming indicator for the docked panel header (Phase 08.1, sketch 001-C).
 *
 * Driven solely by `streamEnabled`:
 * - `streamEnabled === true`  → accent LIVE pill + "~150ms poll" sub-label.
 * - `streamEnabled === false` → neutral NOT LIVE pill, no sub-label.
 *
 * The off state used to read "DEMO", which conflated two independent things:
 * whether the SSE stream is on, and whether the session is a canned scenario.
 * A real attached app showed "DEMO" simply because the user had not clicked
 * "Enable Live Stream". DEMO/ATTACHED/INSTRUMENTED is now owned by the rung
 * badge (see `fidelity-rung.ts`), so this pill speaks only about streaming.
 *
 * Literal Tailwind classes only (IN-12) — no runtime class construction.
 */
export function LivePill({ streamEnabled }: { streamEnabled: boolean }) {
  if (streamEnabled) {
    return (
      <div className="flex items-center gap-2">
        <Chip
          size="sm"
          className="bg-success/10 text-success border-success/20"
          startContent={<span className="inline-block h-2 w-2 rounded-full bg-success" />}
        >
          LIVE
        </Chip>
        <span className="text-xs text-default-500">~150ms poll</span>
      </div>
    )
  }

  return (
    <Chip size="sm" className="bg-default-100 text-default-500">
      NOT LIVE
    </Chip>
  )
}

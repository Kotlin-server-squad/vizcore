import { Chip } from '@heroui/react'

/**
 * LIVE / DEMO indicator pill for the docked panel header (Phase 08.1, sketch 001-C).
 *
 * Driven solely by the existing `streamEnabled` live flag (UI-SPEC line 126):
 * - `streamEnabled === true`  → accent LIVE pill + "~150ms poll" sub-label.
 * - `streamEnabled === false` → neutral DEMO pill, no sub-label.
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
      DEMO
    </Chip>
  )
}

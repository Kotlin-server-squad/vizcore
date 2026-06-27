import { Chip } from '@heroui/react'
import { Link } from '@tanstack/react-router'
import type { SessionInfo } from '@/types/api'
import { LivePill } from '@/components/LivePill'

/**
 * One badged session row in the sessions-sidebar-as-home (Phase 08.5, Surface 003,
 * UI-SPEC line 143). Every row carries an unmistakable LIVE vs DEMO badge:
 * - live → the reused {@link LivePill} (LIVE pill + "~150ms poll" sub-label).
 * - demo → a neutral DEMO {@link Chip}.
 *
 * The sketch's `{host}` meta is unavailable in `SessionInfo` (only `coroutineCount`
 * is wire-present), so the meta degrades gracefully to `{N} active` from the count
 * (PD-10, Pitfall 3). Literal Tailwind only (IN-12) — selected/hover styling via
 * discrete literal-class branches, no `cn()`.
 */
export function SessionRow({
  session,
  kind,
  selected,
}: {
  session: SessionInfo
  kind: 'live' | 'demo'
  selected: boolean
}) {
  const label = session.sessionId

  return (
    <Link
      to="/sessions/$sessionId"
      params={{ sessionId: session.sessionId }}
      className={
        selected
          ? 'flex items-center justify-between gap-3 rounded-lg p-3 bg-primary/5 ring-2 ring-primary'
          : 'flex items-center justify-between gap-3 rounded-lg p-3 hover:bg-default-100'
      }
    >
      <div className="flex min-w-0 flex-col gap-1">
        <span className="truncate text-sm font-semibold">{label}</span>
        <span className="font-mono text-xs text-default-500">{session.coroutineCount} active</span>
      </div>
      {kind === 'live' ? (
        <LivePill streamEnabled />
      ) : (
        <Chip size="sm" className="bg-default-100 text-default-500">
          DEMO
        </Chip>
      )}
    </Link>
  )
}

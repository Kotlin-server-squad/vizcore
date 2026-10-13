import { Chip } from '@heroui/react'
import { Link } from '@tanstack/react-router'
import type { SessionInfo } from '@/types/api'
import { sessionDisplayName } from '@/lib/session-kind'

/**
 * One badged session row in the sessions-sidebar-as-home (Phase 08.5, Surface 003,
 * UI-SPEC line 143). Every row says whose code it is:
 * - app  → an APP badge: the user's own application;
 * - demo → a muted DEMO badge: a scenario that runs inside vizcore.
 *
 * Only what the backend reports is claimed (#145). `SessionInfo` carries no
 * liveness, so the row does not say LIVE (it used to, for every non-demo row,
 * hours after the app had gone); and `coroutineCount` counts every coroutine
 * the session has seen, finished ones included, so it reads "N coroutines",
 * not "N active". Literal Tailwind only (IN-12).
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
  const count = session.coroutineCount

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
        <span className="truncate text-sm font-semibold">{sessionDisplayName(session.sessionId)}</span>
        <span className="truncate font-mono text-xs text-default-500" title={session.sessionId}>
          {count === 1 ? '1 coroutine' : `${count} coroutines`} · {session.sessionId}
        </span>
      </div>
      {kind === 'live' ? (
        <Chip size="sm" variant="bordered" className="border-primary/40 text-primary">
          APP
        </Chip>
      ) : (
        <Chip size="sm" className="bg-default-100 text-default-500">
          DEMO
        </Chip>
      )}
    </Link>
  )
}

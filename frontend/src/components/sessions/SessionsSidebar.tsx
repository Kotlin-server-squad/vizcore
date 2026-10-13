import { Button, Card, CardBody, Spinner } from '@heroui/react'
import { FiPlus } from 'react-icons/fi'
import type { SessionInfo } from '@/types/api'
import { ErrorState } from '../ErrorState'
import { useSessions } from '@/hooks/use-sessions'
import { deriveSessionKind } from '@/lib/session-kind'
import { SessionRow } from './SessionRow'

/**
 * The badged sessions-sidebar-as-home (Phase 08.5, Surface 003 winner C).
 *
 * One grouped, badged list: a "Your apps" group then a "Demo scenarios" group,
 * each row carrying an unmistakable APP/DEMO badge (PD-10, #145). A primary `+ Connect`
 * action opens the 3-step connect wizard (wired by the route via `onConnect`).
 * When the whole list is empty the "No app connected" empty state folds INLINE
 * into the list region (not a standalone screen, UI-SPEC line 145) with both CTAs.
 *
 * Reuses the shipped `useSessions` hook. Literal Tailwind only (IN-12).
 */
export function SessionsSidebar({
  onConnect,
  selectedSessionId,
  className = 'w-[320px] shrink-0',
  onNewDemo,
  onCompare,
}: {
  onConnect: () => void
  selectedSessionId?: string
  /** Width/placement is the caller's decision — sidebar at /sessions, full-width at /. */
  className?: string
  /** Opens the re-hosted scenario picker (D-2). Omitted in the sidebar placement. */
  onNewDemo?: () => void
  /** Opens the re-hosted comparison overlay (D-3). Omitted in the sidebar placement. */
  onCompare?: () => void
}) {
  const { data: sessions, isLoading, isPending, isError, error, refetch } = useSessions()
  // `isPending`, not just `isLoading`: a retry TanStack Query has paused (tab
  // in the background, browser offline) is pending but not fetching, and must
  // read as "still loading" — not fall through to "No app connected" (#139).
  const loading = isLoading || isPending === true

  const live: SessionInfo[] = []
  const demo: SessionInfo[] = []
  for (const session of sessions ?? []) {
    if (deriveSessionKind(session) === 'demo') {
      demo.push(session)
    } else {
      live.push(session)
    }
  }

  // A failed load is NOT an empty list (#139): "No app connected" on a 429 or
  // a dead server would send the user off to debug their app for nothing.
  // Data from an earlier successful load keeps rendering through a failed
  // background refresh.
  const failed = isError && !sessions
  const isEmpty = !loading && !failed && live.length === 0 && demo.length === 0

  return (
    <Card className={className}>
      <CardBody className="gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold">Sessions</h2>
          <div className="flex items-center gap-2">
            {onCompare && (
              <Button size="sm" variant="light" onPress={onCompare}>
                Compare
              </Button>
            )}
            {onNewDemo && (
              <Button size="sm" variant="flat" onPress={onNewDemo}>
                New demo session
              </Button>
            )}
            <Button
              color="primary"
              size="sm"
              startContent={<FiPlus />}
              onPress={onConnect}
            >
              Connect
            </Button>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Spinner size="sm" />
          </div>
        ) : failed ? (
          <ErrorState error={error} subject="The sessions list" onRetry={() => void refetch()} bare />
        ) : isEmpty ? (
          <div className="flex flex-col items-center gap-3 py-12 text-center">
            <h3 className="text-sm font-semibold">No app connected</h3>
            <p className="text-sm text-default-500">
              Add <code className="font-mono text-xs">vizcore-client</code> to your app and call{' '}
              <code className="font-mono text-xs">VizcoreClient.start()</code>. Its coroutines will
              stream in here live.
            </p>
            <Button color="primary" size="sm" onPress={onConnect}>
              Connect your app
            </Button>
            {/* Opens the demo picker in place. It used to navigate to
                /scenarios, which redirects straight back here (#139). */}
            {onNewDemo && (
              <Button variant="ghost" size="sm" onPress={onNewDemo}>
                Run a demo scenario instead
              </Button>
            )}
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {live.length > 0 && (
              <div className="flex flex-col gap-2">
                <span className="text-xs uppercase tracking-wide text-default-400">Your apps</span>
                {live.map(session => (
                  <SessionRow
                    key={session.sessionId}
                    session={session}
                    kind="live"
                    selected={session.sessionId === selectedSessionId}
                  />
                ))}
              </div>
            )}
            {demo.length > 0 && (
              <div className="flex flex-col gap-2">
                <span className="text-xs uppercase tracking-wide text-default-400">
                  Demo scenarios
                </span>
                {demo.map(session => (
                  <SessionRow
                    key={session.sessionId}
                    session={session}
                    kind="demo"
                    selected={session.sessionId === selectedSessionId}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </CardBody>
    </Card>
  )
}

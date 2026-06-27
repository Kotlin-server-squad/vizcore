import { Button, Card, CardBody, Spinner } from '@heroui/react'
import { FiPlus } from 'react-icons/fi'
import { useNavigate } from '@tanstack/react-router'
import type { SessionInfo } from '@/types/api'
import { useSessions } from '@/hooks/use-sessions'
import { deriveSessionKind } from '@/lib/session-kind'
import { SessionRow } from './SessionRow'

/**
 * The badged sessions-sidebar-as-home (Phase 08.5, Surface 003 winner C).
 *
 * One grouped, badged list: a "Live apps" group then a "Demo scenarios" group,
 * each row carrying an unmistakable LIVE/DEMO badge (PD-10). A primary `+ Connect`
 * action opens the 3-step connect wizard (wired by the route via `onConnect`).
 * When the whole list is empty the "No app connected" empty state folds INLINE
 * into the list region (not a standalone screen, UI-SPEC line 145) with both CTAs.
 *
 * Reuses the shipped `useSessions` hook + `LivePill`. Literal Tailwind only (IN-12).
 */
export function SessionsSidebar({
  onConnect,
  selectedSessionId,
}: {
  onConnect: () => void
  selectedSessionId?: string
}) {
  const { data: sessions, isLoading } = useSessions()
  const navigate = useNavigate()

  const live: SessionInfo[] = []
  const demo: SessionInfo[] = []
  for (const session of sessions ?? []) {
    if (deriveSessionKind(session) === 'demo') {
      demo.push(session)
    } else {
      live.push(session)
    }
  }

  const isEmpty = !isLoading && live.length === 0 && demo.length === 0

  return (
    <Card className="w-[320px] shrink-0">
      <CardBody className="gap-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold">Sessions</h2>
          <Button
            color="primary"
            size="sm"
            startContent={<FiPlus />}
            onPress={onConnect}
          >
            Connect
          </Button>
        </div>

        {isLoading ? (
          <div className="flex items-center justify-center py-12">
            <Spinner size="sm" />
          </div>
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
            <Button
              variant="ghost"
              size="sm"
              onPress={() => navigate({ to: '/scenarios' })}
            >
              Run a demo scenario instead
            </Button>
          </div>
        ) : (
          <div className="flex flex-col gap-4">
            {live.length > 0 && (
              <div className="flex flex-col gap-2">
                <span className="text-xs uppercase tracking-wide text-default-400">Live apps</span>
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

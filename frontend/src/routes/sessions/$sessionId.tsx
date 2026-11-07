import { useCallback } from 'react'
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { SessionWorkspace } from '@/components/SessionWorkspace'
import { LookProvider } from '@/looks/look-context'
import { LookExperimentLayer } from '@/looks/experiment/LookExperimentLayer'
import { useLookExperimentEntry } from '@/looks/experiment/use-experiment-entry'

interface SessionSearchParams {
  scenarioId?: string
  scenarioName?: string
  /** Open with the live stream on (set by the Connect wizard, #126). */
  live?: boolean
  /** A trial look for this page (PR L0): `a`, `b`, `c` or `classic`. Read once, then removed. */
  look?: string
  /** Opened by Run again: start the run here (S-13). Read once, then removed. */
  run?: boolean
}

export const Route = createFileRoute('/sessions/$sessionId')({
  component: SessionDetailPage,
  validateSearch: (search: Record<string, unknown>): SessionSearchParams => {
    return {
      scenarioId: search.scenarioId as string | undefined,
      scenarioName: search.scenarioName as string | undefined,
      live: search.live === true || search.live === 'true' ? true : undefined,
      run: search.run === true || search.run === 'true' ? true : undefined,
      // Present but not text (the search parser reads `?look=1`, `null` or `["a"]` as
      // JSON): unknown, so Classic, never the remembered look.
      look:
        search.look === undefined ? undefined : typeof search.look === 'string' ? search.look : '',
    }
  },
})

function SessionDetailPage() {
  const { sessionId } = Route.useParams()
  const search = Route.useSearch()
  const navigate = useNavigate({ from: Route.fullPath })
  // Once the look from a link is applied and remembered, the address drops it,
  // so a later choice from the menu is not overruled on reload.
  const dropLookFromAddress = useCallback(
    () => void navigate({ search: prev => ({ ...prev, look: undefined }), replace: true }),
    [navigate]
  )
  // Run again's request to start the run: dropped once read, so a reload never starts a second one.
  const dropRunFromAddress = useCallback(
    () => void navigate({ search: prev => ({ ...prev, run: undefined }), replace: true }),
    [navigate]
  )
  // The look experiment (off unless the server runs it): this page only, never the shared view.
  const experiment = useLookExperimentEntry(search.look, sessionId)

  return (
    <LookProvider
      link={search.look}
      onLinkRead={dropLookFromAddress}
      assigned={experiment.assigned}
    >
      <LookExperimentLayer config={experiment.config} sessionId={sessionId}>
        <Layout>
          {/* Full width, full height under the navbar (64 px and its 1 px border,
          S-1): the main view and the dock share the window instead of
          scrolling a long page. */}
          <div
            data-look-part="page"
            className="flex h-[calc(100dvh-65px)] flex-col px-4 py-3 sm:px-6"
          >
            <SessionWorkspace
              // A new session is a new page: no selection, dock tab or stream state
              // carries over from the one before (Reset navigates between them).
              key={sessionId}
              sessionId={sessionId}
              scenarioId={search.scenarioId}
              scenarioName={search.scenarioName}
              startLive={search.live}
              autoRun={search.run}
              onAutoRunConsumed={dropRunFromAddress}
            />
          </div>
        </Layout>
      </LookExperimentLayer>
    </LookProvider>
  )
}

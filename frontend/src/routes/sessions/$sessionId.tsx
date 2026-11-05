import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { SessionWorkspace } from '@/components/SessionWorkspace'

interface SessionSearchParams {
  scenarioId?: string
  scenarioName?: string
  /** Open with the live stream on (set by the Connect wizard, #126). */
  live?: boolean
}

export const Route = createFileRoute('/sessions/$sessionId')({
  component: SessionDetailPage,
  validateSearch: (search: Record<string, unknown>): SessionSearchParams => {
    return {
      scenarioId: search.scenarioId as string | undefined,
      scenarioName: search.scenarioName as string | undefined,
      live: search.live === true || search.live === 'true' ? true : undefined,
    }
  },
})

function SessionDetailPage() {
  const { sessionId } = Route.useParams()
  const search = Route.useSearch()

  return (
    <Layout>
      {/* Full width, full height under the navbar (64 px and its 1 px border,
          S-1): the main view and the dock share the window instead of
          scrolling a long page. */}
      <div className="flex h-[calc(100dvh-65px)] flex-col px-4 py-3 sm:px-6">
        <SessionWorkspace
          // A new session is a new page: no selection, dock tab or stream state
          // carries over from the one before (Reset navigates between them).
          key={sessionId}
          sessionId={sessionId}
          scenarioId={search.scenarioId}
          scenarioName={search.scenarioName}
          startLive={search.live}
        />
      </div>
    </Layout>
  )
}

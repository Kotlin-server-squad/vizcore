import { createFileRoute } from '@tanstack/react-router'
import { Layout } from '@/components/Layout'
import { SessionWorkspace } from '@/components/SessionWorkspace'

interface SessionSearchParams {
  scenarioId?: string
  scenarioName?: string
}

export const Route = createFileRoute('/sessions/$sessionId')({
  component: SessionDetailPage,
  validateSearch: (search: Record<string, unknown>): SessionSearchParams => {
    return {
      scenarioId: search.scenarioId as string | undefined,
      scenarioName: search.scenarioName as string | undefined,
    }
  },
})

function SessionDetailPage() {
  const { sessionId } = Route.useParams()
  const search = Route.useSearch()

  return (
    <Layout>
      <div className="container-custom py-8">
        <SessionWorkspace 
          sessionId={sessionId} 
          scenarioId={search.scenarioId}
          scenarioName={search.scenarioName}
        />
      </div>
    </Layout>
  )
}


import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Spinner } from '@heroui/react'
import { Layout } from '@/components/Layout'
import { apiClient } from '@/lib/api-client'
import { LookResultsView } from '@/looks/experiment/LookResultsView'

export const Route = createFileRoute('/experiments/looks')({
  component: LookExperimentResultsPage,
})

/** The look experiment's results (docs/guides/look-experiment.md). Not in the nav; Classic, like every page but the session page. */
export function LookExperimentResultsPage() {
  const results = useQuery({
    queryKey: ['look-experiment-results'],
    queryFn: () => apiClient.getLookExperimentResults(),
    refetchInterval: 60_000,
  })
  return (
    <Layout>
      <div className="container-custom flex flex-col gap-4 py-8">
        <div>
          <h1 className="text-2xl font-semibold">Look experiment</h1>
          <p className="text-sm text-default-500">
            Which look of the session page works best: anonymous counts and ratings from the people who use this
            server.
          </p>
        </div>
        {results.isPending && <Spinner aria-label="Loading the results" />}
        {results.isError && (
          <p role="alert" className="text-danger">
            The results could not be loaded: {results.error.message}
          </p>
        )}
        {results.data && <LookResultsView results={results.data} />}
      </div>
    </Layout>
  )
}

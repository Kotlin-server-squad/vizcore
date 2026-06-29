import { useEffect, useRef } from 'react'
import { createFileRoute, Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, CardBody, CardHeader, Spinner } from '@heroui/react'
import { FiPlay, FiLayers, FiActivity } from 'react-icons/fi'
import { Layout } from '@/components/Layout'
import { useSessions } from '@/hooks/use-sessions'
import { apiClient } from '@/lib/api-client'

/** Poll resolve every 300ms so the deep-link auto-resolves the instant the
 *  IDE-launched app's session binds to the correlation token (D-08/D-09).
 *  Mirrors ConnectWizard's POLL_INTERVAL_MS verbatim. */
const POLL_INTERVAL_MS = 300

/** SPA-root search params. The optional `?correlation=` deep-link param is the
 *  IntelliJ JCEF (or system-browser fallback) auto-connect entry (IDE-03). */
export interface HomeSearch {
  correlation?: string
}

/**
 * Validates and normalizes the `?correlation=` deep-link param. Blank / non-string
 * values collapse to `undefined` (the CMPR-02 idiom) so a malformed
 * `?correlation=` URL never arms the poll — it falls through to the normal home
 * page instead of crashing (T-13-06).
 */
export function validateSearch(search: Record<string, unknown>): HomeSearch {
  const correlation =
    typeof search.correlation === 'string' && search.correlation.trim().length > 0
      ? search.correlation
      : undefined
  return { correlation }
}

export const Route = createFileRoute('/')({
  validateSearch,
  component: HomePage,
})

export function HomePage() {
  // `strict: false` lets the same component mount under a standalone test route
  // without binding to the generated file-route instance (CMPR-02 test idiom).
  const { correlation } = useSearch({ strict: false }) as HomeSearch

  // When opened with `?correlation=` (IDE deep-link) we resolve+auto-navigate to
  // the live session; otherwise the normal home page renders unchanged.
  if (correlation) {
    return <CorrelationDeepLink correlation={correlation} />
  }

  return <Home />
}

/**
 * The `?correlation=` deep-link state (IDE-03 FE half). Reuses the Phase 9
 * resolve-then-navigate logic verbatim from `ConnectWizard.tsx`: a token-scoped
 * `useQuery` poll on `apiClient.resolveCorrelation` (returns `{ sessionId }` once
 * the IDE-launched app's session binds to the token, `null` on 404 = keep
 * polling) plus a one-shot `useEffect` that navigates to the resolved live
 * session exactly once. No backend change, no new endpoint (D-08).
 *
 * The correlation token is non-secret create-path metadata (CORR-01 / D-11) and
 * is never rendered as markup. A "View sessions" fallback link keeps the
 * deep-link state from being a dead-end while resolve is still pending.
 */
function CorrelationDeepLink({ correlation }: { correlation: string }) {
  const navigate = useNavigate()
  const resolvedRef = useRef(false)

  const { data } = useQuery({
    queryKey: ['resolve-correlation', correlation],
    queryFn: () => apiClient.resolveCorrelation(correlation),
    enabled: !!correlation,
    refetchInterval: POLL_INTERVAL_MS,
  })

  // Auto-navigate to the live session the instant resolve returns its id,
  // exactly once (the one-shot guard prevents a double-navigate while the poll
  // keeps firing). Intentionally keyed to the resolve result: navigate is stable.
  useEffect(() => {
    if (resolvedRef.current) return
    if (data?.sessionId) {
      resolvedRef.current = true
      navigate({ to: '/sessions/$sessionId', params: { sessionId: data.sessionId } })
    }
  }, [data])

  return (
    <Layout>
      <div className="container-custom flex flex-col items-center justify-center py-24 text-center">
        <Spinner size="lg" />
        <h1 className="mt-6 text-2xl font-semibold">Connecting to your app…</h1>
        <p className="mt-2 max-w-md text-default-500">
          Waiting for the launched app to report its live session.
        </p>
        <div className="mt-8">
          <Link to="/sessions">
            <Button variant="flat" size="sm" startContent={<FiLayers />}>
              View sessions
            </Button>
          </Link>
        </div>
      </div>
    </Layout>
  )
}

/** The default SPA home page (unchanged from before the deep-link branch). */
function Home() {
  const { data: sessions, isLoading } = useSessions()

  return (
    <Layout>
      <div className="container-custom py-8">
        {/* Hero Section */}
        <div className="mb-12 text-center">
          <h1 className="mb-4 text-5xl font-bold">Coroutine Visualizer</h1>
          <p className="mx-auto max-w-2xl text-xl text-default-600">
            Real-time visualization of Kotlin coroutine lifecycle, hierarchy, and execution flow
          </p>
          <div className="mt-8 flex items-center justify-center gap-4">
            <Link to="/sessions">
              <Button color="primary" size="lg" startContent={<FiLayers />}>
                View Sessions
              </Button>
            </Link>
            <Link to="/scenarios">
              <Button color="secondary" size="lg" variant="flat" startContent={<FiPlay />}>
                Run Scenarios
              </Button>
            </Link>
          </div>
        </div>

        {/* Features */}
        <div className="mb-12 grid gap-6 md:grid-cols-3">
          <Card>
            <CardHeader>
              <FiActivity className="mr-2 h-6 w-6" />
              <h3 className="text-lg font-semibold">Real-time Tracking</h3>
            </CardHeader>
            <CardBody>
              <p className="text-default-600">
                Monitor coroutine lifecycle events as they happen with live SSE streaming
              </p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader>
              <FiLayers className="mr-2 h-6 w-6" />
              <h3 className="text-lg font-semibold">Hierarchy Visualization</h3>
            </CardHeader>
            <CardBody>
              <p className="text-default-600">
                See parent-child relationships and structured concurrency in action
              </p>
            </CardBody>
          </Card>

          <Card>
            <CardHeader>
              <FiPlay className="mr-2 h-6 w-6" />
              <h3 className="text-lg font-semibold">Test Scenarios</h3>
            </CardHeader>
            <CardBody>
              <p className="text-default-600">
                Run pre-built scenarios to explore different coroutine patterns
              </p>
            </CardBody>
          </Card>
        </div>

        {/* Recent Sessions */}
        <div>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-2xl font-bold">Recent Sessions</h2>
            <Link to="/sessions">
              <Button variant="light" size="sm">
                View All
              </Button>
            </Link>
          </div>

          {isLoading ? (
            <div className="text-center text-default-400">Loading sessions...</div>
          ) : !sessions || sessions.length === 0 ? (
            <Card>
              <CardBody>
                <p className="text-center text-default-500">
                  No sessions yet. Create one or run a scenario to get started!
                </p>
              </CardBody>
            </Card>
          ) : (
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
              {sessions.slice(0, 6).map(session => (
                <Link
                  key={session.sessionId}
                  to="/sessions/$sessionId"
                  params={{ sessionId: session.sessionId }}
                >
                  <Card isPressable isHoverable>
                    <CardBody>
                      <div className="mb-2 font-mono text-sm text-default-500">
                        {session.sessionId}
                      </div>
                      <div className="text-lg font-semibold">
                        {session.coroutineCount} coroutines
                      </div>
                    </CardBody>
                  </Card>
                </Link>
              ))}
            </div>
          )}
        </div>
      </div>
    </Layout>
  )
}

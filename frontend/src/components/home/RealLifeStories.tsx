import { Card, CardBody, Skeleton } from '@heroui/react'
import { Link } from '@tanstack/react-router'
import { useScenarios } from '@/hooks/use-scenarios'
import { groupDemos } from '@/lib/demo-groups'
import { ErrorState } from '../ErrorState'
import { StartableDemoCard, type DemoStart } from '../gallery/StartableDemoCard'

const GRID = 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3'

/**
 * The Real life demos on the front door: the Gallery's own cards, with Run and
 * the second ending's button, started by the page's one demo start. Nothing is
 * shown for a server that lists no Real life demo.
 */
export function RealLifeStories({ demoStart }: { demoStart: DemoStart }) {
  const { data, isLoading, isPending, error, refetch } = useScenarios()
  // Pending without loading is a request waiting for the network (offline): still no list.
  const waiting = isLoading || (isPending === true && !data && !error)
  const failed = !waiting && error != null && !data

  const group = groupDemos(data?.scenarios ?? []).find((g) => g.demos.some((d) => d.category === 'realistic'))
  const stories = group?.demos.filter((d) => d.category === 'realistic') ?? []
  if (!waiting && !failed && stories.length === 0) return null

  return (
    <section aria-labelledby="home-stories-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="home-stories-title" className="text-xl font-semibold">
          {waiting || failed || stories.length === 3 ? 'Three real-life stories' : 'Real-life stories'}
        </h2>
        <Link
          to="/gallery"
          className="rounded-small text-sm font-medium text-primary underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          All examples in the Gallery
        </Link>
      </div>
      {waiting ? (
        <div className={GRID}>
          {Array.from({ length: 3 }, (_, i) => (
            <Card key={i} data-testid="demo-card-skeleton">
              <CardBody className="flex flex-col gap-3">
                <Skeleton className="h-5 w-2/3 rounded-lg" />
                <Skeleton className="h-12 w-full rounded-lg" />
                <Skeleton className="h-9 w-full rounded-lg" />
              </CardBody>
            </Card>
          ))}
        </div>
      ) : failed ? (
        <ErrorState error={error} subject="The demo list" onRetry={() => void refetch()} />
      ) : (
        <div className={GRID}>
          {stories.map((scenario) => (
            <StartableDemoCard key={scenario.id} scenario={scenario} groupLabel={group!.label} demoStart={demoStart} />
          ))}
        </div>
      )}
    </section>
  )
}

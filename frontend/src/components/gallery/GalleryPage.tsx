import { useEffect, useMemo } from 'react'
import { Card, CardBody, Chip, Skeleton, Tab, Tabs } from '@heroui/react'
import { FiAlertTriangle } from 'react-icons/fi'
import { useScenarios } from '@/hooks/use-scenarios'
import { useStartDemo } from '@/hooks/use-start-demo'
import { groupDemos } from '@/lib/demo-groups'
import { GUIDES } from '@/content/gallery/guides'
import { MISTAKES } from '@/content/gallery/mistakes'
import { DemoStartAlert } from '../demos/DemoStartAlert'
import { ErrorState } from '../ErrorState'
import { DemoCard } from './DemoCard'
import { MistakeCard } from './MistakeCard'

/** The tab key of the Common-mistakes tab, as kept in `?group=`. */
export const MISTAKES_TAB = 'mistakes'

const GRID = 'grid gap-4 sm:grid-cols-2 lg:grid-cols-3'

interface GalleryPageProps {
  /** The tab from `?group=`; unknown or missing shows the first group. */
  group?: string
  onGroupChange: (group: string) => void
}

/**
 * The Gallery (G-1): every demo by topic, as the server lists them, plus the
 * common mistakes. Run opens the same session view as the "New demo session"
 * dialog.
 */
export function GalleryPage({ group, onGroupChange }: GalleryPageProps) {
  const { data, isLoading, error, refetch } = useScenarios()
  const { start, preparing, startError } = useStartDemo()
  const groups = useMemo(() => groupDemos(data?.scenarios ?? []), [data?.scenarios])

  const selected =
    group === MISTAKES_TAB || groups.some((g) => g.key === group) ? group! : (groups[0]?.key ?? MISTAKES_TAB)

  // A link such as /gallery?group=mistakes#LEAKED_COROUTINE: the card renders
  // after the page does, so the browser's own jump to the anchor misses it.
  // The cards exist only once the list has loaded, hence `showsTabs`.
  const showsTabs = !isLoading && !(error && !data)
  useEffect(() => {
    if (!showsTabs || selected !== MISTAKES_TAB || !window.location.hash) return
    document.getElementById(decodeURIComponent(window.location.hash.slice(1)))?.scrollIntoView({ block: 'start' })
  }, [selected, showsTabs])

  return (
    <div className="container-custom py-8">
      <div className="mb-6">
        <h1 className="mb-2 text-3xl font-bold">Example Gallery</h1>
        <p className="max-w-2xl text-default-600">
          Real-life coroutine examples, grouped by topic. Each one runs on the server, step by step, so you can watch
          it in the session view.
        </p>
      </div>

      {startError && (
        <div className="mb-4">
          <DemoStartAlert startError={startError} />
        </div>
      )}

      {isLoading ? (
        <div className={GRID}>
          {Array.from({ length: 6 }, (_, i) => (
            <Card key={i} data-testid="demo-card-skeleton">
              <CardBody className="flex flex-col gap-3">
                <Skeleton className="h-5 w-2/3 rounded-lg" />
                <Skeleton className="h-12 w-full rounded-lg" />
                <Skeleton className="h-9 w-full rounded-lg" />
              </CardBody>
            </Card>
          ))}
        </div>
      ) : error && !data ? (
        <ErrorState error={error} subject="The demo list" onRetry={() => void refetch()} />
      ) : (
        <Tabs
          aria-label="Gallery topics"
          selectedKey={selected}
          onSelectionChange={(key) => onGroupChange(String(key))}
          color="primary"
          variant="bordered"
          classNames={{ tabList: 'mb-4 flex-wrap', tab: 'w-auto' }}
        >
          {[
            ...groups.map((g) => (
              <Tab key={g.key} title={<TabTitle label={g.label} count={g.demos.length} />}>
                <div className={GRID}>
                  {g.demos.map((scenario) => (
                    <DemoCard
                      key={scenario.id}
                      scenario={scenario}
                      groupLabel={g.label}
                      guide={GUIDES[scenario.id]}
                      isStarting={preparing === scenario.id}
                      onRun={() => void start(scenario)}
                    />
                  ))}
                </div>
              </Tab>
            )),
            <Tab
              key={MISTAKES_TAB}
              title={
                <TabTitle
                  label="Common mistakes"
                  count={MISTAKES.length}
                  icon={<FiAlertTriangle className="h-4 w-4 text-warning" aria-hidden />}
                />
              }
            >
              <p className="mb-4 max-w-2xl text-sm text-default-600">
                The mistakes people make most often with coroutines, what goes wrong, and what to do instead. Their
                demos are on the way.
              </p>
              <div className={GRID}>
                {MISTAKES.map((mistake) => (
                  <MistakeCard key={mistake.id} mistake={mistake} />
                ))}
              </div>
            </Tab>,
          ]}
        </Tabs>
      )}
    </div>
  )
}

function TabTitle({ label, count, icon }: { label: string; count: number; icon?: React.ReactNode }) {
  return (
    <div className="flex items-center gap-2">
      {icon}
      <span>{label}</span>
      <Chip size="sm" variant="flat">
        {count}
      </Chip>
    </div>
  )
}

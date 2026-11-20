import { useEffect, useMemo } from 'react'
import { Card, CardBody, Chip, Skeleton, Tab, Tabs } from '@heroui/react'
import { FiAlertTriangle } from 'react-icons/fi'
import { useScenarios } from '@/hooks/use-scenarios'
import { useStartDemo } from '@/hooks/use-start-demo'
import { groupDemos } from '@/lib/demo-groups'
import { GUIDES } from '@/content/gallery/guides'
import { MISTAKES, hasRunnableMistake } from '@/content/gallery/mistakes'
import type { Mistake } from '@/content/gallery/types'
import { playLabel, secondEnding } from '@/lib/demo-variants'
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
  /** The common mistakes; the app's own unless a test says otherwise. */
  mistakes?: Mistake[]
}

/**
 * The Gallery (G-1): every demo by topic, as the server lists them. Run opens
 * the same session view as the "New demo session" dialog and starts the demo
 * there; a demo with a second ending has a second button for it.
 *
 * The Common mistakes tab is there only while a mistake has a demo to run
 * (`hasRunnableMistake`). Until then a link to `?group=mistakes` shows the
 * first tab, like any unknown group.
 */
export function GalleryPage({ group, onGroupChange, mistakes = MISTAKES }: GalleryPageProps) {
  const { data, isLoading, isPending, error, refetch } = useScenarios()
  const { start, preparing, preparingVariant, startError } = useStartDemo()
  const groups = useMemo(() => groupDemos(data?.scenarios ?? []), [data?.scenarios])

  const showsMistakes = hasRunnableMistake(mistakes)

  const selected =
    (showsMistakes && group === MISTAKES_TAB) || groups.some((g) => g.key === group)
      ? group!
      : (groups[0]?.key ?? (showsMistakes ? MISTAKES_TAB : ''))

  // A link such as /gallery?group=mistakes#LEAKED_COROUTINE: the card renders
  // after the page does, so the browser's own jump to the anchor misses it.
  // Pending without loading is a request waiting for the network (offline): still no list.
  const waiting = isLoading || (isPending === true && !data && !error)
  const failed = !waiting && error != null && !data
  // The cards exist only once the list has loaded, hence `showsTabs`.
  const showsTabs = !waiting && !failed
  useEffect(() => {
    if (!showsTabs || selected !== MISTAKES_TAB || !window.location.hash) return
    let id: string
    try {
      id = decodeURIComponent(window.location.hash.slice(1))
    } catch {
      return // a malformed fragment such as #% names no card
    }
    document.getElementById(id)?.scrollIntoView({ block: 'start' })
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

      {waiting ? (
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
      ) : failed ? (
        <ErrorState error={error} subject="The demo list" onRetry={() => void refetch()} />
      ) : groups.length === 0 && !showsMistakes ? (
        <p className="text-default-600">The server lists no examples.</p>
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
                  {g.demos.map((scenario) => {
                    const second = secondEnding(scenario.id)
                    const starting = preparing === scenario.id
                    return (
                      <DemoCard
                        key={scenario.id}
                        scenario={scenario}
                        groupLabel={g.label}
                        guide={GUIDES[scenario.id]}
                        isStarting={starting && !preparingVariant}
                        isDisabled={preparing !== null && !starting}
                        onRun={() => void start(scenario)}
                        secondEnding={
                          second && {
                            label: playLabel(second.outcome),
                            isStarting: starting && preparingVariant === second.id,
                            onPlay: () => void start(scenario, second.id),
                          }
                        }
                      />
                    )
                  })}
                </div>
              </Tab>
            )),
            ...(showsMistakes
              ? [
                  <Tab
                    key={MISTAKES_TAB}
                    title={
                      <TabTitle
                        label="Common mistakes"
                        count={mistakes.length}
                        icon={<FiAlertTriangle className="h-4 w-4 text-warning" aria-hidden />}
                      />
                    }
                  >
                    <p className="mb-4 max-w-2xl text-sm text-default-600">
                      The mistakes people make most often with coroutines, what goes wrong, and what to do instead.
                    </p>
                    <div className={GRID}>
                      {mistakes.map((mistake) => {
                        // Run only for a demo the server really lists.
                        const demo = data?.scenarios.find((s) => s.id === mistake.demoId)
                        return (
                          <MistakeCard
                            key={mistake.id}
                            mistake={mistake}
                            run={
                              demo && {
                                isStarting: preparing === demo.id,
                                isDisabled: preparing !== null && preparing !== demo.id,
                                onRun: () => void start(demo),
                              }
                            }
                          />
                        )
                      })}
                    </div>
                  </Tab>,
                ]
              : []),
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

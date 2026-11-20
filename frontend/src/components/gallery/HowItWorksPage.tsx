import { Button, Card, CardBody, Chip, Skeleton } from '@heroui/react'
import { Link } from '@tanstack/react-router'
import { FiAlertTriangle, FiClock, FiZap } from 'react-icons/fi'
import { useScenarios } from '@/hooks/use-scenarios'
import { useStartDemo } from '@/hooks/use-start-demo'
import { demoSlug } from '@/lib/demo-slug'
import { GUIDES } from '@/content/gallery/guides'
import { MISTAKES, hasRunnableMistake } from '@/content/gallery/mistakes'
import { willSeeFor } from '@/content/gallery/will-see'
import type { DemoGuide, Mistake } from '@/content/gallery/types'
import { playLabel, secondEnding } from '@/lib/demo-variants'
import type { Scenario } from '@/types/api'
import { DemoStartAlert } from '../demos/DemoStartAlert'
import { ErrorState } from '../ErrorState'
import { WillSeeTree } from './WillSeeTree'
import { MISTAKES_TAB } from './GalleryPage'

const LEVEL_COLOR = { beginner: 'success', intermediate: 'warning', advanced: 'danger' } as const

/**
 * "How it works" for one demo (`/gallery/$slug`): the story, the code next to
 * its steps, what the session tree will show, the key idea and a common
 * mistake. Run opens the same session view as the Gallery's Run, and a demo
 * with a second ending has a second button for it, as in the Gallery.
 */
export function HowItWorksPage({ slug, mistakes = MISTAKES }: { slug: string; mistakes?: Mistake[] }) {
  const { data, isLoading, isPending, error, refetch } = useScenarios()
  const { start, preparing, preparingVariant, startError } = useStartDemo()

  // Pending without loading is a request waiting for the network (offline): still no list.
  const waiting = isLoading || (isPending === true && !data && !error)
  if (waiting) return <GuideSkeleton />
  if (error != null && !data) {
    return (
      <div className="container-custom py-8">
        <ErrorState error={error} subject="The demo list" onRetry={() => void refetch()} />
      </div>
    )
  }

  const scenario = data?.scenarios.find((s) => demoSlug(s.id) === slug)
  const guide = scenario ? GUIDES[scenario.id] : undefined
  const second = secondEnding(scenario?.id)
  const starting = scenario !== undefined && preparing === scenario.id
  const run = scenario && (
    <div className="flex flex-wrap gap-2">
      {second && (
        <RunButton
          scenario={scenario}
          label={playLabel(second.outcome)}
          secondary
          isStarting={starting && preparingVariant === second.id}
          isDisabled={preparing !== null && !(starting && preparingVariant === second.id)}
          onRun={() => void start(scenario, second.id)}
        />
      )}
      <RunButton
        scenario={scenario}
        isStarting={starting && !preparingVariant}
        isDisabled={preparing !== null && !(starting && !preparingVariant)}
        onRun={() => void start(scenario)}
      />
    </div>
  )
  const alert = startError && (
    <div className="mb-4">
      <DemoStartAlert startError={startError} />
    </div>
  )

  if (!scenario || !guide) {
    return (
      <div className="container-custom flex flex-col items-start gap-4 py-8">
        {alert}
        <h1 className="text-2xl font-bold">No guide for this example yet</h1>
        <p className="max-w-2xl text-default-600">
          {scenario
            ? `“${scenario.name}” has no How it works page yet. You can still run it and watch it in the session view.`
            : 'There is no example at this address.'}
        </p>
        <div className="flex gap-2">
          {run}
          <Link to="/gallery" className="self-center text-sm text-primary hover:underline">
            Back to the Gallery
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="container-custom py-8">
      <Breadcrumb scenario={scenario} />
      <header className="mb-6 mt-2 flex flex-wrap items-center gap-3">
        <h1 className="text-3xl font-bold">{scenario.name}</h1>
        <Chip size="sm" variant="flat" color={LEVEL_COLOR[guide.level]}>
          {guide.level}
        </Chip>
        {scenario.duration && (
          <Chip size="sm" variant="flat" startContent={<FiClock className="ml-1 h-3 w-3" />}>
            {scenario.duration}
          </Chip>
        )}
        <span className="flex-1" />
        {run}
      </header>
      {alert}
      <GuideBody guide={guide} mistakes={mistakes} />
    </div>
  )
}

function Breadcrumb({ scenario }: { scenario: Scenario }) {
  return (
    <nav aria-label="Breadcrumb" className="text-sm text-default-500">
      <Link to="/gallery" className="hover:underline">
        Gallery
      </Link>
      {scenario.group && scenario.groupLabel && (
        <>
          <span aria-hidden> › </span>
          <Link to="/gallery" search={{ group: scenario.group }} className="hover:underline">
            {scenario.groupLabel}
          </Link>
        </>
      )}
      <span aria-hidden> › </span>
      <span aria-current="page">{scenario.name}</span>
    </nav>
  )
}

function GuideBody({ guide, mistakes }: { guide: DemoGuide; mistakes: Mistake[] }) {
  const picture = willSeeFor(guide.demoId)
  const mistake = mistakes.find((m) => m.id === guide.mistakeId)
  // The Gallery has its Common mistakes tab only while a mistake has a demo: no link to a tab that is not there.
  const mistakeHasCard = hasRunnableMistake(mistakes)
  return (
    <div className="flex flex-col gap-8">
      <Section title="The story">
        <p className="max-w-3xl text-default-600">{guide.story}</p>
      </Section>

      <div className="grid gap-8 lg:grid-cols-2">
        <Section title="The code">
          <pre
            data-testid="guide-code"
            className="overflow-x-auto rounded-medium border border-divider bg-content2 p-4 font-mono text-xs leading-relaxed"
          >
            {guide.code}
          </pre>
        </Section>
        <Section title="Step by step">
          <ol aria-label="Step by step" className="flex flex-col gap-3">
            {guide.steps.map((step, i) => (
              <li key={step.title} className="flex gap-3">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
                  {i + 1}
                </span>
                <div>
                  <div className="font-semibold">{step.title}</div>
                  <p className="text-sm text-default-600">{step.text}</p>
                </div>
              </li>
            ))}
          </ol>
        </Section>
      </div>

      {picture && (
        <Section title="What you will see">
          <div className="max-w-2xl">
            <WillSeeTree picture={picture} />
          </div>
        </Section>
      )}

      <Section title="Key idea">
        <p className="max-w-3xl font-medium">{guide.keyIdea}</p>
      </Section>

      {mistake && (
        <Section title="Common mistake">
          {/* Amber marks a potential problem, in the icon and border only: amber text is too faint on white. */}
          <Card className="max-w-3xl border border-warning-300 bg-warning-50">
            <CardBody className="flex flex-col gap-2">
              <div className="flex items-center gap-2">
                <FiAlertTriangle className="h-4 w-4 shrink-0 text-warning" aria-hidden />
                {mistakeHasCard ? (
                  <Link
                    to="/gallery"
                    search={{ group: MISTAKES_TAB }}
                    hash={mistake.id}
                    className="font-semibold hover:underline"
                  >
                    {mistake.title} →
                  </Link>
                ) : (
                  <span className="font-semibold">{mistake.title}</span>
                )}
              </div>
              <p className="text-sm">{mistake.whatGoesWrong}</p>
              {!mistakeHasCard && (
                <p className="text-sm">
                  <span className="font-semibold">Do instead: </span>
                  {mistake.fix}
                </p>
              )}
            </CardBody>
          </Card>
        </Section>
      )}
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section>
      <h2 className="mb-3 text-lg font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function RunButton({
  scenario,
  label = 'Run this example',
  secondary = false,
  isStarting,
  isDisabled,
  onRun,
}: {
  scenario: Scenario
  label?: string
  /** The second ending's button: outlined, so Run stays the main one. */
  secondary?: boolean
  isStarting: boolean
  isDisabled: boolean
  onRun: () => void
}) {
  return (
    <Button
      color={secondary ? 'default' : 'primary'}
      variant={secondary ? 'bordered' : 'solid'}
      startContent={isStarting || secondary ? undefined : <FiZap className="h-4 w-4" />}
      isLoading={isStarting}
      isDisabled={isDisabled}
      onPress={onRun}
      data-demo-id={scenario.id}
    >
      {label}
    </Button>
  )
}

function GuideSkeleton() {
  return (
    <div data-testid="guide-skeleton" className="container-custom flex flex-col gap-4 py-8">
      <Skeleton className="h-4 w-1/3 rounded-lg" />
      <Skeleton className="h-8 w-1/2 rounded-lg" />
      <Skeleton className="h-16 w-full rounded-lg" />
      <div className="grid gap-4 lg:grid-cols-2">
        <Skeleton className="h-48 rounded-lg" />
        <Skeleton className="h-48 rounded-lg" />
      </div>
    </div>
  )
}

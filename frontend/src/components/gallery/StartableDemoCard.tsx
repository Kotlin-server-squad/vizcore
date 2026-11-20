import { GUIDES } from '@/content/gallery/guides'
import type { useStartDemo } from '@/hooks/use-start-demo'
import { playLabel, secondEnding } from '@/lib/demo-variants'
import type { Scenario } from '@/types/api'
import { DemoCard } from './DemoCard'

/** What a page's one `useStartDemo` gives its cards. */
export type DemoStart = Pick<ReturnType<typeof useStartDemo>, 'start' | 'preparing' | 'preparingVariant'>

interface StartableDemoCardProps {
  scenario: Scenario
  groupLabel: string
  demoStart: DemoStart
}

/**
 * A demo's card wired to the page's demo start: Run, and the second ending's
 * button if the demo has one. One start at a time, so the other cards wait.
 * Shared by the Gallery and the front door.
 */
export function StartableDemoCard({ scenario, groupLabel, demoStart }: StartableDemoCardProps) {
  const { start, preparing, preparingVariant } = demoStart
  const second = secondEnding(scenario.id)
  const starting = preparing === scenario.id
  return (
    <DemoCard
      scenario={scenario}
      groupLabel={groupLabel}
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
}

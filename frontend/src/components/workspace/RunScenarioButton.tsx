import { Button } from '@heroui/react'
import { FiPlay, FiRotateCw } from 'react-icons/fi'
import type { RunOutcome } from '@/lib/run-outcome'

/**
 * The header's run button (S-13). Before the run it is today's Run Scenario,
 * which starts the demo in this session. Once the run has started it is Run
 * again: a new session for the same demo, opened and started; this one stays
 * as it is. Run again is allowed while the run still goes on: the old run
 * keeps running in its own session.
 */
export function RunScenarioButton({
  outcome,
  isPending,
  isRunAgainPending = false,
  onRun,
  onRunAgain,
}: {
  outcome: RunOutcome
  isPending: boolean
  isRunAgainPending?: boolean
  onRun: () => void
  onRunAgain: () => void
}) {
  if (outcome === 'notStarted') {
    return (
      <Button size="sm" color="primary" startContent={<FiPlay aria-hidden />} onPress={onRun} isLoading={isPending}>
        Run Scenario
      </Button>
    )
  }
  return (
    <Button
      data-look-part="run-again"
      size="sm"
      color="primary"
      variant="flat"
      startContent={isRunAgainPending ? undefined : <FiRotateCw aria-hidden />}
      onPress={onRunAgain}
      isLoading={isRunAgainPending}
    >
      Run again
    </Button>
  )
}

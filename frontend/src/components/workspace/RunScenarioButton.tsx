import { Button } from '@heroui/react'
import { FiPlay } from 'react-icons/fi'
import type { RunOutcome } from '@/lib/run-outcome'

/**
 * Today's Run button, moved into the header with its labels and states
 * unchanged (spec Part 3: it stays until Run again arrives in Part 9).
 */
export function RunScenarioButton({
  outcome,
  isPending,
  onRun,
}: {
  outcome: RunOutcome
  isPending: boolean
  onRun: () => void
}) {
  if (outcome === 'finished' || outcome === 'failed') {
    return (
      <Button
        size="sm"
        color={outcome === 'failed' ? 'danger' : 'success'}
        variant="flat"
        startContent={<FiPlay aria-hidden />}
        isDisabled
      >
        {outcome === 'failed' ? 'Scenario Failed' : 'Scenario Finished'}
      </Button>
    )
  }
  return (
    <Button
      size="sm"
      color="primary"
      startContent={<FiPlay aria-hidden />}
      onPress={onRun}
      isLoading={isPending}
      isDisabled={outcome === 'running'}
    >
      {outcome === 'running' ? 'Scenario Running' : 'Run Scenario'}
    </Button>
  )
}

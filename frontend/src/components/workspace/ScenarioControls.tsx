import { useMemo } from 'react'
import { Card, CardBody, Button } from '@heroui/react'
import { FiPlay, FiRotateCcw, FiTrash2 } from 'react-icons/fi'
import { useNavigate } from '@tanstack/react-router'
import { useRunScenario, } from '@/hooks/use-scenarios'
import { useDeleteSession } from '@/hooks/use-sessions'
import { CoroutineState, type SessionSnapshot } from '@/types/api'

/** Terminal coroutine states — no further transitions expected. */
const TERMINAL_STATES = new Set<CoroutineState>([
  CoroutineState.COMPLETED,
  CoroutineState.CANCELLED,
  CoroutineState.FAILED,
])

interface ScenarioControlsProps {
  sessionId: string
  scenarioId: string
  session: SessionSnapshot
  /** Empties the live event list. Never deletes the session (WR-05). */
  onClearEvents: () => void
  onRefetch: () => void
}

/**
 * Run / Reset / Clear for a scenario-backed session.
 *
 * Mutations only, so the caller gates this off entirely in the read-only
 * shared view (T-03-22) — mounting it there would put three mutation hooks
 * behind a shell that carries no Bearer.
 */
export function ScenarioControls({
  sessionId,
  scenarioId,
  session,
  onClearEvents,
  onRefetch,
}: ScenarioControlsProps) {
  const runScenario = useRunScenario()
  const deleteSession = useDeleteSession()
  const navigate = useNavigate()

  // Three-state scenario derivation:
  //   notStarted — coroutineCount === 0 (no coroutines seen yet)
  //   running    — coroutines exist but at least one is non-terminal
  //   completed  — coroutines exist and ALL are in a terminal state
  const scenarioState: 'notStarted' | 'running' | 'completed' = useMemo(() => {
    if (!session || session.coroutineCount === 0 || session.coroutines.length === 0) {
      return 'notStarted'
    }
    const allTerminal = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
    return allTerminal ? 'completed' : 'running'
  }, [session])

  const handleRunScenario = async () => {
    if (!scenarioId) return

    try {
      await runScenario.mutateAsync({ scenarioId, sessionId })
      // Refetch immediately after running
      setTimeout(() => onRefetch(), 500)
    } catch {
      // Error is handled by the mutation's error state
    }
  }

  // Clear ONLY empties the live event list (WR-05) — it must never delete
  // the session. Reset (below) remains the destructive delete-and-navigate.
  const handleClear = () => {
    onClearEvents()
    onRefetch()
  }

  const handleReset = async () => {
    if (!confirm('Reset this session? This will clear all coroutines and start fresh.')) {
      return
    }

    try {
      // Delete current session
      await deleteSession.mutateAsync(sessionId)

      // Navigate back to scenarios or create new session
      navigate({ to: '/scenarios' })
    } catch {
      // Error is handled by the mutation's error state
    }
  }

  return (
    <Card>
      <CardBody>
        <div className="flex items-center justify-between">
          <div>
            <h3 className="mb-1 text-lg font-semibold">Scenario Controls</h3>
            <p className="text-sm text-default-500">
              {scenarioState === 'notStarted' && 'Ready to run the scenario'}
              {scenarioState === 'running' && 'Scenario is running'}
              {scenarioState === 'completed' && 'Scenario completed'}
            </p>
          </div>
          <div className="flex gap-2">
            {scenarioState === 'completed' ? (
              <Button
                color="success"
                size="lg"
                variant="flat"
                startContent={<FiPlay />}
                isDisabled
              >
                Scenario Completed
              </Button>
            ) : (
            <Button
              color="primary"
              size="lg"
              startContent={<FiPlay />}
              onPress={handleRunScenario}
              isLoading={runScenario.isPending}
              isDisabled={scenarioState === 'running'}
            >
              {scenarioState === 'running' ? 'Scenario Running' : 'Run Scenario'}
            </Button>
            )}
            <Button
              color="warning"
              size="lg"
              variant="flat"
              startContent={<FiRotateCcw />}
              onPress={handleReset}
              isLoading={deleteSession.isPending}
            >
              Reset
            </Button>
            <Button
              color="danger"
              size="lg"
              variant="light"
              startContent={<FiTrash2 />}
              onPress={handleClear}
            >
              Clear
            </Button>
          </div>
        </div>
      </CardBody>
    </Card>
  )
}

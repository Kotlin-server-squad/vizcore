import { useMemo, useState } from 'react'
import { Card, CardBody, Button } from '@heroui/react'
import { FiPlay, FiRotateCcw, FiTrash2 } from 'react-icons/fi'
import { useNavigate } from '@tanstack/react-router'
import { useRunScenario } from '@/hooks/use-scenarios'
import { useCreateSession, useDeleteSession } from '@/hooks/use-sessions'
import { describeApiError } from '@/lib/api-errors'
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
  /** Display name of the scenario; names the fresh session Reset creates. */
  scenarioName?: string
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
 *
 * Every action reports its own failure inline (#139); none of them swallows
 * an error.
 */
export function ScenarioControls({
  sessionId,
  scenarioId,
  scenarioName,
  session,
  onClearEvents,
  onRefetch,
}: ScenarioControlsProps) {
  const runScenario = useRunScenario()
  const deleteSession = useDeleteSession()
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<{ action: string; error: unknown } | null>(null)

  // Run outcome (spec S-3):
  //   notStarted — no coroutines seen yet
  //   failed     — at least one coroutine FAILED, whether or not others still
  //                run. Never green "completed": a run that failed something did
  //                not succeed, and a supervisor's surviving children do not undo it.
  //   running    — none failed, at least one is not terminal
  //   finished   — all terminal, none failed
  const { scenarioState, failedCount } = useMemo(() => {
    if (!session || session.coroutineCount === 0 || session.coroutines.length === 0) {
      return { scenarioState: 'notStarted' as const, failedCount: 0 }
    }
    const failed = session.coroutines.filter(c => c.state === CoroutineState.FAILED).length
    if (failed > 0) return { scenarioState: 'failed' as const, failedCount: failed }
    const allTerminal = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
    return { scenarioState: allTerminal ? ('finished' as const) : ('running' as const), failedCount: 0 }
  }, [session])

  const handleRunScenario = async () => {
    if (!scenarioId) return
    setActionError(null)
    try {
      await runScenario.mutateAsync({ scenarioId, sessionId })
      // Refetch immediately after running
      setTimeout(() => onRefetch(), 500)
    } catch (error) {
      setActionError({ action: 'Run the scenario', error })
    }
  }

  // Clear ONLY empties the live event list (WR-05) — it must never delete
  // the session. Reset (below) remains the destructive delete-and-restart.
  const handleClear = () => {
    onClearEvents()
    onRefetch()
  }

  // Reset = "clear all coroutines and start fresh": delete this session, then
  // open a fresh session for the same scenario. It used to navigate to
  // /scenarios, which only redirects back to the sessions list (#139).
  const handleReset = async () => {
    if (!confirm('Reset this session? This will clear all coroutines and start fresh.')) {
      return
    }
    setActionError(null)
    try {
      await deleteSession.mutateAsync(sessionId)
    } catch (error) {
      setActionError({ action: 'Delete this session', error })
      return
    }
    const name = scenarioName ?? scenarioId
    try {
      const fresh = await createSession.mutateAsync(`scenario-${name}`)
      void navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: fresh.sessionId },
        search: { scenarioId, scenarioName: name },
      })
    } catch {
      // The old session is gone; with no fresh one the list is the only
      // sensible place left, and it shows its own errors.
      void navigate({ to: '/' })
    }
  }

  return (
    <Card>
      <CardBody className="gap-3">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="mb-1 text-lg font-semibold">Scenario Controls</h3>
            {/* A live region, so a screen reader hears a run fail without moving focus. */}
            <p
              role="status"
              aria-atomic="true"
              className={scenarioState === 'failed' ? 'text-sm text-danger' : 'text-sm text-default-500'}
            >
              {scenarioState === 'notStarted' && 'Ready to run the scenario'}
              {scenarioState === 'running' && 'Scenario is running'}
              {scenarioState === 'finished' && 'Scenario finished'}
              {scenarioState === 'failed' &&
                `Scenario failed: ${failedCount} ${failedCount === 1 ? 'coroutine' : 'coroutines'} failed`}
            </p>
          </div>
          <div className="flex gap-2">
            {scenarioState === 'finished' || scenarioState === 'failed' ? (
              <Button
                color={scenarioState === 'failed' ? 'danger' : 'success'}
                size="lg"
                variant="flat"
                startContent={<FiPlay />}
                isDisabled
              >
                {scenarioState === 'failed' ? 'Scenario Failed' : 'Scenario Finished'}
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
              isLoading={deleteSession.isPending || createSession.isPending}
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
        {actionError && (
          <p role="alert" className="text-sm text-danger">
            Could not {actionError.action.toLowerCase()}:{' '}
            {describeApiError(actionError.error, 'This session').message}
          </p>
        )}
      </CardBody>
    </Card>
  )
}

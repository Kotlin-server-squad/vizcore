import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useRunScenario } from '@/hooks/use-scenarios'
import { useCreateSession, useDeleteSession } from '@/hooks/use-sessions'
import { describeApiError } from '@/lib/api-errors'
import { sessionDisplayName } from '@/lib/session-kind'

interface ScenarioActionArgs {
  sessionId: string
  /** The demo's catalog id: the address's `scenarioId`, else the session's `demoId` (S-13). */
  scenarioId: string
  /** Display name of the scenario; names the fresh session Reset creates. */
  scenarioName?: string
  /** Empties the live event list. Never deletes the session (WR-05). */
  onClearEvents: () => void
  onRefetch: () => void
}

export interface ScenarioActions {
  run: () => Promise<void>
  /** A new session for the same demo, opened so that it starts the run there (S-13). */
  runAgain: () => Promise<void>
  reset: () => Promise<void>
  clear: () => void
  isRunPending: boolean
  isRunAgainPending: boolean
  isResetPending: boolean
  /** Why the last action failed, in a sentence (#139); null when it did not. */
  errorText: string | null
}

/**
 * Run / Run again / Reset / Clear for a scenario-backed session (they were the
 * `ScenarioControls` card; the header now offers them, S-3).
 *
 * Mutation hooks only: nothing is requested until an action is invoked, and
 * the caller offers none of them in the read-only shared view (T-03-22).
 */
export function useScenarioActions({
  sessionId,
  scenarioId,
  scenarioName,
  onClearEvents,
  onRefetch,
}: ScenarioActionArgs): ScenarioActions {
  const runScenario = useRunScenario()
  const deleteSession = useDeleteSession()
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<{ action: string; error: unknown } | null>(null)
  const [runAgainPending, setRunAgainPending] = useState(false)
  // The name new sessions get: the address's, else the one the session id was minted from.
  const name = scenarioName ?? sessionDisplayName(sessionId)

  const run = async () => {
    setActionError(null)
    try {
      await runScenario.mutateAsync({ scenarioId, sessionId })
      // Refetch immediately after running
      setTimeout(() => onRefetch(), 500)
    } catch (error) {
      setActionError({ action: 'Run the scenario', error })
    }
  }

  // Run again (S-13): a NEW session for the same demo; this one is not touched (no delete, no
  // request). The new page starts the run itself (`run: true`, use-auto-run): some demo routes
  // answer only once the demo has finished, so starting it from here would open a finished run.
  const runAgain = async () => {
    setActionError(null)
    setRunAgainPending(true)
    try {
      const fresh = await createSession.mutateAsync({ name: `scenario-${name}`, demoId: scenarioId })
      void navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: fresh.sessionId },
        search: { scenarioId, scenarioName: name, run: true },
      })
    } catch (error) {
      setActionError({ action: 'Run it again', error })
    } finally {
      setRunAgainPending(false)
    }
  }

  // Clear ONLY empties the live event list (WR-05) — it must never delete
  // the session. Reset (below) remains the destructive delete-and-restart.
  const clear = () => {
    onClearEvents()
    onRefetch()
  }

  // Reset = "clear all coroutines and start fresh": delete this session, then
  // open a fresh session for the same scenario. It used to navigate to
  // /scenarios, which only redirects back to the sessions list (#139).
  const reset = async () => {
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
    try {
      const fresh = await createSession.mutateAsync({ name: `scenario-${name}`, demoId: scenarioId })
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

  return {
    run,
    runAgain,
    reset,
    clear,
    isRunPending: runScenario.isPending,
    isRunAgainPending: runAgainPending,
    isResetPending: deleteSession.isPending || createSession.isPending,
    errorText: actionError
      ? `Could not ${actionError.action.toLowerCase()}: ${describeApiError(actionError.error, 'This session').message}`
      : null,
  }
}

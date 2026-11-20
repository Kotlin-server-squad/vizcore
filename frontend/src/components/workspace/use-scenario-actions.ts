import { useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useRunScenario } from '@/hooks/use-scenarios'
import { useCreateSession, useDeleteSession } from '@/hooks/use-sessions'
import { describeApiError } from '@/lib/api-errors'
import { sessionDisplayName } from '@/lib/session-kind'
import { playLabel, secondEnding, USUAL_OUTCOME, variantParams, type DemoVariant } from '@/lib/demo-variants'

interface ScenarioActionArgs {
  sessionId: string
  /** The demo's catalog id: the address's `scenarioId`, else the session's `demoId` (S-13). */
  scenarioId: string
  /** Display name of the scenario; names the fresh session Reset creates. */
  scenarioName?: string
  /** The ending this session plays, already checked against the demo; none is the usual one. */
  variant?: DemoVariant
  /** Opened without its ending on the address and none remembered (ending-memory): the header names none. */
  endingUnknown?: boolean
  /** Empties the live event list. Never deletes the session (WR-05). */
  onClearEvents: () => void
  onRefetch: () => void
}

export interface ScenarioActions {
  run: () => Promise<void>
  /** A new session for the same demo and the same ending, opened so that it starts the run there (S-13). */
  runAgain: () => Promise<void>
  /** The ending this session plays ("card declined", "it works"), for the header; none for a demo with one ending. */
  ending?: string
  /** The demo's other ending, when it has two: a new session that plays it, like Run again. */
  otherEnding?: { label: string; play: () => Promise<void>; isPending: boolean }
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
  variant,
  endingUnknown = false,
  onClearEvents,
  onRefetch,
}: ScenarioActionArgs): ScenarioActions {
  const runScenario = useRunScenario()
  const deleteSession = useDeleteSession()
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [actionError, setActionError] = useState<{ action: string; error: unknown } | null>(null)
  // Which of the two "new session" buttons is at work.
  const [freshPending, setFreshPending] = useState<'again' | 'other' | null>(null)
  // The name new sessions get: the address's, else the one the session id was minted from.
  const name = scenarioName ?? sessionDisplayName(sessionId)

  const run = async () => {
    setActionError(null)
    try {
      await runScenario.mutateAsync({ scenarioId, sessionId, ...(variant ? { params: variantParams(variant) } : {}) })
      // Refetch immediately after running
      setTimeout(() => onRefetch(), 500)
    } catch (error) {
      setActionError({ action: 'Run the scenario', error })
    }
  }

  // Run again (S-13): a NEW session for the same demo; this one is not touched (no delete, no
  // request). The new page starts the run itself (`run: true`, use-auto-run): some demo routes
  // answer only once the demo has finished, so starting it from here would open a finished run.
  // The address keeps the ending, so the new page runs the same one, and its Run again too.
  const openFresh = async (which: 'again' | 'other', ending: DemoVariant | undefined) => {
    if (freshPending) return
    setActionError(null)
    setFreshPending(which)
    try {
      const fresh = await createSession.mutateAsync({ name: `scenario-${name}`, demoId: scenarioId })
      void navigate({
        to: '/sessions/$sessionId',
        params: { sessionId: fresh.sessionId },
        search: { scenarioId, scenarioName: name, run: true, ...(ending ? { variant: ending.id } : {}) },
      })
    } catch (error) {
      setActionError({ action: which === 'again' ? 'Run it again' : 'Play the other ending', error })
    } finally {
      setFreshPending(null)
    }
  }
  const runAgain = () => openFresh('again', variant)
  // The ending this session does not play: the second one, or the usual one when this is the second.
  const second = secondEnding(scenarioId)
  const other = variant ? undefined : second

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
        search: { scenarioId, scenarioName: name, ...(variant ? { variant: variant.id } : {}) },
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
    ending: variant?.outcome ?? (second && !endingUnknown ? USUAL_OUTCOME : undefined),
    otherEnding: second && {
      label: playLabel(other ? other.outcome : USUAL_OUTCOME),
      play: () => openFresh('other', other),
      isPending: freshPending === 'other',
    },
    reset,
    clear,
    isRunPending: runScenario.isPending,
    isRunAgainPending: freshPending === 'again',
    isResetPending: deleteSession.isPending || createSession.isPending,
    errorText: actionError
      ? `Could not ${actionError.action.toLowerCase()}: ${describeApiError(actionError.error, 'This session').message}`
      : null,
  }
}

import { useCallback, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useCreateSession } from '@/hooks/use-sessions'
import type { Scenario } from '@/types/api'

export interface DemoStartError {
  scenario: string
  error: unknown
}

/**
 * Starts a demo: mint a `scenario-`-prefixed session (which is what
 * `deriveSessionKind` keys the DEMO badge off), then open it. Shared by the
 * "New demo session" dialog and the Gallery.
 *
 * Never rejects: a failed create (e.g. the session-create rate limit) is kept
 * in `startError` for the caller to show, instead of becoming an unhandled
 * rejection (#139).
 */
export function useStartDemo(onStarted?: () => void) {
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [preparing, setPreparing] = useState<string | null>(null)
  const [startError, setStartError] = useState<DemoStartError | null>(null)

  const start = useCallback(
    async (scenario: Scenario) => {
      setPreparing(scenario.id)
      setStartError(null)
      try {
        const result = await createSession.mutateAsync(`scenario-${scenario.name}`)
        onStarted?.()
        navigate({
          to: '/sessions/$sessionId',
          params: { sessionId: result.sessionId },
          search: { scenarioId: scenario.id, scenarioName: scenario.name },
        })
      } catch (error) {
        setStartError({ scenario: scenario.name, error })
      } finally {
        setPreparing(null)
      }
    },
    [createSession, navigate, onStarted],
  )

  return { start, preparing, startError }
}

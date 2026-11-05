import { useCallback, useEffect, useRef, useState } from 'react'
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
 * rejection (#139). One start at a time: a second one while the first is still
 * creating its session is ignored, or the slower answer would open the other demo.
 * A start whose page is gone by the time its session exists does not navigate.
 */
export function useStartDemo(onStarted?: () => void) {
  const createSession = useCreateSession()
  const navigate = useNavigate()
  const [preparing, setPreparing] = useState<string | null>(null)
  const [startError, setStartError] = useState<DemoStartError | null>(null)
  const inFlight = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const start = useCallback(
    async (scenario: Scenario) => {
      if (inFlight.current) return
      inFlight.current = true
      setPreparing(scenario.id)
      setStartError(null)
      try {
        const result = await createSession.mutateAsync(`scenario-${scenario.name}`)
        if (!mounted.current) return
        onStarted?.()
        navigate({
          to: '/sessions/$sessionId',
          params: { sessionId: result.sessionId },
          search: { scenarioId: scenario.id, scenarioName: scenario.name },
        })
      } catch (error) {
        if (mounted.current) setStartError({ scenario: scenario.name, error })
      } finally {
        inFlight.current = false
        if (mounted.current) setPreparing(null)
      }
    },
    [createSession, navigate, onStarted],
  )

  return { start, preparing, startError }
}

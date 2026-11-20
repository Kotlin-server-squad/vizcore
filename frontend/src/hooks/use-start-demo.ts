import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useCreateSession } from '@/hooks/use-sessions'
import { validVariant } from '@/lib/demo-variants'
import type { Scenario } from '@/types/api'

export interface DemoStartError {
  scenario: string
  error: unknown
}

/**
 * Starts a demo: mint a `scenario-`-prefixed session (which is what
 * `deriveSessionKind` keys the DEMO badge off), then open it with `run: true`,
 * so the session page starts the run itself, once (use-auto-run). Shared by the
 * "New demo session" dialog and the Gallery.
 *
 * A demo's second ending is started by its variant id (demo-variants); one the
 * demo does not have is ignored, and the usual ending runs.
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
  // Which ending of `preparing` is starting: its variant id, null for the usual one.
  const [preparingVariant, setPreparingVariant] = useState<string | null>(null)
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
    async (scenario: Scenario, variantId?: string) => {
      if (inFlight.current) return
      inFlight.current = true
      const variant = validVariant(scenario.id, variantId)
      setPreparing(scenario.id)
      setPreparingVariant(variant?.id ?? null)
      setStartError(null)
      try {
        const result = await createSession.mutateAsync({ name: `scenario-${scenario.name}`, demoId: scenario.id })
        if (!mounted.current) return
        onStarted?.()
        navigate({
          to: '/sessions/$sessionId',
          params: { sessionId: result.sessionId },
          search: {
            scenarioId: scenario.id,
            scenarioName: scenario.name,
            run: true,
            ...(variant ? { variant: variant.id } : {}),
          },
        })
      } catch (error) {
        if (mounted.current) setStartError({ scenario: scenario.name, error })
      } finally {
        inFlight.current = false
        if (mounted.current) {
          setPreparing(null)
          setPreparingVariant(null)
        }
      }
    },
    [createSession, navigate, onStarted],
  )

  return { start, preparing, preparingVariant, startError }
}

import { useEffect, useRef } from 'react'
import type { RunOutcome } from '@/lib/run-outcome'

/**
 * Run again (S-13) opens its new session with `?run=true`, and the new page
 * starts the run itself: some demo routes answer only when the demo has
 * finished, so a run started by the old page would open a finished session.
 *
 * Once the session has loaded ([outcome] known), the request is dropped from
 * the address first, so a reload never starts a second run, and the run is
 * started only if nothing has run yet. Once per page.
 */
export function useAutoRun({
  requested,
  outcome,
  run,
  onConsumed,
}: {
  requested: boolean
  /** The run's outcome, null until the session has loaded. */
  outcome: RunOutcome | null
  run: () => Promise<void>
  onConsumed: () => void
}): void {
  const done = useRef(false)
  useEffect(() => {
    if (!requested || outcome === null || done.current) return
    done.current = true
    onConsumed()
    if (outcome === 'notStarted') void run()
  }, [requested, outcome, run, onConsumed])
}

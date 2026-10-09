import { useEffect, useMemo, useRef } from 'react'
import { useCoroutineTimeline } from '@/hooks/use-timeline'
import { resolveRunsOn } from '@/lib/runs-on'
import { TimingCard } from './TimingCard'
import { SuspendedAtCard } from './SuspendedAtCard'
import { RunsOnCard } from './RunsOnCard'
import { IdentityCard } from './IdentityCard'
import { EventsCard } from './EventsCard'
import type { CoroutineNode, ThreadActivity } from '@/types/api'

interface InspectorProps {
  sessionId: string
  /** Null = nothing selected. No timeline is fetched while it is null (D-08). */
  coroutine: CoroutineNode | null
  /**
   * Read-only shared view: the shell carries no Bearer, so every
   * timeline-backed card is omitted rather than rendered against a 401.
   * Identity needs no fetch, so it is what remains. The workspace does not
   * offer selection in the shared view (PD-01) and passes a `placeholder`
   * saying so; this guard stays as a no-protected-fetch guarantee.
   */
  readOnly: boolean
  /**
   * Thread activity for the session — the only source that names the thread and
   * dispatcher a coroutine is on. Undefined while it loads, and in the
   * read-only shared view where the protected /threads fetch is disabled.
   */
  threadActivity?: ThreadActivity
  /** What to say while nothing is selected (e.g. why selection is unavailable). */
  placeholder?: string
}

/**
 * The right-hand detail column (D-7), ordered most-diagnostic-first:
 * timing → suspended at → runs on → identity → events.
 *
 * That order is carried over from validated sketch 005 rather than re-derived.
 * It answers, in sequence, the questions a developer actually asks of a stuck
 * coroutine: how long, where, on what, which one, and what just happened.
 *
 * One fetch backs four of the five cards. `CoroutineSourceStack` inside
 * `SuspendedAtCard` calls the same query — React Query dedupes on the key, so
 * this is one network request, not two.
 */
export function Inspector({
  sessionId,
  coroutine,
  readOnly,
  threadActivity,
  placeholder = 'Select a coroutine to inspect it',
}: InspectorProps) {
  const showTimeline = !!coroutine && !readOnly
  const { data: timeline, refetch: refetchTimeline } = useCoroutineTimeline(
    showTimeline ? sessionId : undefined,
    showTimeline ? coroutine.id : undefined,
  )

  // Keep the selected coroutine's timeline current while it runs. The live
  // stream no longer invalidates every ['sessions', id, …] key on each flush
  // (#137); instead re-read the timeline when the refreshed snapshot reports
  // that THIS coroutine changed state — bounded by the snapshot's own cadence.
  const lastSeenRef = useRef<{ id: string; state: string } | null>(null)
  const coroutineId = coroutine?.id
  const coroutineState = coroutine?.state
  useEffect(() => {
    if (!showTimeline || !coroutineId || !coroutineState) {
      lastSeenRef.current = null
      return
    }
    const last = lastSeenRef.current
    lastSeenRef.current = { id: coroutineId, state: coroutineState }
    if (last && last.id === coroutineId && last.state !== coroutineState) {
      void refetchTimeline()
    }
  }, [showTimeline, coroutineId, coroutineState, refetchTimeline])

  const runsOn = useMemo(
    () => (coroutine ? resolveRunsOn(threadActivity, coroutine.id) : null),
    [threadActivity, coroutine],
  )

  if (!coroutine) {
    return (
      <div className="text-sm text-default-400">
        {placeholder}
      </div>
    )
  }

  return (
    <div data-testid="inspector" className="space-y-3">
      {showTimeline && (
        <>
          <TimingCard timeline={timeline} />
          <SuspendedAtCard sessionId={sessionId} coroutineId={coroutine.id} />
          <RunsOnCard runsOn={runsOn} />
        </>
      )}
      <IdentityCard coroutine={coroutine} />
      {showTimeline && <EventsCard timeline={timeline} />}
    </div>
  )
}

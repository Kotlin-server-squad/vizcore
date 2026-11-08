import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import type { CoroutineProblem } from '@/lib/health-text'
import { useCoroutineTimeline } from '@/hooks/use-timeline'
import { resolveRunsOn } from '@/lib/runs-on'
import { stateLabel } from '@/lib/coroutine-state-colors'
import { TimingCard, Transitions } from './TimingCard'
import { InspectorCard } from './InspectorCard'
import { SuspendedAtCard } from './SuspendedAtCard'
import { RunsOnCard } from './RunsOnCard'
import { IdentityCard } from './IdentityCard'
import { EventsCard } from './EventsCard'
import { SummaryCard } from './SummaryCard'
import { ExceptionCard } from './ExceptionCard'
import { ClueCard } from './ClueCard'
import { ObservedCard } from './ObservedCard'
import { CardFacts } from '../../CardFacts'
import { MomentNote } from '../MomentNote'
import { MomentContext, type MomentView } from '@/hooks/use-moment'
import { momentErrorText } from '@/lib/moment-errors'
import type { CoroutineNode, ThreadActivity } from '@/types/api'
import type { ActivityLine } from '@/lib/activity'

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
  /**
   * What to show while nothing is selected; overrides `placeholder` (T-3's
   * session summary). Kept mounted, hidden, under a selection, so its queries
   * stay subscribed and coming back to it refetches nothing (#124).
   */
  emptyState?: ReactNode
  /** Clears the selection, back to the empty state; offered under the heading. */
  onClearSelection?: () => void
  /** What the selected coroutine waits for or holds (S-6), from the page's events. */
  activity?: ActivityLine | null
  /**
   * The moment the Inspector shows, outside the live view (Part 8, S-11): a replay cursor or a shared link's loaded
   * cut. Its reads are bound to it, the read-only shared view included (it reads through the share token).
   */
  moment?: MomentView | null
  /**
   * A shared link without a health has no cut to bind its reads to (M-11): the reason, and the Inspector shows identity
   * only rather than guess a binding.
   */
  unavailableReason?: string | null
  /** In replay, the selection's name when the cursor's coroutines do not hold it (M-5). */
  pendingSelectionLabel?: string | null
  /** Why they do not: by default it does not exist yet at the cursor. */
  pendingSelectionNote?: string
  /** The selected coroutine's problem in the session health, which sets the cards' order (triage T-3, Part 7). */
  problem?: CoroutineProblem | null
  /** The health's "as of" its problem cards come from (H-2). */
  problemAsOf?: string
  /** The health lists only part of its problems: a coroutine it does not list may still have one. */
  problemsCut?: boolean
  /** A problem whose coroutine the page does not draw (trimmed, or not created at the cursor): opened from the incident alone. */
  problemOnly?: { id: string; label: string; problem: CoroutineProblem } | null
  /** App-clock time of the session's first event, for times into the capture. */
  captureStartAppNanos?: number | null
  /** Changed by "Show": focus moves to the heading (T-3). */
  focusKey?: number
  /**
   * Live view: the newest seq of an event the page received for the selected coroutine. Its timeline is re-read after
   * it, at most every 10 s (a suspend and resume between two snapshots leaves its state unchanged).
   */
  liveSeq?: number
  /** While the Timeline is the main view, its one-sentence summary of the selected row (Part 10, S-10). */
  sentence?: string | null
}

/** A live coroutine's timeline is re-read after its own events at most this often (Part 7, P-1). */
const LIVE_TIMELINE_MIN_INTERVAL_MS = 10_000

/**
 * The Inspector's reads at a moment, provided to every card below (Part 8, M-3). Outside it, nothing is provided and
 * every read is the live one.
 */
export function Inspector(props: InspectorProps) {
  const body = <InspectorBody {...props} />
  return props.moment ? <MomentContext.Provider value={props.moment}>{body}</MomentContext.Provider> : body
}

/**
 * The Inspector tab (D-7, S-4), ordered most-diagnostic-first:
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
function InspectorBody({
  sessionId,
  coroutine,
  readOnly,
  threadActivity,
  placeholder = 'Select a coroutine to inspect it',
  emptyState,
  onClearSelection,
  activity = null,
  moment = null,
  unavailableReason = null,
  pendingSelectionLabel = null,
  pendingSelectionNote = 'not created yet at this moment',
  problem = null,
  problemAsOf,
  problemsCut = false,
  problemOnly = null,
  captureStartAppNanos = null,
  focusKey,
  liveSeq,
  sentence = null,
}: InspectorProps) {
  const headingRef = useRef<HTMLHeadingElement | null>(null)
  // "Show" moves focus to the heading (T-3), once per request, after the selection has rendered.
  useEffect(() => {
    if (focusKey !== undefined) headingRef.current?.focus()
  }, [focusKey])
  // The shared view has no credential: only a read bound to its cut (through the token) may fetch (M-11).
  const showTimeline = !!coroutine && (!readOnly || !!moment) && !unavailableReason
  const {
    data: timeline,
    refetch: refetchTimeline,
    moment: momentStatus,
    error: timelineError,
  } = useCoroutineTimeline(
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
    // At a moment the read is bound to it; nothing live to follow.
    if (moment || !showTimeline || !coroutineId || !coroutineState) {
      lastSeenRef.current = null
      return
    }
    const last = lastSeenRef.current
    lastSeenRef.current = { id: coroutineId, state: coroutineState }
    // While the stream brings its events, the throttled re-read below covers a state change too: one path, not two.
    if (liveSeq !== undefined) return
    if (last && last.id === coroutineId && last.state !== coroutineState) {
      void refetchTimeline()
    }
  }, [moment, showTimeline, coroutineId, coroutineState, refetchTimeline, liveSeq])

  // Re-read after the coroutine's own new events, throttled (P-1). Never at a moment: that read is bound.
  const lastLiveReadRef = useRef<{ id: string; seq: number; at: number } | null>(null)
  useEffect(() => {
    if (moment || !showTimeline || !coroutineId) return
    const last = lastLiveReadRef.current
    if (!last || last.id !== coroutineId) {
      // The selection's first read happens anyway, or comes from the cache, which may predate the events received since:
      // count from now, but from no event, so the next one the throttle allows re-reads it.
      lastLiveReadRef.current = { id: coroutineId, seq: -1, at: Date.now() }
    }
    const current = lastLiveReadRef.current!
    if (liveSeq === undefined || liveSeq <= current.seq) return
    const wait = Math.max(0, current.at + LIVE_TIMELINE_MIN_INTERVAL_MS - Date.now())
    const timer = setTimeout(() => {
      lastLiveReadRef.current = { id: coroutineId, seq: liveSeq, at: Date.now() }
      void refetchTimeline()
    }, wait)
    return () => clearTimeout(timer)
  }, [moment, showTimeline, coroutineId, liveSeq, refetchTimeline])

  const runsOn = useMemo(
    () => (coroutine ? resolveRunsOn(threadActivity, coroutine.id) : null),
    [threadActivity, coroutine],
  )

  const empty =
    emptyState !== undefined ? (
      <div hidden={!!coroutine || !!pendingSelectionLabel || !!problemOnly}>{emptyState}</div>
    ) : !coroutine && !pendingSelectionLabel ? (
      <div className="text-sm text-default-400">
        {placeholder}
      </div>
    ) : null

  if (!coroutine && problemOnly) {
    // From the incident alone (T-3): no canvas node, no timeline read.
    return (
      <>
        {empty}
        <div data-testid="inspector" className="space-y-3">
          <MomentNote moment={moment?.moment ?? null} />
          <div className="flex flex-wrap items-baseline gap-x-2">
            <h2 id="inspector-heading" ref={headingRef} tabIndex={-1} className="text-base font-semibold outline-none">
              <span className="font-mono [overflow-wrap:anywhere]">{problemOnly.label}</span>
            </h2>
            {onClearSelection && (
              <button type="button" onClick={onClearSelection} className="ml-auto text-xs text-primary hover:underline">
                Session summary
              </button>
            )}
          </div>
          <p data-testid="problem-only-note" className="text-sm text-default-500">
            Not drawn in the tree at this moment: shown from the health; its events may have been trimmed.
          </p>
          <div className="grid gap-3 lg:grid-cols-2">
            <div className="min-w-0 space-y-3">
              <ProblemCards problem={problemOnly.problem} asOf={problemAsOf} />
              {problemOnly.problem.kind === 'POSSIBLY_BLOCKED' && (
                // Its running time and transitions are the health's too: no timeline read is needed for them.
                <InspectorCard title="Timing" testId="timing-card">
                  <Transitions incident={problemOnly.problem.incident} captureStartAppNanos={captureStartAppNanos} />
                </InspectorCard>
              )}
            </div>
          </div>
        </div>
      </>
    )
  }

  if (!coroutine) {
    if (!pendingSelectionLabel) return empty
    return (
      <>
        {empty}
        <div data-testid="inspector-pending" className="space-y-2">
          <MomentNote moment={moment?.moment ?? null} />
          <p className="text-sm text-default-500">
            <span className="font-mono">{pendingSelectionLabel}</span> · {pendingSelectionNote}
          </p>
          {onClearSelection && (
            <button type="button" onClick={onClearSelection} className="text-xs text-primary hover:underline">
              Session summary
            </button>
          )}
        </div>
      </>
    )
  }

  const failure = momentStatus?.status === 'error' ? momentErrorText(timelineError) : null

  return (
    <>
      {empty}
      <div data-testid="inspector" className="space-y-3">
        <MomentNote moment={moment?.moment ?? null} />
        <div className="flex flex-wrap items-baseline gap-x-2">
          <h2
            id="inspector-heading"
            ref={headingRef}
            tabIndex={-1}
            className="flex flex-wrap items-baseline gap-x-2 text-base font-semibold outline-none"
          >
            <span className="font-mono [overflow-wrap:anywhere]">{coroutine.label ?? coroutine.id}</span>
            <span className="text-sm font-normal text-default-500">{stateLabel(coroutine.state)}</span>
          </h2>
          {onClearSelection && (
            <button
              type="button"
              onClick={onClearSelection}
              className="ml-auto text-xs text-primary hover:underline"
            >
              Session summary
            </button>
          )}
        </div>
        {/* Its lifetime, creation site and the health's observation badges, as on its card (Part 7, P-9). */}
        <CardFacts node={coroutine} />
        {/* What it waits for or holds (S-6): a wait in the Suspended colour, a hold neutral. */}
        {activity && (
          <p
            data-testid="inspector-activity"
            className={activity.waiting ? 'text-sm text-warning' : 'text-sm text-default-600'}
          >
            {activity.text}
          </p>
        )}
        {/* The Timeline's one sentence for the selected row (Part 10), from the same spans it draws. */}
        {sentence && (
          <p data-testid="inspector-timeline-sentence" data-look-part="timeline-sentence" className="text-sm text-default-700">
            {sentence}
          </p>
        )}
        {/* At a moment (Part 8): what the read could not give, said once, above the cards. */}
        {unavailableReason && (
          <p data-look-part="trimmed-note" className="text-sm text-default-500">
            Details unavailable: this link&apos;s health could not be read ({unavailableReason}).
          </p>
        )}
        {momentStatus?.status === 'updating' && (
          <p className="text-sm text-default-400">Updating to this moment…</p>
        )}
        {failure && (
          <p data-testid="inspector-read-error" className="text-sm text-default-500">
            {failure.text}{' '}
            {failure.action && (
              <button
                type="button"
                onClick={() => (failure.action === 'reload' ? window.location.reload() : refetchTimeline())}
                className="text-primary hover:underline"
              >
                {failure.action === 'reload' ? 'Reload' : 'Try again'}
              </button>
            )}
          </p>
        )}
        {timeline?.partial && (
          <p data-look-part="trimmed-note" className="text-sm text-warning">
            Its state at this moment is unknown: its events before it were trimmed.
          </p>
        )}
        {/* Two columns on a wide dock, one below 1,024 px (S-14). D-7's order
            reads down the first column, then the events. */}
        {!problem && problemsCut && (
          <p data-testid="problem-not-listed" className="text-sm text-default-500">
            Problem details: not among the problems the health lists (it lists the first 50).
          </p>
        )}
        <div className="grid gap-3 lg:grid-cols-2">
          <div className="min-w-0 space-y-3">
            {problem?.kind === 'FAILED' ? (
              // A failure (T-3): the exception first, then where, how long, on what, which one.
              <>
                <ProblemCards problem={problem} asOf={problemAsOf} />
                {showTimeline && (
                  <>
                    <SuspendedAtCard sessionId={sessionId} coroutineId={coroutine.id} />
                    <TimingCard timeline={timeline} lifetime={coroutine} />
                    {timeline?.eventsTrimmed && <SummaryCard coroutineId={coroutine.id} />}
                    <RunsOnCard runsOn={runsOn} title={moment ? 'Last observed on' : undefined} />
                  </>
                )}
              </>
            ) : problem?.kind === 'POSSIBLY_BLOCKED' ? (
              // Possibly blocked (T-3): where it was observed and the clue, from the health; then its transitions.
              <>
                <ProblemCards problem={problem} asOf={problemAsOf} />
                {showTimeline && (
                  <>
                    <TimingCard timeline={timeline} blocked={problem.incident} captureStartAppNanos={captureStartAppNanos} lifetime={coroutine} />
                    {timeline?.eventsTrimmed && <SummaryCard coroutineId={coroutine.id} />}
                  </>
                )}
              </>
            ) : (
              showTimeline && (
                <>
                  <TimingCard timeline={timeline} lifetime={coroutine} />
                  {timeline?.eventsTrimmed && <SummaryCard coroutineId={coroutine.id} />}
                  <SuspendedAtCard sessionId={sessionId} coroutineId={coroutine.id} />
                  <RunsOnCard runsOn={runsOn} title={moment ? 'Last observed on' : undefined} />
                </>
              )
            )}
            <IdentityCard coroutine={coroutine} />
          </div>
          {showTimeline && (
            <div className="min-w-0">
              <EventsCard timeline={timeline} />
            </div>
          )}
        </div>
      </div>
    </>
  )
}

/** The cards a problem leads with (T-3): the exception of a failure; where a possibly blocked coroutine was observed and its clue. */
function ProblemCards({ problem, asOf }: { problem: CoroutineProblem; asOf?: string }) {
  if (problem.kind === 'FAILED') return <ExceptionCard failure={problem.failure} round={problem.round} asOf={asOf} />
  return (
    <>
      <ObservedCard incident={problem.incident} asOf={asOf} />
      {problem.incident.eventLoopClue && <ClueCard clue={problem.incident.eventLoopClue} />}
    </>
  )
}

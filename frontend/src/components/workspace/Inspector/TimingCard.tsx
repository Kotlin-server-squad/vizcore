import { formatDuration } from '@/lib/format-duration'
import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { CoroutineTimeline } from '@/types/api'
import { lifetimeText } from '@/lib/card-facts'
import type { PossiblyBlockedIncident, TransitionKind } from '@/types/health'

/**
 * The lifetime the timeline shows so far, for a coroutine that has not
 * finished (#145). The backend reports `totalDuration` only on completion, and
 * its active/suspended figures count CLOSED intervals only; the span from the
 * first to the last recorded event is the most the client can say from the
 * events alone. Event timestamps are the app JVM's monotonic clock, so they are
 * compared with each other only — never with the browser's clock.
 */
export function lifetimeSoFar(timeline: CoroutineTimeline): number | null {
  const stamps = timeline.events.map(e => e.tsNanos).filter(t => Number.isFinite(t))
  const span = stamps.length >= 2 ? Math.max(...stamps) - Math.min(...stamps) : null
  const closed =
    timeline.activeDuration != null && timeline.suspendedDuration != null
      ? timeline.activeDuration + timeline.suspendedDuration
      : null
  if (span === null && closed === null) return null
  return Math.max(span ?? 0, closed ?? 0)
}

/**
 * How long this coroutine has been alive, working, and waiting.
 *
 * Every duration is `~`-prefixed: the backend samples on a poll, so these are
 * bounded by the poll interval rather than exact (validated sketch 001-C). A
 * coroutine still running reads "at least ~N": the span of its recorded events,
 * which cannot reach to the app's latest poll (triage "Lifetimes"). Nothing counts on with the browser's clock (#232's
 * estimate is gone, Part 7). A possibly blocked coroutine shows its observed
 * transitions from the health (triage T-3), a list rather than a colour bar.
 */
export function TimingCard({
  timeline,
  blocked,
  captureStartAppNanos,
  lifetime,
}: {
  timeline: CoroutineTimeline | undefined
  /** A possibly blocked coroutine's incident: its transitions are listed. */
  blocked?: PossiblyBlockedIncident
  /** App-clock time of the session's first event, the origin of "at 2.0 s". */
  captureStartAppNanos?: number | null
  /** The server's lifetime for it (triage "Lifetimes", Part 7), as its card shows it; preferred when given. */
  lifetime?: { lifetimeNanos?: number | null; lifetimeKind?: 'FINISHED' | 'SO_FAR' | 'AT_LEAST' | null }
}) {
  const serverLifetime = lifetime ? lifetimeText(lifetime) : null
  // A timeline at a moment built on a durable summary (Part 8, M-10): what is left is not the whole life, so no
  // duration from it.
  const trimmed = !!timeline?.eventsTrimmed
  const unfinished = !!timeline && timeline.totalDuration == null && !trimmed
  const soFar = unfinished && timeline ? lifetimeSoFar(timeline) : null

  if (trimmed) {
    return (
      <InspectorCard title="Timing" testId="timing-card">
        <p className="text-sm text-default-500">Unavailable: early events were trimmed.</p>
        {blocked && <Transitions incident={blocked} captureStartAppNanos={captureStartAppNanos ?? null} />}
      </InspectorCard>
    )
  }

  return (
    <InspectorCard title="Timing" testId="timing-card">
      <InspectorRow
        label="Total"
        value={
          serverLifetime ? (
            <span title="From its first observation, on the app's clock (the server's summary)">{serverLifetime}</span>
          ) : soFar !== null ? (
            <span title="Still running: from its first to its latest recorded event; it may have run longer since">
              at least ~{formatDuration(soFar)}
            </span>
          ) : (
            <Approx nanos={timeline?.totalDuration} />
          )
        }
      />
      <InspectorRow label="Active" value={<Approx nanos={timeline?.activeDuration} />} />
      <InspectorRow label="Suspended" value={<Approx nanos={timeline?.suspendedDuration} />} />
      {blocked && <Transitions incident={blocked} captureStartAppNanos={captureStartAppNanos ?? null} />}
      <p className="mt-2 text-xs text-default-400">
        Approximate — sampling is poll-bounded.
      </p>
    </InspectorCard>
  )
}

const TRANSITION_WORD: Record<TransitionKind, string> = {
  CREATED: 'Created',
  STARTED: 'Started',
  SUSPENDED: 'Suspended',
  RESUMED: 'Resumed',
  BODY_COMPLETED: 'Body completed',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  FAILED: 'Failed',
}

/** The observed transitions of a possibly blocked coroutine (T-3), newest last, as times into the capture. */
export function Transitions({ incident, captureStartAppNanos }: { incident: PossiblyBlockedIncident; captureStartAppNanos: number | null }) {
  const at = (t: { appNanos: number; seq: number }) =>
    captureStartAppNanos != null ? `at ${formatDuration(t.appNanos - captureStartAppNanos)}` : `at event ${t.seq}`
  return (
    <div data-testid="transitions" className="mt-2 space-y-1 border-t border-default-200 pt-2 text-sm">
      <p>Running for {formatDuration(incident.runningForNanos)} with no suspension observed</p>
      {incident.firstObservedRunning && <p className="text-default-500">No suspension was ever observed for it.</p>}
      {incident.transitionsGap && <p className="text-default-500">Earlier transitions were not retained.</p>}
      <ol className="space-y-0.5 text-xs text-default-600">
        {incident.transitions.map(t => (
          <li key={t.seq}>
            {TRANSITION_WORD[t.kind]} {at(t)}
          </li>
        ))}
      </ol>
    </div>
  )
}

function Approx({ nanos }: { nanos: number | null | undefined }) {
  if (nanos === null || nanos === undefined) return <NotReported />
  return <>~{formatDuration(nanos)}</>
}

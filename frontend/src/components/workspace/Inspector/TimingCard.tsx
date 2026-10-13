import { formatNanoTime } from '@/lib/utils'
import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { CoroutineTimeline } from '@/types/api'

/**
 * The lifetime the timeline shows so far, for a coroutine that has not
 * finished (#145). The backend reports `totalDuration` only on completion, and
 * its active/suspended figures count CLOSED intervals only; the span from the
 * first to the last recorded event is the most the client can truthfully say.
 * Event timestamps are the app JVM's monotonic clock, so they are compared with
 * each other only — never with the browser's clock.
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
 * coroutine still running gets "~N so far" instead of "not reported" (#145).
 */
export function TimingCard({ timeline }: { timeline: CoroutineTimeline | undefined }) {
  const soFar =
    timeline && timeline.totalDuration == null ? lifetimeSoFar(timeline) : null
  return (
    <InspectorCard title="Timing" testId="timing-card">
      <InspectorRow
        label="Total"
        value={
          soFar !== null ? (
            <span title="Still running: time from its first to its latest recorded event">
              ~{formatNanoTime(soFar)} so far
            </span>
          ) : (
            <Approx nanos={timeline?.totalDuration} />
          )
        }
      />
      <InspectorRow label="Active" value={<Approx nanos={timeline?.activeDuration} />} />
      <InspectorRow label="Suspended" value={<Approx nanos={timeline?.suspendedDuration} />} />
      <p className="mt-2 text-xs text-default-400">
        Approximate — sampling is poll-bounded.
      </p>
    </InspectorCard>
  )
}

function Approx({ nanos }: { nanos: number | null | undefined }) {
  if (nanos === null || nanos === undefined) return <NotReported />
  return <>~{formatNanoTime(nanos)}</>
}

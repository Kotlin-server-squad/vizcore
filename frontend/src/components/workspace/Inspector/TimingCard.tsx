import { formatNanoTime } from '@/lib/utils'
import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { CoroutineTimeline } from '@/types/api'

/**
 * How long this coroutine has been alive, working, and waiting.
 *
 * Every duration is `~`-prefixed: the backend samples on a poll, so these are
 * bounded by the poll interval rather than exact (validated sketch 001-C).
 */
export function TimingCard({ timeline }: { timeline: CoroutineTimeline | undefined }) {
  return (
    <InspectorCard title="Timing" testId="timing-card">
      <InspectorRow label="Total" value={<Approx nanos={timeline?.totalDuration} />} />
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

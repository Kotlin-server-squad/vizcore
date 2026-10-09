import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { RunsOn } from '@/lib/runs-on'

/**
 * Which thread and dispatcher this coroutine is on — the per-coroutine half of
 * what the Threads tab used to answer (the aggregate half is the metric tiles).
 *
 * Fed from thread activity rather than the coroutine timeline: the timeline
 * projection is source-only (D-02) and carries no thread or dispatcher name, so
 * this card reported "not reported" against a live backend that had the data.
 * Resolution — including "newest assignment wins" and "a release means it is no
 * longer there" — lives in `resolveRunsOn`.
 */
export function RunsOnCard({ runsOn }: { runsOn: RunsOn | null }) {
  return (
    <InspectorCard title="Runs on" testId="runs-on-card">
      <InspectorRow label="Thread" value={runsOn?.threadName ?? <NotReported />} />
      <InspectorRow label="Dispatcher" value={runsOn?.dispatcherName ?? <NotReported />} />
    </InspectorCard>
  )
}

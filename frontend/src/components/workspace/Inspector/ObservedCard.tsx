import { InspectorCard, InspectorRow } from './InspectorCard'
import type { PossiblyBlockedIncident } from '@/types/health'

/**
 * Where a possibly blocked coroutine was observed (triage T-3, "as Observed"), from the health's incident only: the
 * thread of its current run (the backend keeps it only when assigned after the latest start or resume), its last known
 * dispatcher, and its last suspension point only when the SDK read the frame (provenance `REAL_FRAME`).
 */
export function ObservedCard({ incident, asOf }: { incident: PossiblyBlockedIncident; asOf?: string }) {
  const point = incident.lastSuspensionProvenance === 'REAL_FRAME' ? incident.lastSuspension : null
  const where = point
    ? point.fileName
      ? `${point.fileName}${point.lineNumber != null ? `:${point.lineNumber}` : ''}`
      : point.function
    : null
  return (
    <InspectorCard title="Observed" testId="observed-card">
      <InspectorRow
        label="Thread"
        value={incident.lastThread?.threadName ?? <span className="italic text-default-400">not observed for the current run</span>}
      />
      <InspectorRow label="Dispatcher" value={incident.dispatcher ?? <span className="italic text-default-400">not reported</span>} />
      <InspectorRow
        label="Last suspension point"
        value={where ? <span title={point?.function}>{where}</span> : <span className="italic text-default-400">suspension location unavailable</span>}
      />
      {asOf && <p className="mt-1 text-xs text-default-400">From the session health, {asOf}.</p>}
    </InspectorCard>
  )
}

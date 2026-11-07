import { InspectorCard, InspectorRow } from './InspectorCard'
import type { FailureEntry } from '@/types/health'

/**
 * What failed (triage T-3, first card for a failure): the exception as the health recorded it and, for a failed round,
 * what else happened in that round. Cancellations after the first failure are context, never a cause (H-1).
 */
export function ExceptionCard({
  failure,
  round,
  asOf,
}: {
  failure: FailureEntry
  /** Its failed round's figures; null for an ungrouped failure. */
  round: { failed: number; cancelledAfter: number } | null
  /** The health's "as of" these facts come from (H-2). */
  asOf?: string
}) {
  const n = (v: number) => v.toLocaleString('en-US')
  return (
    <div data-look-part="exception-card">
      <InspectorCard title="Exception" testId="exception-card">
        {failure.detailsDropped ? (
          <p className="text-sm text-default-500">Details dropped: this session kept only a summary of the failure.</p>
        ) : (
          <>
            <InspectorRow label="Type" value={failure.exceptionType ?? 'not reported'} />
            <p className="mt-1 text-sm [overflow-wrap:anywhere]">{failure.message ?? <span className="italic text-default-400">no message</span>}</p>
          </>
        )}
        <p className="mt-2 text-xs text-default-500">
          {round
            ? `1 of ${n(round.failed)} failed ${round.failed === 1 ? 'coroutine' : 'coroutines'} in this round · ${n(round.cancelledAfter)} cancelled after the first failure (context, not a cause)`
            : 'Round unavailable: its ancestry could not be resolved.'}
        </p>
        {asOf && <p className="mt-1 text-xs text-default-400">From the session health, {asOf}.</p>}
      </InspectorCard>
    </div>
  )
}

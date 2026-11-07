import { SessionMetrics } from '../SessionMetrics'
import { MomentNote, type Moment } from './MomentNote'
import type { MomentMetrics } from '@/lib/moment-metrics'

/**
 * What the Inspector shows with nothing selected outside the live view (Part 8, M-1, M-6): the session's metric tiles
 * at the moment the page shows, computed from the same coroutines and events as the tree, with what they cannot say
 * named rather than shown as zero. Fetches nothing.
 */
export function MomentSummary({
  metrics,
  moment,
  coroutineCount,
  incomplete,
}: {
  metrics: MomentMetrics
  moment: Moment
  /** The coroutines the tree holds at this moment. */
  coroutineCount: number
  /** The events the page holds do not start at the session's beginning (trimmed, capped or cleared). */
  incomplete: boolean
}) {
  return (
    <div data-testid="moment-summary" className="space-y-4">
      <MomentNote moment={moment} />
      <p className="text-sm text-default-400">Select a coroutine to inspect it</p>
      <p className="text-sm">
        {coroutineCount === 1 ? '1 coroutine' : `${coroutineCount} coroutines`} at this moment
        {metrics.unknown > 0 && (
          <span className="text-default-500"> · {metrics.unknown} with their state unknown</span>
        )}
      </p>
      <SessionMetrics
        sessionId=""
        enabled={false}
        data={{ ...metrics, leaks: [], leakThresholdMs: 0 }}
      />
      <ul className="space-y-1 text-xs text-default-500">
        <li>
          Peak and throughput are counted{' '}
          {incomplete ? 'in the kept events only: earlier events were trimmed' : 'over the events up to this moment'},
          throughput on the app&apos;s clock (events in the 10 s before it).
        </li>
        <li>Long-lived coroutines are not evaluated at a moment.</li>
      </ul>
    </div>
  )
}

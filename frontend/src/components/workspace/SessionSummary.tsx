import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { SessionMetrics } from '../SessionMetrics'
import { LeakList } from '../LeakList'

/**
 * What the Inspector shows with nothing selected (triage T-3): the session's
 * metric tiles and its long-lived coroutines. Live view only — in replay the
 * numbers would describe now over a frozen view, and the shared view has no
 * Bearer for /metrics (M-4). The leak list renders once, from the same query
 * the tiles use (React Query dedupes the key; PD-02). The app/vizcore split,
 * and "in retained events since" once history is trimmed, join it with the
 * health payload in Part 7.
 */
export function SessionSummary({
  sessionId,
  enabled,
  coroutineCount,
  eventCount,
}: {
  sessionId: string
  enabled: boolean
  /** From the session snapshot: everything captured, vizcore's own connection included. */
  coroutineCount: number
  eventCount: number
}) {
  // A pure observer: the workspace owns the refresh cadence (#124).
  const { data: metrics } = useSessionMetrics(sessionId, false, enabled)
  return (
    <div data-testid="session-summary" className="space-y-4">
      <p className="text-sm text-default-400">Select a coroutine to inspect it</p>
      {coroutineCount === 0 ? (
        // H-4: where a user first meets an empty result, say how capture works.
        <p data-testid="session-captured" className="text-sm">
          Nothing captured yet.{' '}
          <span className="text-default-500">
            vizcore samples an attached app's coroutines on a poll, so one that starts and ends between two polls may not appear.
          </span>
        </p>
      ) : (
        <p data-testid="session-captured" className="text-sm">
          {coroutineCount} coroutines and {eventCount} events captured
          <span className="text-default-500">
            {' '}
            — what vizcore captured; which are your app's and which its own connection's arrives with the
            health API (Part 7).
          </span>
        </p>
      )}
      <SessionMetrics sessionId={sessionId} enabled={enabled} showLeaks={false} />
      {metrics && metrics.leaks.length > 0 && (
        <LeakList leaks={metrics.leaks} leakThresholdMs={metrics.leakThresholdMs} />
      )}
    </div>
  )
}

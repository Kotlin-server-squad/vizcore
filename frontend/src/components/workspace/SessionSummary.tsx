import { SessionMetrics } from '../SessionMetrics'

/**
 * What the Inspector shows with nothing selected (triage T-3): what was
 * captured, split by origin from the session health, and the session's metric
 * tiles. Live view only — in replay the numbers would describe now over a
 * frozen view, and the shared view has no Bearer for /metrics (M-4). Long-lived
 * coroutines are an observation in the Problems tab (Part 7, T-5).
 */
export function SessionSummary({
  sessionId,
  enabled,
  coroutineCount,
  eventCount,
  counts,
  captureStartAppNanos = null,
}: {
  sessionId: string
  enabled: boolean
  /** From the session snapshot: everything captured, vizcore's own connection included. */
  coroutineCount: number
  eventCount: number
  /** The health's counts by effective origin; absent until it is read. */
  counts?: { app: number; vizcore: number; unknown: number; untracked: number }
  captureStartAppNanos?: number | null
}) {
  const n = (v: number) => v.toLocaleString('en-US')
  const split = counts
    ? [
        `${n(counts.app)} your app's`,
        ...(counts.vizcore > 0 ? [`${n(counts.vizcore)} vizcore's own connection`] : []),
        ...(counts.unknown > 0 ? [`${n(counts.unknown)} origin unknown`] : []),
      ].join(' · ')
    : null
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
          {split && <span className="text-default-500">: {split}</span>}
        </p>
      )}
      <SessionMetrics sessionId={sessionId} enabled={enabled} captureStartAppNanos={captureStartAppNanos} />
    </div>
  )
}

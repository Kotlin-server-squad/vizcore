import { Chip } from '@heroui/react'
import { clockTime, type ClockOptions } from '@/lib/health-text'
import type { SessionSnapshot } from '@/types/api'

/**
 * The app connection and its freshness (S-3, triage T-2), for an attached app: whether its ingest socket is open now,
 * when its last data was observed — on the app's own clock (its last poll watermark), else when the backend received
 * it, labelled so — and when the session started. The browser's clock is never read for these.
 */
export function ConnectionFacts({
  session,
  clock,
}: {
  session: Pick<SessionSnapshot, 'appConnected' | 'startedAtEpochMs' | 'lastIngestEpochMs' | 'lastWatermark'>
  clock?: ClockOptions
}) {
  if (session.appConnected === undefined) return null
  const lastData = session.lastWatermark?.appEpochMs
    ? `Last data ${clockTime(session.lastWatermark.appEpochMs, clock)}`
    : session.lastIngestEpochMs
      ? `Last data received ${clockTime(session.lastIngestEpochMs, clock)}`
      : 'No data yet'
  const started = session.startedAtEpochMs ? ` · started ${clockTime(session.startedAtEpochMs, clock)}` : ''
  return (
    <>
      <Chip
        size="sm"
        variant="dot"
        color={session.appConnected ? 'success' : 'default'}
        data-testid="connection"
        data-look-part="connection"
      >
        {session.appConnected ? 'App connected' : 'App disconnected'}
      </Chip>
      <span
        data-testid="connection-facts"
        data-look-part="connection-facts"
        className="text-sm text-default-500"
        title="On the app's clock when it reports one; otherwise when vizcore received it"
      >
        {lastData}
        {started}
      </span>
    </>
  )
}

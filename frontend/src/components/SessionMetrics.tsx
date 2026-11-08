import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { FiActivity, FiTrendingUp, FiZap, FiCpu } from 'react-icons/fi'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { formatDuration } from '@/lib/format-duration'
import type { MetricsResponse } from '@/types/api'

interface SessionMetricsProps {
  sessionId: string
  /**
   * Read-only shared view (T-08-08): disables the protected /metrics fetch/poll.
   * The shared shell carries no Bearer, so this query would 401/poll noisily.
   */
  enabled?: boolean
  /** App-clock time of the session's first event, for "in retained events since <t>" (T-3). */
  captureStartAppNanos?: number | null
  /**
   * The metrics to show instead of fetching them: the metrics at a replay moment or of a shared link's loaded
   * snapshot (Part 8, M-1). Nothing is fetched when given.
   */
  data?: MetricsResponse
}

/**
 * The "Session metrics" panel (RCO-07, D-06/D-07). Delta L1 reflows the tiles
 * into a single horizontal strip (flex-wrap) of Active / Peak / Throughput /
 * Dispatcher utilization. Fed by useSessionMetrics as a pure observer: it
 * never polls itself — the workspace owns the refresh cadence (#124). Live, the
 * figures are the app's own (`byOrigin.app`, T-3) when origins are reported;
 * long-lived coroutines are an observation in the Problems tab (Part 7, T-5).
 */
export function SessionMetrics({
  sessionId,
  enabled = true,
  captureStartAppNanos = null,
  data: given,
}: SessionMetricsProps) {
  const fetched = useSessionMetrics(sessionId, false, enabled && !given)
  const data = given ?? fetched.data
  const isLoading = !given && fetched.isLoading

  if (isLoading) {
    return (
      <Card>
        <CardBody>
          <div className="text-center text-default-400 py-4">Loading metrics...</div>
        </CardBody>
      </Card>
    )
  }

  if (!data) {
    return (
      <Card>
        <CardBody>
          <div className="text-center text-default-400 py-4">No metrics available</div>
        </CardBody>
      </Card>
    )
  }

  // Live: the app's own figures when every coroutine's origin is known (T-3); otherwise all captured, said so.
  const byOrigin = given ? null : data.byOrigin
  const appOnly = !!byOrigin && byOrigin.unknown.captured === 0
  const shown = appOnly
    ? {
        active: byOrigin.app.active,
        peak: byOrigin.app.peak,
        throughputPerSec: byOrigin.app.throughputPerSec,
        dispatcherUtilization: byOrigin.app.dispatcherUtilization,
      }
    : data
  const dispatcherEntries = Object.entries(shown.dispatcherUtilization)
  const retainedSince =
    !given && data.retainedOnly
      ? data.oldestRetainedAppNanos != null && captureStartAppNanos != null
        ? `${formatDuration(data.oldestRetainedAppNanos - captureStartAppNanos)} into the capture`
        : 'the oldest retained event'
      : null

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">Session metrics</h3>
      {!given && (
        <p data-testid="metrics-scope" className="text-xs text-default-500">
          {appOnly
            ? "Your app's coroutines; vizcore's own connection is left out."
            : "All captured coroutines (origin not available: vizcore's own connection may be included)."}
        </p>
      )}
      {retainedSince && (
        <p data-testid="metrics-retained" className="text-xs text-default-500">
          Peak and throughput: in retained events since {retainedSince}.
        </p>
      )}

      {/* Delta L1: a single horizontal tile strip (wrap allowed on narrow
          widths) — Active · Peak · Throughput /s · Dispatcher util — replacing
          the old two-column card grid (UI-SPEC line 124). */}
      <div className="flex flex-wrap gap-4">
        {/* Active — uses the primary accent (UI-SPEC accent reserved list) */}
        <MetricCard
          icon={<FiActivity className="w-4 h-4 text-primary" />}
          label="Active"
          value={<span className="text-lg font-semibold text-primary">{shown.active}</span>}
        />

        {/* Peak — neutral metric numeral */}
        <MetricCard
          icon={<FiTrendingUp className="w-4 h-4 text-default-600" />}
          label="Peak"
          value={<span className="text-lg font-semibold">{shown.peak}</span>}
        />

        {/* Throughput — "—" em-dash when zero/no events, else "N /s" */}
        <MetricCard
          icon={<FiZap className="w-4 h-4 text-default-600" />}
          label="Throughput"
          value={
            shown.throughputPerSec === null ? (
              <span className="text-sm text-default-400">unavailable</span>
            ) : shown.throughputPerSec > 0 ? (
              <span>
                <span className="text-lg font-semibold">{formatThroughput(shown.throughputPerSec)}</span>
                <span className="text-sm text-default-500"> /s</span>
              </span>
            ) : (
              <span className="text-lg font-semibold text-default-400">—</span>
            )
          }
        />

        {/* Dispatcher utilization — active coroutines grouped by dispatcher */}
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2 text-sm text-default-600">
              <FiCpu className="w-4 h-4" />
              <span>Dispatcher utilization</span>
            </div>
          </CardHeader>
          <CardBody>
            {dispatcherEntries.length === 0 ? (
              <div className="text-xs text-default-400">No dispatcher activity</div>
            ) : (
              <div className="flex flex-wrap gap-1">
                {dispatcherEntries.map(([name, count]) => (
                  <Chip key={name} size="sm" variant="flat">
                    {name}: {count}
                  </Chip>
                ))}
              </div>
            )}
          </CardBody>
        </Card>
      </div>

    </div>
  )
}

/**
 * One metric tile: its label and value as a term and its definition. The gap
 * keeps them apart — the card shrinks to its content, so a `justify-between`
 * row alone left none ("Active0").
 */
function MetricCard({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode
  label: string
  value: React.ReactNode
}) {
  return (
    <Card>
      <CardBody>
        <dl className="flex items-center justify-between gap-3">
          <dt className="flex items-center gap-2 text-sm text-default-600">
            {icon}
            <span>{label}</span>
          </dt>
          <dd>{value}</dd>
        </dl>
      </CardBody>
    </Card>
  )
}

/** Throughput numeral: one decimal place, trimming a trailing ".0". */
function formatThroughput(perSec: number): string {
  const rounded = Math.round(perSec * 10) / 10
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
}

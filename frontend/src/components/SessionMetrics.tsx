import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { FiActivity, FiTrendingUp, FiZap, FiCpu, FiAlertCircle } from 'react-icons/fi'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { LeakList } from './LeakList'

interface SessionMetricsProps {
  sessionId: string
  /**
   * Forwarded to useSessionMetrics: while the SSE stream drives the live view,
   * polling falls back to the slow 5s interval (mirrors DispatcherOverview).
   */
  isLive?: boolean
  /**
   * Read-only shared view (T-08-08): disables the protected /metrics fetch/poll.
   * The shared shell carries no Bearer, so this query would 401/poll noisily.
   */
  enabled?: boolean
  /**
   * Render the internal "Potential leaks" Card (default true). Set false when
   * the leaks are mounted separately (e.g. the LiveDockPanel header strip is
   * tiles-only and the dock's left column owns the single LeakList mount,
   * PD-02/PD-04a). Additive — every existing call site omits it and keeps the
   * shipped behavior unchanged.
   */
  showLeaks?: boolean
}

/**
 * The "Session metrics" panel (RCO-07, D-06/D-07). Delta L1 reflows the tiles
 * into a single horizontal strip (flex-wrap) of Active / Peak / Throughput /
 * Dispatcher utilization, plus a Potential-leaks card backed by LeakList. Fed
 * by useSessionMetrics (poll-while-live).
 */
export function SessionMetrics({
  sessionId,
  isLive = false,
  enabled = true,
  showLeaks = true,
}: SessionMetricsProps) {
  const { data, isLoading } = useSessionMetrics(sessionId, isLive, enabled)

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

  const dispatcherEntries = Object.entries(data.dispatcherUtilization)

  return (
    <div className="space-y-4">
      <h3 className="text-lg font-semibold">Session metrics</h3>

      {/* Delta L1: a single horizontal tile strip (wrap allowed on narrow
          widths) — Active · Peak · Throughput /s · Dispatcher util — replacing
          the old two-column card grid (UI-SPEC line 124). */}
      <div className="flex flex-wrap gap-4">
        {/* Active — uses the primary accent (UI-SPEC accent reserved list) */}
        <MetricCard
          icon={<FiActivity className="w-4 h-4 text-primary" />}
          label="Active"
          value={<span className="text-lg font-semibold text-primary">{data.active}</span>}
        />

        {/* Peak — neutral metric numeral */}
        <MetricCard
          icon={<FiTrendingUp className="w-4 h-4 text-default-600" />}
          label="Peak"
          value={<span className="text-lg font-semibold">{data.peak}</span>}
        />

        {/* Throughput — "—" em-dash when zero/no events, else "N /s" */}
        <MetricCard
          icon={<FiZap className="w-4 h-4 text-default-600" />}
          label="Throughput"
          value={
            data.throughputPerSec > 0 ? (
              <span>
                <span className="text-lg font-semibold">{formatThroughput(data.throughputPerSec)}</span>
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

      {/* Potential leaks (D-07) — warning highlight, never danger. Suppressed
          when showLeaks={false} so the LiveDockPanel header strip is tiles-only
          and the dock's left column owns the single LeakList mount (PD-04a). */}
      {showLeaks && (
        <Card>
          <CardHeader>
            <div className="flex items-center gap-2 text-sm text-default-600">
              <FiAlertCircle className="w-4 h-4" />
              <span>Potential leaks</span>
            </div>
          </CardHeader>
          <CardBody>
            {data.leaks.length === 0 ? (
              <div className="text-xs text-default-400">No potential leaks detected</div>
            ) : (
              <LeakList leaks={data.leaks} leakThresholdMs={data.leakThresholdMs} />
            )}
          </CardBody>
        </Card>
      )}
    </div>
  )
}

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
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-sm text-default-600">
            {icon}
            <span>{label}</span>
          </div>
          {value}
        </div>
      </CardBody>
    </Card>
  )
}

/** Throughput numeral: one decimal place, trimming a trailing ".0". */
function formatThroughput(perSec: number): string {
  const rounded = Math.round(perSec * 10) / 10
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
}

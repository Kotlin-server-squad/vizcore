import { Card, CardBody, CardHeader, Chip, Tooltip } from '@heroui/react'
import { useCoroutineTimeline, useTimelineStats, useSuspensionPoints } from '@/hooks/use-timeline'
import { formatNanoTime } from '@/lib/utils'
import { isUserFrame } from '@/lib/source-frames'
import { toastSuccess } from '@/lib/toast'
import { FiClock, FiActivity, FiPause, FiCpu, FiMapPin } from 'react-icons/fi'
import type { TimelineEvent } from '@/types/api'

/**
 * Delta S1 jump-to-code (v1, LOCKED): copy `{fileName}:{lineNumber}` to the
 * clipboard and confirm with a success toast.
 */
function copyFileLine(fileName: string, lineNumber: number | string): void {
  const target = `${fileName}:${lineNumber}`
  void navigator.clipboard?.writeText(target)
  toastSuccess(`Copied ${target}`)
}

/**
 * Renders a suspension point's `file:line`. User-code frames (per `isUserFrame`)
 * become an accent jump-to-code target wrapped in a "Copy file:line" tooltip;
 * library frames keep the call site's existing dimmed `text-default-500` text.
 */
function TimelineSourceRef({
  fn,
  fileName,
  lineNumber,
}: {
  fn: string | null | undefined
  fileName: string
  lineNumber?: number | null
}) {
  const label = lineNumber ? `${fileName}:${lineNumber}` : fileName

  if (isUserFrame(fn)) {
    return (
      <Tooltip content="Copy file:line">
        <span
          role="button"
          tabIndex={0}
          aria-label={`Jump to code: ${label}`}
          className="font-semibold text-primary cursor-pointer hover:underline"
          onClick={() => copyFileLine(fileName, lineNumber!)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              copyFileLine(fileName, lineNumber!)
            }
          }}
        >
          {label}
        </span>
      </Tooltip>
    )
  }

  return <>{label}</>
}

interface CoroutineTimelineViewProps {
  sessionId: string
  coroutineId: string
}

export function CoroutineTimelineView({ sessionId, coroutineId }: CoroutineTimelineViewProps) {
  const { data: timeline, isLoading } = useCoroutineTimeline(sessionId, coroutineId)
  const stats = useTimelineStats(timeline)
  const suspensionPoints = useSuspensionPoints(sessionId, coroutineId)

  if (isLoading) {
    return (
      <Card>
        <CardBody>
          <div className="flex items-center justify-center py-8">
            <div className="text-center">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary mx-auto mb-2" />
              <p className="text-sm text-default-500">Loading timeline...</p>
            </div>
          </div>
        </CardBody>
      </Card>
    )
  }

  if (!timeline) {
    return (
      <Card>
        <CardBody>
          <div className="text-center text-default-400 py-8">
            No timeline data available
          </div>
        </CardBody>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      {/* Timeline Statistics */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between w-full">
            <h3 className="text-lg font-semibold">Timeline: {timeline.name || timeline.coroutineId}</h3>
            <Chip size="sm" variant="flat" color="primary">
              {timeline.events.length} events
            </Chip>
          </div>
        </CardHeader>
        <CardBody>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-6">
            <StatCard
              icon={<FiClock />}
              label="Total Duration"
              value={formatNanoTime(stats.totalDuration)}
              color="default"
            />
            <StatCard
              icon={<FiActivity />}
              label="Active Time"
              value={`${stats.activePercent.toFixed(1)}%`}
              color="success"
              subtitle={formatNanoTime(stats.activeTime)}
            />
            <StatCard
              icon={<FiPause />}
              label="Suspended Time"
              value={`${stats.suspendedPercent.toFixed(1)}%`}
              color="warning"
              subtitle={formatNanoTime(stats.suspendedTime)}
            />
            <StatCard
              icon={<FiCpu />}
              label="Suspensions"
              value={stats.suspensionCount.toString()}
              color="secondary"
              subtitle={`${stats.dispatcherSwitches} dispatcher switches`}
            />
          </div>

          {/* Active/Suspended Time Bar */}
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-default-600">Time Distribution</span>
              <span className="text-default-400">{formatNanoTime(stats.totalDuration)}</span>
            </div>
            <div className="flex h-4 rounded-full overflow-hidden bg-default-100">
              <div
                className="bg-success transition-all"
                style={{ width: `${stats.activePercent}%` }}
                title={`Active: ${stats.activePercent.toFixed(1)}%`}
              />
              <div
                className="bg-warning transition-all"
                style={{ width: `${stats.suspendedPercent}%` }}
                title={`Suspended: ${stats.suspendedPercent.toFixed(1)}%`}
              />
            </div>
            <div className="flex justify-between text-xs text-default-500">
              <span>Active: {formatNanoTime(stats.activeTime)}</span>
              <span>Suspended: {formatNanoTime(stats.suspendedTime)}</span>
            </div>
          </div>
        </CardBody>
      </Card>

      {/* Suspension Points */}
      {suspensionPoints.length > 0 && (
        <Card>
          <CardHeader>
            <h4 className="font-semibold flex items-center gap-2">
              <FiMapPin />
              Suspension Points
            </h4>
          </CardHeader>
          <CardBody>
            <div className="space-y-2">
              {suspensionPoints.map((point, i) => (
                <div key={i} className="flex items-start gap-3 p-3 bg-default-50 rounded-lg">
                  <Chip size="sm" variant="flat" color="secondary">
                    {point.reason}
                  </Chip>
                  <div className="flex-1">
                    <div className="font-mono text-sm font-semibold">{point.function}</div>
                    {point.fileName && (
                      <div className="text-xs text-default-500">
                        <TimelineSourceRef
                          fn={point.function}
                          fileName={point.fileName}
                          lineNumber={point.lineNumber}
                        />
                      </div>
                    )}
                    {point.duration && (
                      <div className="text-xs text-default-400 mt-1">
                        Duration: {formatNanoTime(point.duration)}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </CardBody>
        </Card>
      )}

      {/* Timeline Events */}
      <Card>
        <CardHeader>
          <h4 className="font-semibold">Timeline Events</h4>
        </CardHeader>
        <CardBody>
          <div className="space-y-2">
            {timeline.events.map((event, i) => (
              <TimelineEventCard key={event.seq} event={event} isLast={i === timeline.events.length - 1} />
            ))}
          </div>
        </CardBody>
      </Card>
    </div>
  )
}

function StatCard({
  icon,
  label,
  value,
  subtitle,
  color = 'default'
}: {
  icon: React.ReactNode
  label: string
  value: string
  subtitle?: string
  color?: 'default' | 'success' | 'warning' | 'secondary'
}) {
  const colorClasses = {
    default: 'text-default-600 bg-default-100',
    success: 'text-success bg-success/10',
    warning: 'text-warning bg-warning/10',
    secondary: 'text-secondary bg-secondary/10'
  }

  return (
    <div className="p-4 rounded-lg bg-default-50">
      <div className="flex items-center gap-3">
        <div className={`p-2 rounded-lg ${colorClasses[color]}`}>
          {icon}
        </div>
        <div className="flex-1 min-w-0">
          <div className="text-xs text-default-500 uppercase tracking-wide">{label}</div>
          <div className="text-xl font-bold truncate">{value}</div>
          {subtitle && <div className="text-xs text-default-400">{subtitle}</div>}
        </div>
      </div>
    </div>
  )
}

function TimelineEventCard({ event, isLast }: { event: TimelineEvent; isLast: boolean }) {
  const getEventColor = (kind: string) => {
    if (kind === 'coroutine.started' || kind === 'coroutine.resumed') return 'success'
    if (kind === 'coroutine.suspended') return 'warning'
    if (kind === 'coroutine.completed') return 'primary'
    if (kind === 'coroutine.failed' || kind === 'coroutine.cancelled') return 'danger'
    return 'default'
  }

  return (
    <div className="flex gap-3">
      {/* Timeline connector */}
      <div className="flex flex-col items-center">
        <div className={`w-3 h-3 rounded-full bg-${getEventColor(event.kind)} border-2 border-white shadow`} />
        {!isLast && <div className="w-0.5 flex-1 bg-default-200 min-h-[40px]" />}
      </div>

      {/* Event content */}
      <div className="flex-1 pb-4">
        <div className="flex items-start justify-between gap-4 mb-2">
          <div className="flex-1">
            <div className="flex items-center gap-2 mb-1">
              <span className="font-semibold text-sm">{event.kind}</span>
              <Chip size="sm" variant="dot" color={getEventColor(event.kind)}>
                #{event.seq}
              </Chip>
            </div>
            
            {/* Thread & Dispatcher Info */}
            {event.threadName && (
              <div className="flex items-center gap-2 text-xs text-default-600">
                <FiCpu className="w-3 h-3" />
                <span>{event.threadName}</span>
                {event.dispatcherName && (
                  <Chip size="sm" variant="flat" className="h-5">
                    {event.dispatcherName}
                  </Chip>
                )}
              </div>
            )}

            {/* Suspension Point Details */}
            {event.suspensionPoint && (
              <div className="mt-2 p-2 bg-warning/10 rounded border border-warning/20">
                <div className="text-xs space-y-1">
                  <div className="flex items-center gap-2">
                    <FiMapPin className="w-3 h-3 text-warning" />
                    <span className="font-mono font-semibold">{event.suspensionPoint.function}</span>
                    <Chip size="sm" variant="flat" color="warning">
                      {event.suspensionPoint.reason}
                    </Chip>
                  </div>
                  {event.suspensionPoint.fileName && (
                    <div className="text-default-500 ml-5">
                      <TimelineSourceRef
                        fn={event.suspensionPoint.function}
                        fileName={event.suspensionPoint.fileName}
                        lineNumber={event.suspensionPoint.lineNumber}
                      />
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

          <div className="text-xs text-default-400 text-right">
            {formatNanoTime(event.timestamp)}
            {event.duration && (
              <div className="text-warning font-semibold">
                +{formatNanoTime(event.duration)}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}


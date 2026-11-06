import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { Card, CardBody, Chip, Input, Tooltip } from '@heroui/react'
import { motion, AnimatePresence } from 'framer-motion'
import type { HTMLMotionProps } from 'framer-motion'
import type {
  VizEvent,
  JobStateChangedEvent,
  JobCancellationRequestedEvent,
  JobJoinRequestedEvent,
  DispatcherSelectedEvent,
  ThreadAssignedEvent,
  DeferredValueAvailableEvent,
  DeferredAwaitStartedEvent,
  DeferredAwaitCompletedEvent,
  CoroutineSuspendedEvent
} from '@/types/api'
import { formatNanoTime, formatRelativeTime } from '@/lib/utils'
import { useAnimatedInView } from '@/hooks/use-animated-in-view'
import { FiSearch, FiClock, FiServer, FiDatabase, FiMail, FiCloud, FiActivity } from 'react-icons/fi'
import { DispatcherBadge } from './DispatcherBadge'
import { eventTitle, type Selection } from '@/lib/selection'

interface EventsListProps {
  events: VizEvent[]
  /** What a row is about (S-2); null keeps that row presentational. */
  subjectOf?: (event: VizEvent) => Selection | null
  /** Selects what a row is about. Without it every row is presentational. */
  onSelect?: (selection: Selection) => void
  /** Resource id -> name, for rows about a channel whose creation is not in the list. */
  resourceNames?: ReadonlyMap<string, string>
}

const NO_NAMES: ReadonlyMap<string, string> = new Map()

/**
 * Parse a service label like "OrderService.processOrder" into parts
 */
function parseServiceLabel(label: string | null | undefined): { service: string; method: string; icon: React.ReactNode } | null {
  if (!label) return null

  // Check for common service patterns
  const dotMatch = label.match(/^([A-Za-z]+(?:Service|Repository|Api|Client|Generator|Processor|Validator)?)\.([\w-]+)$/)

  if (dotMatch) {
    const service = dotMatch[1] ?? ''
    const method = dotMatch[2] ?? ''
    return {
      service,
      method,
      icon: getServiceIcon(service)
    }
  }

  return null
}

/**
 * Get appropriate icon for service type
 */
function getServiceIcon(service: string): React.ReactNode {
  const serviceLower = service.toLowerCase()

  if (serviceLower.includes('email') || serviceLower.includes('sms') || serviceLower.includes('slack')) {
    return <FiMail className="w-3 h-3" />
  }
  if (serviceLower.includes('database') || serviceLower.includes('repository')) {
    return <FiDatabase className="w-3 h-3" />
  }
  if (serviceLower.includes('api') || serviceLower.includes('client')) {
    return <FiCloud className="w-3 h-3" />
  }
  if (serviceLower.includes('s3') || serviceLower.includes('storage')) {
    return <FiCloud className="w-3 h-3" />
  }
  if (serviceLower.includes('analytics')) {
    return <FiActivity className="w-3 h-3" />
  }

  return <FiServer className="w-3 h-3" />
}

/**
 * Wrapper that claims an animation slot before rendering a motion.div.
 * When the slot limit is reached, renders a plain div instead.
 */
function AnimatedEventItem({
  children,
  className,
  ...motionProps
}: { children: ReactNode; className?: string } & HTMLMotionProps<'div'>) {
  const { ref, shouldAnimate } = useAnimatedInView()
  if (!shouldAnimate) {
    return <div ref={ref} className={className}>{children}</div>
  }
  return (
    <motion.div
      ref={ref}
      className={className}
      whileHover={{ backgroundColor: 'rgba(99, 102, 241, 0.03)' }}
      {...motionProps}
    >
      {children}
    </motion.div>
  )
}

export function EventsList({ events, subjectOf, onSelect, resourceNames = NO_NAMES }: EventsListProps) {
  const [filter, setFilter] = useState('')

  const filteredEvents = useMemo(() => {
    if (!filter) return events

    const lower = filter.toLowerCase()
    return events.filter(
      e =>
        e.kind?.toLowerCase().includes(lower) ||
        e.coroutineId?.toLowerCase().includes(lower) ||
        e.label?.toLowerCase().includes(lower)
    )
  }, [events, filter])

  const sortedEvents = useMemo(
    () => [...filteredEvents].sort((a, b) => b.seq - a.seq),
    [filteredEvents]
  )

  if (events.length === 0) {
    return (
      <div className="py-8 text-center text-default-400">
        No events yet. Events will appear here as coroutines are created and executed.
      </div>
    )
  }

  const baseTime = events[0]?.tsNanos || 0

  return (
    <div className="space-y-4">
      <Input
        placeholder="Filter events..."
        value={filter}
        onValueChange={setFilter}
        startContent={<FiSearch />}
        isClearable
        onClear={() => setFilter('')}
      />

      <div className="space-y-2">
        <AnimatePresence mode="popLayout">
          {sortedEvents.map((event, idx) => {
            const title = eventTitle(event, resourceNames)
            const subject = onSelect && subjectOf ? subjectOf(event) : null
            return (
            <AnimatedEventItem
              key={`${event.sessionId}-${event.seq}`}
              initial={{ opacity: 0, y: -10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ delay: idx * 0.02 }}
            >
              <Card
                shadow="sm"
                className={
                  'w-full ' +
                  (event.kind?.includes('failed')
                    ? 'border-l-4 border-danger'
                    : event.kind?.includes('cancelled')
                    ? 'border-l-4 border-default-400'
                    : event.kind?.includes('body-completed')
                    ? 'border-l-4 border-secondary/50'
                    : '')
                }
                // A row selects what it is about (S-2): its coroutine, or a resource.
                {...(subject && onSelect
                  ? { isPressable: true, 'aria-label': `Select ${title} (event #${event.seq})`, onPress: () => onSelect(subject) }
                  : {})}
              >
                <CardBody className="py-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <div className="font-mono text-xs text-default-500">
                        #{event.seq}
                      </div>
                      <Chip
                        size="sm"
                        color={getEventColor(event.kind ?? '')}
                        variant="flat"
                        startContent={
                          event.kind?.includes('failed') ? '⚠️' :
                          event.kind?.includes('cancelled') ? '🚫' :
                          event.kind?.includes('body-completed') ? '⏳' :
                          event.kind?.includes('suspended') ? '💤' :
                          event.kind?.includes('resumed') ? '▶️' :
                          undefined
                        }
                      >
                        {event.kind ?? 'unknown'}
                      </Chip>
                      <div className="flex-1">
                        {/* Enhanced service label display */}
                        {(() => {
                          const parsed = parseServiceLabel(event.label)
                          if (parsed) {
                            return (
                              <div className="flex items-center gap-2">
                                <Tooltip content={parsed.service}>
                                  <Chip
                                    size="sm"
                                    variant="bordered"
                                    startContent={parsed.icon}
                                    className="cursor-help"
                                  >
                                    {parsed.service}
                                  </Chip>
                                </Tooltip>
                                <span className="font-semibold text-primary">.{parsed.method}()</span>
                              </div>
                            )
                          }
                          return (
                            <div className="font-semibold">
                              {title}
                            </div>
                          )
                        })()}
                        {event.coroutineId && (
                          <div className="text-xs text-default-500 mt-1">
                            ID: <code className="font-mono">{event.coroutineId}</code>
                            {event.parentCoroutineId && (
                              <> • Parent: <code className="font-mono">{event.parentCoroutineId}</code></>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                    <div className="text-right text-xs text-default-400 flex flex-col items-end gap-1">
                      <div>{formatNanoTime(event.tsNanos)}</div>
                      <Chip size="sm" variant="flat" color="default">
                        {formatRelativeTime(event.tsNanos, baseTime)}
                      </Chip>
                    </div>
                  </div>
                  
                  {/* Suspension duration display */}
                  {event.kind === 'coroutine.suspended' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 flex items-center gap-3 rounded-md bg-secondary/10 px-3 py-2 text-xs"
                    >
                      <div className="flex items-center gap-1 text-secondary">
                        <FiClock className="w-3 h-3" />
                        <span className="font-semibold">Suspending</span>
                      </div>
                      {(event as CoroutineSuspendedEvent).suspensionPoint?.reason && (
                        <Chip size="sm" variant="flat" color="secondary">
                          {(event as CoroutineSuspendedEvent).suspensionPoint?.reason}
                        </Chip>
                      )}
                      {(() => {
                        const parsed = parseServiceLabel(event.label)
                        if (parsed) {
                          return (
                            <span className="text-default-500">
                              Waiting for {parsed.service} response...
                            </span>
                          )
                        }
                        return null
                      })()}
                    </motion.div>
                  )}

                  {/* Add explanation for key events */}
                  {event.kind?.includes('body-completed') && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-secondary/10 px-3 py-2 text-xs text-secondary"
                    >
                      Body finished, waiting for children to complete (structured concurrency)
                    </motion.div>
                  )}
                  
                  {event.kind?.includes('failed') && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-danger/10 px-3 py-2 text-xs text-danger"
                    >
                      ⚠️ Exception will propagate to parent and cancel siblings
                    </motion.div>
                  )}
                  
                  {event.kind?.includes('cancelled') && event.parentCoroutineId && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-default-100 px-3 py-2 text-xs text-default-500"
                    >
                      Cancelled by structured concurrency (parent or sibling failure)
                    </motion.div>
                  )}
                  
                  {/* Job-related event explanations */}
                  {event.kind === 'JobStateChanged' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-secondary/10 px-3 py-2 text-xs text-secondary"
                    >
                      Job State: {(event as JobStateChangedEvent).isActive ? '🟢 Active' : '⚫ Inactive'} |
                      {(event as JobStateChangedEvent).isCompleted ? ' ✅ Completed' : ''}
                      {(event as JobStateChangedEvent).isCancelled ? ' 🚫 Cancelled' : ''} |
                      Children: {(event as JobStateChangedEvent).childrenCount}
                    </motion.div>
                  )}
                  
                  {event.kind === 'JobCancellationRequested' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-default-100 px-3 py-2 text-xs text-default-500"
                    >
                      🚫 Cancellation requested
                      {(event as JobCancellationRequestedEvent).requestedBy &&
                        ` by ${(event as JobCancellationRequestedEvent).requestedBy}`}
                      {(event as JobCancellationRequestedEvent).cause &&
                        `: ${(event as JobCancellationRequestedEvent).cause}`}
                    </motion.div>
                  )}
                  
                  {event.kind === 'JobJoinRequested' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-secondary/10 px-3 py-2 text-xs text-secondary"
                    >
                      ⏳ Waiting for job to complete
                      {(event as JobJoinRequestedEvent).waitingCoroutineId &&
                        ` (caller: ${(event as JobJoinRequestedEvent).waitingCoroutineId})`}
                    </motion.div>
                  )}
                  
                  {event.kind === 'JobJoinCompleted' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-success/10 px-3 py-2 text-xs text-success"
                    >
                      ✅ Job wait completed, execution resumed
                    </motion.div>
                  )}
                  
                  {/* Dispatcher events */}
                  {event.kind === 'DispatcherSelected' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 flex items-center gap-2 rounded-md bg-primary/10 px-3 py-2 text-xs"
                    >
                      <span className="text-primary">🎯 Dispatcher selected:</span>
                      <DispatcherBadge dispatcherName={(event as DispatcherSelectedEvent).dispatcherName} />
                      <span className="text-default-500 font-mono text-xs">
                        ID: {(event as DispatcherSelectedEvent).dispatcherId}
                      </span>
                    </motion.div>
                  )}
                  
                  {event.kind === 'thread.assigned' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 flex items-center gap-2 rounded-md bg-success/10 px-3 py-2 text-xs"
                    >
                      <span className="text-success">🧵 Thread assigned:</span>
                      <code className="font-mono text-xs">{(event as ThreadAssignedEvent).threadName}</code>
                      {(event as ThreadAssignedEvent).dispatcherName && (
                        <>
                          <span className="text-default-500">on</span>
                          <DispatcherBadge dispatcherName={(event as ThreadAssignedEvent).dispatcherName} />
                        </>
                      )}
                    </motion.div>
                  )}
                  
                  {/* Async/Deferred events */}
                  {event.kind === 'DeferredValueAvailable' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-success/10 px-3 py-2 text-xs text-success"
                    >
                      ✅ Async result available: {(event as DeferredValueAvailableEvent).deferredId}
                    </motion.div>
                  )}

                  {event.kind === 'DeferredAwaitStarted' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-warning/10 px-3 py-2 text-xs text-warning"
                    >
                      ⏳ Awaiting async result: {(event as DeferredAwaitStartedEvent).deferredId}
                      {' by '}
                      {(event as DeferredAwaitStartedEvent).awaitingCoroutineId}
                    </motion.div>
                  )}

                  {event.kind === 'DeferredAwaitCompleted' && (
                    <motion.div
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      className="mt-2 rounded-md bg-success/10 px-3 py-2 text-xs text-success"
                    >
                      ✅ Await completed: {(event as DeferredAwaitCompletedEvent).deferredId}
                    </motion.div>
                  )}
                </CardBody>
              </Card>
            </AnimatedEventItem>
            )
          })}
        </AnimatePresence>
      </div>
    </div>
  )
}

function getEventColor(kind: string): 'primary' | 'success' | 'warning' | 'danger' | 'default' | 'secondary' {
  if (kind.includes('created')) return 'default'
  if (kind.includes('started')) return 'primary'
  if (kind.includes('body-completed')) return 'secondary'
  if (kind.includes('completed')) return 'success'
  if (kind.includes('failed')) return 'danger'
  if (kind.includes('cancelled')) return 'default'
  if (kind.includes('suspended')) return 'warning'
  if (kind.includes('resumed')) return 'primary'
  if (kind === 'DispatcherSelected') return 'primary'
  if (kind === 'thread.assigned') return 'success'
  if (kind === 'DeferredValueAvailable') return 'success'
  if (kind === 'DeferredAwaitStarted') return 'warning'
  if (kind === 'DeferredAwaitCompleted') return 'success'
  if (kind === 'JobStateChanged') return 'secondary'
  if (kind === 'JobCancellationRequested') return 'default'
  if (kind === 'JobJoinRequested') return 'secondary'
  if (kind === 'JobJoinCompleted') return 'success'
  return 'default'
}


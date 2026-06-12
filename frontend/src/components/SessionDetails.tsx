import { useState, useEffect, useMemo, useRef } from 'react'
import { Card, CardBody, CardHeader, Chip, Tabs, Tab, Spinner, Button } from '@heroui/react'
import { FiRefreshCw, FiRadio, FiGitBranch, FiList, FiPlay, FiRotateCcw, FiTrash2 } from 'react-icons/fi'
import { useSession, useSessionEvents, useDeleteSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'
import { useRunScenario } from '@/hooks/use-scenarios'
import { useThreadActivity } from '@/hooks/use-thread-activity'
import { useEventCategories } from '@/hooks/use-event-categories'
import { CoroutineTree } from './CoroutineTree'
import { CoroutineTreeGraph } from './CoroutineTreeGraph'
import { EventsList } from './EventsList'
import { StructuredConcurrencyInfo } from './StructuredConcurrencyInfo'
import { ThreadTimeline } from './ThreadTimeline'
import { DispatcherOverview } from './DispatcherOverview'
import { ChannelPanel } from './channels/ChannelPanel'
import { FlowPanel } from './flow/FlowPanel'
import { SyncPanel } from './sync/SyncPanel'
import { JobPanel } from './jobs/JobPanel'
import { ValidationPanel } from './validation/ValidationPanel'
import { OrderProcessingView } from './scenarios/OrderProcessingView'
import { RegistrationFlowView } from './scenarios/RegistrationFlowView'
import { motion, AnimatePresence } from 'framer-motion'
import { useNavigate } from '@tanstack/react-router'
import type { JobStateChangedEvent } from '@/types/api'
import { CoroutineState } from '@/types/api'

/** Terminal coroutine states — no further transitions expected. */
const TERMINAL_STATES = new Set<CoroutineState>([
  CoroutineState.COMPLETED,
  CoroutineState.CANCELLED,
  CoroutineState.FAILED,
])

/** Debounce window for coalescing live-event-driven session refetches (ms). */
const SESSION_REFETCH_DEBOUNCE_MS = 500

/**
 * Max-wait cap for the session-refetch debounce (ms). Under a sustained event
 * stream whose inter-event gap stays below SESSION_REFETCH_DEBOUNCE_MS, a pure
 * trailing-edge debounce would never fire; this cap guarantees the session
 * snapshot refetches at least once per SESSION_REFETCH_MAX_WAIT_MS.
 */
const SESSION_REFETCH_MAX_WAIT_MS = 1500

interface SessionDetailsProps {
  sessionId: string
  scenarioId?: string
  scenarioName?: string
}

export function SessionDetails({ sessionId, scenarioId, scenarioName }: SessionDetailsProps) {
  const { data: session, isLoading, refetch } = useSession(sessionId)
  const { data: storedEvents } = useSessionEvents(sessionId)
  const [streamEnabled, setStreamEnabled] = useState(false)
  const [viewMode, setViewMode] = useState<'graph' | 'list'>('graph')
  const { events: liveEvents, isConnected, clearEvents } = useEventStream(sessionId, streamEnabled)
  // Pass isLive=streamEnabled so thread-activity does not poll every 2s while
  // SSE is driving updates; SSE-triggered cache invalidations handle refreshes.
  const { data: threadActivity } = useThreadActivity(sessionId, streamEnabled)
  const eventCategories = useEventCategories(sessionId)
  const runScenario = useRunScenario()
  const deleteSession = useDeleteSession()
  const navigate = useNavigate()
  // Debounce ref: reset on each new live event; only the trailing edge refetches.
  const sessionRefetchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // Timestamp of the first un-flushed live event in the current debounce
  // window — used to enforce the max-wait cap.
  const firstSessionRefetchAtRef = useRef<number | null>(null)

  const allEvents = streamEnabled ? liveEvents : storedEvents || []
  const hasScenario = !!scenarioId

  // Three-state scenario derivation:
  //   notStarted — coroutineCount === 0 (no coroutines seen yet)
  //   running    — coroutines exist but at least one is non-terminal
  //   completed  — coroutines exist and ALL are in a terminal state
  const scenarioState: 'notStarted' | 'running' | 'completed' = useMemo(() => {
    if (!session || session.coroutineCount === 0 || session.coroutines.length === 0) {
      return 'notStarted'
    }
    const allTerminal = session.coroutines.every(c => TERMINAL_STATES.has(c.state))
    return allTerminal ? 'completed' : 'running'
  }, [session])

  // Track job states from JobStateChanged events
  const jobStates = useMemo(() => {
    const states = new Map<string, JobStateChangedEvent>()
    allEvents.forEach(event => {
      if (event.kind === 'JobStateChanged') {
        const jobEvent = event as JobStateChangedEvent
        states.set(jobEvent.jobId, jobEvent)
      }
    })
    return states
  }, [allEvents])

  // Coalesced session refetch: debounce so a burst of SSE events triggers at
  // most one refetch per SESSION_REFETCH_DEBOUNCE_MS window (trailing edge),
  // with a max-wait cap so a sustained stream still refetches at least once
  // per SESSION_REFETCH_MAX_WAIT_MS (the trailing edge can never be starved).
  useEffect(() => {
    if (!streamEnabled || liveEvents.length === 0) return

    const flushRefetch = () => {
      sessionRefetchTimerRef.current = null
      // Reset the window so the next event starts a fresh max-wait clock.
      firstSessionRefetchAtRef.current = null
      refetch()
    }

    if (firstSessionRefetchAtRef.current === null) {
      firstSessionRefetchAtRef.current = Date.now()
    }
    const elapsed = Date.now() - firstSessionRefetchAtRef.current

    if (sessionRefetchTimerRef.current !== null) {
      clearTimeout(sessionRefetchTimerRef.current)
    }
    if (elapsed >= SESSION_REFETCH_MAX_WAIT_MS) {
      flushRefetch()
    } else {
      sessionRefetchTimerRef.current = setTimeout(
        flushRefetch,
        Math.min(SESSION_REFETCH_DEBOUNCE_MS, SESSION_REFETCH_MAX_WAIT_MS - elapsed),
      )
    }

    return () => {
      // NOTE: this cleanup runs between every liveEvents.length change, so it
      // must NOT reset firstSessionRefetchAtRef here — doing so would restart
      // the max-wait clock on every event and reintroduce starvation. The
      // window ref is reset on flush (above) and on stream teardown (below).
      if (sessionRefetchTimerRef.current !== null) {
        clearTimeout(sessionRefetchTimerRef.current)
        sessionRefetchTimerRef.current = null
      }
    }
  }, [streamEnabled, liveEvents.length, refetch])

  // Teardown for the max-wait window: reset the debounce refs only when the
  // stream toggles or the component unmounts (not between individual events).
  useEffect(() => {
    return () => {
      if (sessionRefetchTimerRef.current !== null) {
        clearTimeout(sessionRefetchTimerRef.current)
        sessionRefetchTimerRef.current = null
      }
      firstSessionRefetchAtRef.current = null
    }
  }, [streamEnabled])

  // Auto-enable live stream when scenario is present
  useEffect(() => {
    if (hasScenario && !streamEnabled) {
      setStreamEnabled(true)
    }
  }, [hasScenario, streamEnabled])

  const handleRunScenario = async () => {
    if (!scenarioId) return

    try {
      await runScenario.mutateAsync({ scenarioId, sessionId })
      // Refetch immediately after running
      setTimeout(() => refetch(), 500)
    } catch {
      // Error is handled by the mutation's error state
    }
  }

  const handleReset = async () => {
    if (!confirm('Reset this session? This will clear all coroutines and start fresh.')) {
      return
    }

    try {
      // Delete current session
      await deleteSession.mutateAsync(sessionId)

      // Navigate back to scenarios or create new session
      if (hasScenario) {
        navigate({ to: '/scenarios' })
      } else {
        navigate({ to: '/sessions' })
      }
    } catch {
      // Error is handled by the mutation's error state
    }
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-20">
        <Spinner size="lg" />
      </div>
    )
  }

  if (!session) {
    return (
      <Card>
        <CardBody>
          <p className="text-center text-danger">Session not found</p>
        </CardBody>
      </Card>
    )
  }

  return (
    <div className="space-y-6">
      {/* Session Header */}
      <Card>
        <CardHeader className="flex items-center justify-between">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-2xl font-bold">Session Details</h1>
              {hasScenario && scenarioName && (
                <Chip color="primary" variant="bordered" size="lg">
                  {scenarioName}
                </Chip>
              )}
            </div>
            <p className="font-mono text-sm text-default-500">{sessionId}</p>
          </div>
          <div className="flex items-center gap-3">
            <div className="flex gap-2">
              <Chip color="primary" variant="flat">
                {session.coroutineCount} coroutines
              </Chip>
              <Chip color="secondary" variant="flat">
                {session.eventCount} events
              </Chip>
            </div>
            <Button
              isIconOnly
              variant="flat"
              onPress={() => refetch()}
            >
              <FiRefreshCw />
            </Button>
          </div>
        </CardHeader>
        <CardBody>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Button
                color={streamEnabled ? 'success' : 'default'}
                variant={streamEnabled ? 'flat' : 'bordered'}
                startContent={<FiRadio />}
                onPress={() => {
                  if (streamEnabled) {
                    clearEvents()
                  }
                  setStreamEnabled(!streamEnabled)
                }}
              >
                {streamEnabled ? 'Live Stream Active' : 'Enable Live Stream'}
              </Button>
              {streamEnabled && (
                <AnimatePresence>
                  <motion.div
                    initial={{ opacity: 0, scale: 0.8 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.8 }}
                    className="flex items-center gap-2"
                  >
                    <Chip
                      color={isConnected ? 'success' : 'warning'}
                      variant="dot"
                    >
                      {isConnected ? 'Connected' : 'Connecting...'}
                    </Chip>
                  </motion.div>
                </AnimatePresence>
              )}
            </div>

            {/* View Mode Toggle */}
            <div className="flex gap-2">
              <Button
                size="sm"
                variant={viewMode === 'graph' ? 'flat' : 'light'}
                color={viewMode === 'graph' ? 'primary' : 'default'}
                startContent={<FiGitBranch />}
                onPress={() => setViewMode('graph')}
              >
                Graph View
              </Button>
              <Button
                size="sm"
                variant={viewMode === 'list' ? 'flat' : 'light'}
                color={viewMode === 'list' ? 'primary' : 'default'}
                startContent={<FiList />}
                onPress={() => setViewMode('list')}
              >
                List View
              </Button>
            </div>
          </div>
        </CardBody>
      </Card>

      {/* Scenario Control Panel */}
      {hasScenario && (
        <Card>
          <CardBody>
            <div className="flex items-center justify-between">
              <div>
                <h3 className="mb-1 text-lg font-semibold">Scenario Controls</h3>
                <p className="text-sm text-default-500">
                  {scenarioState === 'notStarted' && 'Ready to run the scenario'}
                  {scenarioState === 'running' && 'Scenario is running'}
                  {scenarioState === 'completed' && 'Scenario completed'}
                </p>
              </div>
              <div className="flex gap-2">
                {scenarioState === 'completed' ? (
                  <Button
                    color="success"
                    size="lg"
                    variant="flat"
                    startContent={<FiPlay />}
                    isDisabled
                  >
                    Scenario Completed
                  </Button>
                ) : (
                <Button
                  color="primary"
                  size="lg"
                  startContent={<FiPlay />}
                  onPress={handleRunScenario}
                  isLoading={runScenario.isPending}
                  isDisabled={scenarioState === 'running'}
                >
                  {scenarioState === 'running' ? 'Scenario Running' : 'Run Scenario'}
                </Button>
                )}
                <Button
                  color="warning"
                  size="lg"
                  variant="flat"
                  startContent={<FiRotateCcw />}
                  onPress={handleReset}
                  isLoading={deleteSession.isPending}
                >
                  Reset
                </Button>
                <Button
                  color="danger"
                  size="lg"
                  variant="light"
                  startContent={<FiTrash2 />}
                  onPress={handleReset}
                  isLoading={deleteSession.isPending}
                >
                  Clear
                </Button>
              </div>
            </div>
          </CardBody>
        </Card>
      )}

      {/* Structured Concurrency Info - Show when session has coroutines */}
      {session.coroutineCount > 0 && <StructuredConcurrencyInfo />}

      {/* Scenario Pipeline View - shown for realistic scenarios */}
      {scenarioId === 'order-processing' && allEvents.length > 0 && (
        <OrderProcessingView events={allEvents} />
      )}
      {scenarioId === 'user-registration' && allEvents.length > 0 && (
        <RegistrationFlowView events={allEvents} />
      )}

      {/* Main Tabs */}
      <Tabs aria-label="Session tabs" variant="bordered" fullWidth>
        {/* Coroutines tab - Graph/List view toggle */}
        <Tab key="coroutines" title="Coroutines">
          <div className="space-y-4 pt-2">
            <Card>
              <CardBody className="overflow-auto">
                {viewMode === 'graph' ? (
                  <CoroutineTreeGraph coroutines={session.coroutines} />
                ) : (
                  <CoroutineTree coroutines={session.coroutines} />
                )}
              </CardBody>
            </Card>
          </div>
        </Tab>

        {/* Events tab - restored as its own focused tab */}
        <Tab key="events" title="Events">
          <div className="pt-2">
            <Card>
              <CardBody>
                <EventsList events={allEvents} />
              </CardBody>
            </Card>
          </div>
        </Tab>

        {/* Threads tab - thread activity and dispatcher overview */}
        <Tab key="threads" title="Threads">
          <div className="space-y-4 pt-2">
            {threadActivity ? (
              <ThreadTimeline threadActivity={threadActivity as unknown as import('@/types/api').ThreadActivity} />
            ) : (
              <Card>
                <CardBody>
                  <div className="text-center text-default-400 py-4">
                    <Spinner size="sm" className="mb-2" />
                    <p>Loading thread activity...</p>
                  </div>
                </CardBody>
              </Card>
            )}

            <div className="py-2">
              <DispatcherOverview sessionId={sessionId} />
            </div>
          </div>
        </Tab>

        {/* Channels tab - shown when channel events are present */}
        {eventCategories.hasChannels && (
          <Tab key="channels" title="Channels">
            <ChannelPanel sessionId={sessionId} />
          </Tab>
        )}

        {/* Flow tab - shown when flow events are present */}
        {eventCategories.hasFlowOps && (
          <Tab key="flow" title="Flow">
            <FlowPanel sessionId={sessionId} />
          </Tab>
        )}

        {/* Sync tab - shown when sync primitive events are present */}
        {eventCategories.hasSyncPrimitives && (
          <Tab key="sync" title="Sync">
            <SyncPanel sessionId={sessionId} />
          </Tab>
        )}

        {/* Jobs tab - shown when job events are present */}
        {eventCategories.hasJobs && (
          <Tab key="jobs" title={`Jobs (${jobStates.size})`}>
            <JobPanel sessionId={sessionId} />
          </Tab>
        )}

        {/* Validation tab - always shown */}
        <Tab key="validation" title="Validation">
          <ValidationPanel sessionId={sessionId} />
        </Tab>
      </Tabs>
    </div>
  )
}

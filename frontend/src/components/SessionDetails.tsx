import { useState, useEffect, useMemo, useRef, useCallback } from 'react'
import { Card, CardBody, CardHeader, Chip, Tabs, Tab, Spinner, Button } from '@heroui/react'
import { FiRefreshCw, FiRadio, FiGitBranch, FiList, FiPlay, FiRotateCcw, FiTrash2 } from 'react-icons/fi'
import { useSession, useSessionEvents, useDeleteSession } from '@/hooks/use-sessions'
import { useEventStream } from '@/hooks/use-event-stream'
import { useRunScenario } from '@/hooks/use-scenarios'
import { useThreadActivity } from '@/hooks/use-thread-activity'
import { useEventCategories } from '@/hooks/use-event-categories'
import { useReplay } from '@/hooks/use-replay'
import { projectCoroutines } from '@/lib/projections/project-coroutines'
import { projectThreadActivity } from '@/lib/projections/project-thread-activity'
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
import { ReplayController } from './replay/ReplayController'
import { LiveDataNotice } from './replay/LiveDataNotice'
import { RecordConfirmModal } from './replay/RecordConfirmModal'
import { ExportMenu } from './export/ExportMenu'
import { useRecordReplay } from '@/hooks/use-record-replay'
import { OrderProcessingView } from './scenarios/OrderProcessingView'
import { RegistrationFlowView } from './scenarios/RegistrationFlowView'
import { motion, AnimatePresence } from 'framer-motion'
import { useNavigate } from '@tanstack/react-router'
import type { JobStateChangedEvent, ThreadActivity, VizEvent } from '@/types/api'
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
  /**
   * Read-only shared view (Plan 06, D-09/D-10). When true, every mutation/nav
   * affordance is gated OFF — the live-stream toggle, the scenario controls
   * (Run/Reset/Clear), and the Share/Manage-shares trigger — while the
   * tree/graph/timeline/thread-lanes panels, the ReplayController (play/scrub/
   * speed) and the ExportMenu (PNG/SVG/WebM/JSON) stay, since those are pure
   * read operations. The shared `/shared/$token` route feeds the session +
   * events via the React Query cache (seeded from the public getSharedSession
   * payload) so the same hooks render without any protected fetch (T-03-22/25).
   * The component is REUSED, never forked (D-10).
   */
  readOnly?: boolean
}

export function SessionDetails({
  sessionId,
  scenarioId,
  scenarioName,
  readOnly = false,
}: SessionDetailsProps) {
  const { data: session, isLoading, refetch } = useSession(sessionId)
  const { data: storedEvents } = useSessionEvents(sessionId)
  const [streamEnabled, setStreamEnabled] = useState(false)
  const [viewMode, setViewMode] = useState<'graph' | 'list'>('graph')
  // Replay mode (D-01): when active, panels render from the frozen snapshot's
  // replay cursor instead of the live/stored events. The snapshot is captured
  // at replay entry so live SSE events do not mutate the frozen view (D-02).
  const [replayActive, setReplayActive] = useState(false)
  const [replaySnapshot, setReplaySnapshot] = useState<VizEvent[]>([])
  const { events: liveEvents, isConnected, clearEvents } = useEventStream(
    sessionId,
    streamEnabled,
    replayActive,
  )
  // Panel ref for ExportMenu (captures the active visualization region lazily).
  const panelRef = useRef<HTMLDivElement | null>(null)
  // Pass isLive=streamEnabled so thread-activity does not poll every 2s while
  // SSE is driving updates; SSE-triggered cache invalidations handle refreshes.
  // In read-only mode the protected /threads fetch is disabled — the shared
  // shell has no Bearer; thread lanes are derived from the shared events below.
  const { data: threadActivity } = useThreadActivity(sessionId, streamEnabled, !readOnly)
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

  // Replay drives over the FROZEN snapshot taken at entry (never the live
  // `allEvents`), so live SSE events buffer for the badge without re-rendering
  // the replay panels (D-02).
  const replay = useReplay(replaySnapshot)
  const { seekTo: replaySeekTo } = replay

  // Number of live events appended since replay entry — drives the "● N new
  // events" badge (D-02). The SSE stream stays connected during replay (the
  // gate only suppresses invalidation, not the EventSource), so any live
  // events beyond the frozen snapshot are buffered and counted here.
  const newEventsCount = replayActive
    ? Math.max(liveEvents.length - replaySnapshot.length, 0)
    : 0

  // Enter replay: freeze the given snapshot and activate replay. The seek to
  // the end (D-03) is performed by the effect below once useReplay has applied
  // the new snapshot (it resets to index 0 on events-identity change, so the
  // seek must follow that reset).
  //
  // The snapshot is passed EXPLICITLY (WR-08): the replay-toggle button computes
  // it inline from the latest closure, while the recorder computes it ONCE from
  // a ref at click time and passes the SAME list here — so the frozen view and
  // the recorder's auto-stop boundary can never diverge.
  const enterReplay = useCallback(
    (snapshot?: readonly VizEvent[]) => {
      // Defensive: only honor an explicit array snapshot (a stray PressEvent
      // from an onPress handler must fall through to the closure source). The
      // explicit snapshot is copied into a mutable array — it is frozen on
      // entry and never mutated, but `replaySnapshot`/`useReplay` are typed
      // mutable, so a defensive copy keeps the readonly hook contract intact.
      const frozen: VizEvent[] = Array.isArray(snapshot)
        ? [...snapshot]
        : streamEnabled
          ? liveEvents
          : storedEvents || []
      setReplaySnapshot(frozen)
      setReplayActive(true)
    },
    [streamEnabled, liveEvents, storedEvents],
  )

  // Exit replay: drop the cursor and apply buffered events (the gated
  // useEventStream flush re-validates the live panels) — D-04.
  const exitReplay = useCallback(() => {
    setReplayActive(false)
    setReplaySnapshot([])
  }, [])

  // Scripted WebM recording (EXPT-02, plan 02-08): the ExportMenu Record item
  // drives this one-click pipeline — enter replay → seek 0 → record the active
  // panel at 2x while auto-playing → auto-stop at the last event → download.
  // The estimate + auto-stop read whichever events will be frozen on entry: the
  // live snapshot if already replaying, otherwise the source the toggle would
  // freeze (so a one-click record from the live view records the full timeline).
  //
  // WR-08: keep the snapshot source behind a ref so the hook reads it ONCE at
  // click time and freezes exactly that list (rather than two independently
  // closed-over sources that can drift apart by any events arriving in between).
  const recordEvents = replayActive
    ? replaySnapshot
    : streamEnabled
      ? liveEvents
      : storedEvents || []
  const recordEventsRef = useRef<VizEvent[]>(recordEvents)
  recordEventsRef.current = recordEvents
  const recordReplay = useRecordReplay({
    getPanelEl: () => panelRef.current,
    getRecordSnapshot: () => recordEventsRef.current,
    replay,
    enterReplay,
    sessionId,
  })

  // On entering replay (snapshot applied), jump to the end and stay paused
  // (D-03). Keyed on the snapshot identity so re-entry re-seeks; useReplay's
  // own reset-to-0 effect runs first on the same identity change.
  //
  // CR-01: this auto-seek-to-end MUST be suppressed while a recording is being
  // armed or is active. The record flow freezes the SAME snapshot and then
  // seeks to 0 to record the full timeline; if this effect also fired it would
  // clobber the cursor to the LAST index and the recorder would capture only
  // the final frame (a ~0-duration video). Gating on isArming/isRecording lets
  // the record run own the post-enter seek.
  const seekedSnapshotRef = useRef<VizEvent[] | null>(null)
  useEffect(() => {
    if (!replayActive) {
      seekedSnapshotRef.current = null
      return
    }
    if (recordReplay.isArming || recordReplay.isRecording) return
    if (replaySnapshot.length === 0) return
    if (seekedSnapshotRef.current === replaySnapshot) return
    seekedSnapshotRef.current = replaySnapshot
    replaySeekTo(replaySnapshot.length - 1)
  }, [
    replayActive,
    replaySnapshot,
    replaySeekTo,
    recordReplay.isArming,
    recordReplay.isRecording,
  ])

  // Panel data source: replay cursor view-models vs. live snapshot (D-17).
  const panelEvents = replayActive ? replay.visibleEvents : allEvents
  const panelCoroutines = useMemo(
    () => (replayActive ? projectCoroutines(replay.visibleEvents) : session?.coroutines ?? []),
    [replayActive, replay.visibleEvents, session?.coroutines],
  )
  const panelThreadActivity: ThreadActivity | undefined = useMemo(() => {
    if (replayActive) return projectThreadActivity(replay.visibleEvents)
    // Read-only shared view: the protected /threads fetch is disabled, so derive
    // the lanes from the shared event history (same client-side projection used
    // for replay) rather than the server snapshot.
    if (readOnly) return projectThreadActivity(allEvents)
    return threadActivity
  }, [replayActive, readOnly, replay.visibleEvents, allEvents, threadActivity])

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
    // D-02: while replay is active the frozen panels must not be refetched out
    // from under the cursor. The buffered events apply on exit (useEventStream
    // exit flush).
    if (!streamEnabled || replayActive || liveEvents.length === 0) return

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
  }, [streamEnabled, replayActive, liveEvents.length, refetch])

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

  // Auto-enable live stream ONCE when a scenario is present (WR-04). The
  // effect must NOT depend on streamEnabled: re-running on every toggle
  // created a fight-loop where disabling the stream instantly re-enabled it
  // (after clearEvents had already wiped the accumulated live events), making
  // the toggle impossible to switch off on scenario pages.
  const autoEnabledRef = useRef(false)
  useEffect(() => {
    if (hasScenario && !autoEnabledRef.current) {
      autoEnabledRef.current = true
      setStreamEnabled(true)
    }
  }, [hasScenario])

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

  // Clear ONLY empties the live event list (WR-05) — it must never delete
  // the session. Reset (below) remains the destructive delete-and-navigate.
  const handleClear = () => {
    clearEvents()
    refetch()
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
            <div className="flex items-center gap-2">
              <Chip color="primary" variant="flat">
                {session.coroutineCount} coroutines
              </Chip>
              <Chip color="secondary" variant="flat">
                {session.eventCount} events
              </Chip>
              {/* REPLAY mode chip (D-15) — only solid-primary chip in the app. */}
              {replayActive && (
                <Chip size="sm" color="primary" variant="solid">
                  REPLAY
                </Chip>
              )}
              {/* New-events badge (D-02/D-04): clickable, exits replay + applies
                  buffered events. Hidden when N = 0. */}
              {replayActive && newEventsCount > 0 && (
                <button
                  type="button"
                  aria-label="Exit replay and jump to live"
                  onClick={exitReplay}
                >
                  <Chip size="sm" color="warning" variant="dot">
                    {newEventsCount === 1 ? '1 new event' : `${newEventsCount} new events`}
                  </Chip>
                </button>
              )}
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
              {/* Live-stream toggle — a shared session is a frozen capture, so
                  it is gated OFF in read-only mode (T-03-22). The "Read-only
                  shared view" banner is rendered by the /shared/$token shell. */}
              {!readOnly && (
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
              )}
              {!readOnly && streamEnabled && (
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

              {/* Replay toggle (D-01) — always visible. */}
              <Button
                size="sm"
                color={replayActive ? 'primary' : 'default'}
                variant={replayActive ? 'flat' : 'bordered'}
                startContent={<FiPlay />}
                onPress={replayActive ? exitReplay : () => enterReplay()}
              >
                {replayActive ? 'Exit Replay' : 'Replay'}
              </Button>
            </div>

            <div className="flex items-center gap-2">
              {/* Export menu (ADR-018 / EXPT-01/02 / D-22). */}
              <ExportMenu
                getPanelEl={() => panelRef.current}
                sessionId={sessionId}
                sessionName={scenarioName ?? sessionId}
                events={panelEvents}
                panel={viewMode === 'graph' ? 'graph' : 'tree'}
                onRecord={recordReplay.canRecord ? recordReplay.startRecording : undefined}
              />

              {/* View Mode Toggle */}
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

      {/* Sticky ReplayController bar (D-13) — directly above the tabs. While
          recording, the controller shows the red-dot recording cluster and the
          scripted pipeline drives playback (D-06/D-23). */}
      {replayActive && (
        <div className="sticky top-16 z-30">
          <ReplayController
            replay={replay}
            isRecording={recordReplay.isRecording}
            elapsedMs={recordReplay.elapsedMs}
            onStopRecording={recordReplay.stopRecording}
          />
        </div>
      )}

      {/* D-26 long-recording confirm (>120s estimate). */}
      <RecordConfirmModal
        isOpen={recordReplay.confirmOpen}
        estimateMs={recordReplay.confirmEstimateMs}
        speed={recordReplay.confirmSpeed}
        onConfirm={recordReplay.confirmRecord}
        onCancel={recordReplay.cancelConfirm}
      />

      {/* Scenario Control Panel — Run/Reset/Clear are mutations, gated OFF in
          the read-only shared view (T-03-22). */}
      {hasScenario && !readOnly && (
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
                  onPress={handleClear}
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
        {/* Coroutines tab - Graph/List view toggle. In replay, renders the
            projected snapshot from the replay cursor (D-17). */}
        <Tab key="coroutines" title="Coroutines">
          <div className="space-y-4 pt-2">
            <Card>
              <CardBody className="overflow-auto">
                <div ref={panelRef}>
                  {viewMode === 'graph' ? (
                    <CoroutineTreeGraph coroutines={panelCoroutines} />
                  ) : (
                    <CoroutineTree coroutines={panelCoroutines} />
                  )}
                </div>
              </CardBody>
            </Card>
          </div>
        </Tab>

        {/* Events tab - restored as its own focused tab */}
        <Tab key="events" title="Events">
          <div className="pt-2">
            <Card>
              <CardBody>
                <EventsList events={panelEvents} />
              </CardBody>
            </Card>
          </div>
        </Tab>

        {/* Threads tab - thread activity and dispatcher overview. In replay,
            derive lanes from the replay cursor via projectThreadActivity. */}
        <Tab key="threads" title="Threads">
          <div className="space-y-4 pt-2">
            {panelThreadActivity ? (
              <ThreadTimeline threadActivity={panelThreadActivity} />
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
              <DispatcherOverview
                sessionId={sessionId}
                isLive={streamEnabled}
                enabled={!readOnly}
              />
            </div>
          </div>
        </Tab>

        {/* Channels tab - shown when channel events are present */}
        {eventCategories.hasChannels && (
          <Tab key="channels" title="Channels">
            <div className="space-y-2 pt-2">
              {replayActive && <LiveDataNotice />}
              <ChannelPanel sessionId={sessionId} />
            </div>
          </Tab>
        )}

        {/* Flow tab - shown when flow events are present */}
        {eventCategories.hasFlowOps && (
          <Tab key="flow" title="Flow">
            <div className="space-y-2 pt-2">
              {replayActive && <LiveDataNotice />}
              <FlowPanel sessionId={sessionId} />
            </div>
          </Tab>
        )}

        {/* Sync tab - shown when sync primitive events are present */}
        {eventCategories.hasSyncPrimitives && (
          <Tab key="sync" title="Sync">
            <div className="space-y-2 pt-2">
              {replayActive && <LiveDataNotice />}
              <SyncPanel sessionId={sessionId} />
            </div>
          </Tab>
        )}

        {/* Jobs tab - shown when job events are present */}
        {eventCategories.hasJobs && (
          <Tab key="jobs" title={`Jobs (${jobStates.size})`}>
            <div className="space-y-2 pt-2">
              {replayActive && <LiveDataNotice />}
              <JobPanel sessionId={sessionId} />
            </div>
          </Tab>
        )}

        {/* Validation tab - always shown */}
        <Tab key="validation" title="Validation">
          <div className="space-y-2 pt-2">
            {replayActive && <LiveDataNotice />}
            <ValidationPanel sessionId={sessionId} />
          </div>
        </Tab>
      </Tabs>
    </div>
  )
}

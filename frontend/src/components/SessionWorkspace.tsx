import { useState, useEffect, useMemo, useRef } from 'react'
import {
  Card,
  CardBody,
  Tabs,
  Tab,
  Spinner,
  Modal,
  ModalContent,
  ModalHeader,
  ModalBody,
} from '@heroui/react'
import { useSession, useSessionEvents } from '@/hooks/use-sessions'
import { useThreadActivity } from '@/hooks/use-thread-activity'
import { useEventCategories } from '@/hooks/use-event-categories'
import { useWorkspaceReplay } from '@/hooks/use-workspace-replay'
import { useSessionRefetch } from '@/hooks/use-session-refetch'
import { useValidation } from '@/hooks/use-validation'
import { projectCoroutines } from '@/lib/projections/project-coroutines'
import { deriveStateCounts, selectCoroutines, type StateFilter } from '@/lib/state-counts'
import { deriveRung } from '@/lib/fidelity-rung'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { projectThreadActivity } from '@/lib/projections/project-thread-activity'
import { StructuredConcurrencyInfo } from './StructuredConcurrencyInfo'
import { SessionHeader } from './workspace/SessionHeader'
import { CoroutineCanvas } from './workspace/CoroutineCanvas'
import { WorkspaceBody } from './workspace/WorkspaceBody'
import { Inspector } from './workspace/Inspector/Inspector'
import { EventsDrawer } from './workspace/EventsDrawer'
import { EvidencePanels } from './workspace/EvidencePanels'
import { ChecksModal, countFailedChecks } from './workspace/ChecksModal'
import { ScenarioControls } from './workspace/ScenarioControls'
import { StateBar } from './StateBar'
import { ReplayController } from './replay/ReplayController'
import { RecordConfirmModal } from './replay/RecordConfirmModal'
import { ManageShares } from './share/ManageShares'
import { OrderProcessingView } from './scenarios/OrderProcessingView'
import { RegistrationFlowView } from './scenarios/RegistrationFlowView'
import type { ThreadActivity } from '@/types/api'
import { CoroutineState } from '@/types/api'

/** Terminal coroutine states — no further transitions expected. */
const TERMINAL_STATES = new Set<CoroutineState>([
  CoroutineState.COMPLETED,
  CoroutineState.CANCELLED,
  CoroutineState.FAILED,
])

/**
 * Max number of live coroutine nodes rendered before collapsing the overflow
 * into an "N more coroutines" indicator (D-08). Tuned against the demo to beat
 * the Phase-7 ~1,800-card flat wall while keeping the active view legible.
 */
const NODE_CAP = 200

interface SessionWorkspaceProps {
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

export function SessionWorkspace({
  sessionId,
  scenarioId,
  scenarioName,
  readOnly = false,
}: SessionWorkspaceProps) {
  const { data: session, isLoading, refetch } = useSession(sessionId)
  const { data: storedEvents } = useSessionEvents(sessionId)
  const [streamEnabled, setStreamEnabled] = useState(false)
  const [viewMode, setViewMode] = useState<'graph' | 'list'>('graph')
  // Selected coroutine for the source drawer (RCO-06, D-02/D-06). Lifted here so
  // both live-view modes (graph/list) share one selection + one mounted drawer.
  // Null = drawer closed. Wired ONLY at the live "What's running now" mount, so
  // replay/shared node renders stay non-interactive.
  const [selectedCoroutineId, setSelectedCoroutineId] = useState<string | null>(null)
  // Active-only "What's running now" view (D-08): completed coroutines collapse
  // into an expandable aggregate, toggled off by default.
  const [showCompleted, setShowCompleted] = useState(false)
  // The state bar's active chip (D-4). 'all' is the resting state and preserves
  // the pre-bar behaviour exactly: active-only unless showCompleted is on.
  const [stateFilter, setStateFilter] = useState<StateFilter>('all')
  // Manage-shares modal (D-11/D-13). Owner-only — the trigger is gated OFF in
  // the read-only shared view (ADR-019: no re-sharing from a shared link).
  const [sharesOpen, setSharesOpen] = useState(false)
  // Session checks (M-3). The hook is owned here rather than inside the modal
  // so a run survives the modal closing, and so the state bar can report it.
  const [checksOpen, setChecksOpen] = useState(false)
  const validation = useValidation(sessionId)
  const failedChecks = countFailedChecks(validation.data)
  // Panel ref for ExportMenu (captures the active visualization region lazily).
  const panelRef = useRef<HTMLDivElement | null>(null)
  // Pass isLive=streamEnabled so thread-activity does not poll every 2s while
  // SSE is driving updates; SSE-triggered cache invalidations handle refreshes.
  // In read-only mode the protected /threads fetch is disabled — the shared
  // shell has no Bearer; thread lanes are derived from the shared events below.
  const { data: threadActivity } = useThreadActivity(sessionId, streamEnabled, !readOnly)
  const eventCategories = useEventCategories(sessionId)
  // Which rung this session is on (D-6), and the leak set behind the leak chip.
  // Metrics is already polled by the dock; React Query dedupes the second call.
  const rung = useMemo(
    () => deriveRung(sessionId, eventCategories),
    [sessionId, eventCategories],
  )
  const { data: metrics } = useSessionMetrics(sessionId, streamEnabled, !readOnly)
  const leakIds = useMemo(
    () => new Set((metrics?.leaks ?? []).map(l => l.coroutineId)),
    [metrics?.leaks],
  )

  // Replay + scripted recording (D-01..04, D-23). One hook because the cursor
  // and the recorder freeze the same snapshot and contend for the same seek.
  const {
    liveEvents,
    isConnected,
    clearEvents,
    replayActive,
    enterReplay,
    exitReplay,
    replay,
    recordReplay,
    newEventsCount,
  } = useWorkspaceReplay({
    sessionId,
    streamEnabled,
    storedEvents,
    getPanelEl: () => panelRef.current,
  })

  // Coalesced session refetch (CR-02). Suspended while replaying so the frozen
  // panels are not refetched out from under the cursor (D-02).
  useSessionRefetch({
    enabled: streamEnabled && !replayActive,
    eventCount: liveEvents.length,
    refetch,
    streamEnabled,
  })

  const allEvents = streamEnabled ? liveEvents : storedEvents || []
  const hasScenario = !!scenarioId
  // Live-view gate (PD-01): the Surface-001 IDE-dock is LIVE-ONLY. Replay and
  // read-only shared modes keep the existing tabbed, non-interactive layout.
  const isLiveView = !replayActive && !readOnly

  // Panel data source: replay cursor view-models vs. live snapshot (D-17).
  const panelEvents = replayActive ? replay.visibleEvents : allEvents
  const panelCoroutines = useMemo(
    () => (replayActive ? projectCoroutines(replay.visibleEvents) : session?.coroutines ?? []),
    [replayActive, replay.visibleEvents, session?.coroutines],
  )
  // Active-only "What's running now" derivation (D-08). Default the live view to
  // the non-terminal set, cap rendered nodes at NODE_CAP, and surface the
  // remainder ("N more") + the collapsed completed aggregate ("Show completed").
  const { completedCount, shownCoroutines, moreCount } = useMemo(() => {
    const active = panelCoroutines.filter(c => !TERMINAL_STATES.has(c.state as CoroutineState))
    const shown = active.slice(0, NODE_CAP)
    return {
      completedCount: panelCoroutines.length - active.length,
      shownCoroutines: shown,
      moreCount: active.length - shown.length,
    }
  }, [panelCoroutines])
  // What the tree/graph render: the capped active set, or — when the user expands
  // the completed aggregate — the full set (active + completed) up to the cap.
  const renderedCoroutines = useMemo(() => {
    // A named chip selects that state explicitly across the whole snapshot, and
    // bypasses the active-only default — you asked for completed, you get
    // completed. With no chip active, behaviour is exactly as before the bar.
    if (stateFilter !== 'all') {
      return selectCoroutines(panelCoroutines, stateFilter, leakIds).slice(0, NODE_CAP)
    }
    return showCompleted ? panelCoroutines.slice(0, NODE_CAP) : shownCoroutines
  }, [stateFilter, showCompleted, panelCoroutines, shownCoroutines, leakIds])

  /** Counts for the state bar — the whole snapshot, never the capped subset. */
  const stateCounts = useMemo(
    () => deriveStateCounts(panelCoroutines, leakIds),
    [panelCoroutines, leakIds],
  )

  /** The selected coroutine itself — what the inspector reads (D-7). */
  const selectedCoroutine = useMemo(
    () => panelCoroutines.find(c => c.id === selectedCoroutineId) ?? null,
    [panelCoroutines, selectedCoroutineId],
  )

  // Close the source drawer if its coroutine leaves the session entirely (e.g. a
  // replay cursor moving before its creation, or a session reset). A terminal or
  // capped-out coroutine still exists in panelCoroutines, so it keeps its drawer +
  // resolved label (WR-04); only a truly-absent id clears the selection so we stop
  // fetching a coroutine that no longer exists.
  useEffect(() => {
    if (selectedCoroutineId && !panelCoroutines.some(c => c.id === selectedCoroutineId)) {
      setSelectedCoroutineId(null)
    }
  }, [selectedCoroutineId, panelCoroutines])

  const panelThreadActivity: ThreadActivity | undefined = useMemo(() => {
    if (replayActive) return projectThreadActivity(replay.visibleEvents)
    // Read-only shared view: the protected /threads fetch is disabled, so derive
    // the lanes from the shared event history (same client-side projection used
    // for replay) rather than the server snapshot.
    if (readOnly) return projectThreadActivity(allEvents)
    return threadActivity
  }, [replayActive, readOnly, replay.visibleEvents, allEvents, threadActivity])

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
      <SessionHeader
        sessionId={sessionId}
        session={session}
        scenarioName={scenarioName}
        hasScenario={hasScenario}
        rung={rung}
        readOnly={readOnly}
        streamEnabled={streamEnabled}
        isConnected={isConnected}
        onToggleStream={() => {
          if (streamEnabled) {
            clearEvents()
          }
          setStreamEnabled(!streamEnabled)
        }}
        replayActive={replayActive}
        newEventsCount={newEventsCount}
        onToggleReplay={replayActive ? exitReplay : () => enterReplay()}
        onExitReplay={exitReplay}
        viewMode={viewMode}
        onViewModeChange={setViewMode}
        onRefetch={() => refetch()}
        onOpenShares={() => setSharesOpen(true)}
        onOpenChecks={() => setChecksOpen(true)}
        panelRef={panelRef}
        panelEvents={panelEvents}
        onRecord={recordReplay.canRecord ? recordReplay.startRecording : undefined}
      />

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

      {/* Manage-shares modal (D-11/D-13) — owner-only; never mounted in the
          read-only shared view (the trigger is gated off there). */}
      {!readOnly && (
        <Modal isOpen={sharesOpen} onOpenChange={setSharesOpen} size="lg">
          <ModalContent>
            <ModalHeader>Manage shares</ModalHeader>
            <ModalBody className="pb-6">
              <ManageShares sessionId={sessionId} />
            </ModalBody>
          </ModalContent>
        </Modal>
      )}

      {/* Session checks (M-3) — the retired Validation tab, on demand. */}
      <ChecksModal
        sessionId={sessionId}
        isOpen={checksOpen}
        onOpenChange={setChecksOpen}
        validation={validation}
      />

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
      {scenarioId && !readOnly && (
        <ScenarioControls
          sessionId={sessionId}
          scenarioId={scenarioId}
          session={session}
          onClearEvents={clearEvents}
          onRefetch={refetch}
        />
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

      {/* The state bar (D-4): what the session is doing, and the canvas filter. */}
      <StateBar
        counts={stateCounts}
        filter={stateFilter}
        onFilterChange={setStateFilter}
        // Shown only once a run has actually failed something (M-3): a chip
        // reading zero for a session nobody validated is a claim we cannot make.
        action={
          failedChecks > 0
            ? { label: 'Checks', count: failedChecks, onPress: () => setChecksOpen(true) }
            : undefined
        }
      />

      {/* Main Tabs */}
      <Tabs aria-label="Session tabs" variant="bordered" fullWidth>
        {/* Coroutines tab - Graph/List view toggle. In replay, renders the
            projected snapshot from the replay cursor (D-17). */}
        <Tab key="coroutines" title="Coroutines">
          <div className="space-y-4 pt-2">
            {/* The live "what's running now" list (header + Show-completed
                control + tree/graph canvas + "N more"). In the live view it is
                hosted in the dock's left column (PD-01/PD-02); in replay/shared
                it renders standalone in the existing tabbed layout. */}
            {(() => {
              const liveList = (
                <CoroutineCanvas
                  coroutines={renderedCoroutines}
                  viewMode={viewMode}
                  completedCount={completedCount}
                  showCompleted={showCompleted}
                  onToggleCompleted={() => setShowCompleted(prev => !prev)}
                  moreCount={moreCount}
                  stateFilter={stateFilter}
                  selectedCoroutineId={selectedCoroutineId}
                  onSelect={isLiveView ? setSelectedCoroutineId : undefined}
                  panelRef={panelRef}
                />
              )

              const canvasWithEvents = (
                <div className="space-y-4">
                  {liveList}
                  {/* Events, re-hosted from its tab as a drawer under the
                      canvas and scoped to the selection (D-4 / spec tab map). */}
                  <EventsDrawer events={panelEvents} selectedCoroutine={selectedCoroutine} />
                </div>
              )

              // Surface 001 (PD-01): LIVE view → IDE-dock; replay/shared → the
              // existing standalone list (no dock, no added interactivity). The
              // dock owns the single SessionMetrics tile-strip + the single
              // inline LeakList (PD-02), so neither is mounted again here.
              // Surface 002 (PD-05): the live-view source attribution lives
              // INLINE in the dock's right column — the selected coroutine's
              // CoroutineSourceStack (compact chips → expand) feeds the slot.
              // The right-side CoroutineSourceDrawer mount is retired so the
              // timeline is fetched/mounted exactly once (Pitfall 5). A muted
              // placeholder renders until a coroutine is selected.
              return isLiveView ? (
                <WorkspaceBody
                  sessionId={sessionId}
                  streamEnabled={streamEnabled}
                  readOnly={readOnly}
                  liveList={canvasWithEvents}
                  showMetrics
                  inspector={
                    <Inspector
                      sessionId={sessionId}
                      coroutine={selectedCoroutine}
                      readOnly={readOnly}
                    />
                  }
                />
              ) : (
                canvasWithEvents
              )
            })()}
          </div>
        </Tab>

      </Tabs>

      {/* The evidence section (M-1/M-2): what this session can show beyond the
          tree, and — where the rung is the reason it cannot — what unlocks it. */}
      <EvidencePanels
        sessionId={sessionId}
        rung={rung}
        categories={eventCategories}
        replayActive={replayActive}
        readOnly={readOnly}
        streamEnabled={streamEnabled}
        threadActivity={panelThreadActivity}
      />
    </div>
  )
}

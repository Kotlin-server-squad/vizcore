import { useState, useEffect, useMemo, useRef } from 'react'
import {
  Card,
  CardBody,
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
import { useValidation } from '@/hooks/use-validation'
import { SSE_FALLBACK_POLL_MS } from '@/lib/poll-interval'
import { useNavigate } from '@tanstack/react-router'
import { ErrorState } from './ErrorState'
import { projectCoroutines } from '@/lib/projections/project-coroutines'
import { deriveStateCounts, selectCoroutines, type StateFilter } from '@/lib/state-counts'
import { deriveRung } from '@/lib/fidelity-rung'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { projectThreadActivity } from '@/lib/projections/project-thread-activity'
import { SessionHeader } from './workspace/SessionHeader'
import { CoroutineCanvas } from './workspace/CoroutineCanvas'
import { SessionDock } from './workspace/SessionDock'
import { Inspector } from './workspace/Inspector/Inspector'
import { EventsPanel } from './workspace/EventsPanel'
import { DockTabs, DockTabPanel, type DockTabId } from './workspace/DockTabs'
import { LearnPanel } from './workspace/LearnPanel'
import { SessionSummary } from './workspace/SessionSummary'
import { EvidencePanels } from './workspace/EvidencePanels'
import { ChecksModal, countFailedChecks } from './workspace/ChecksModal'
import { useScenarioActions } from './workspace/use-scenario-actions'
import { StateBar } from './StateBar'
import { ReplayController } from './replay/ReplayController'
import { RecordConfirmModal } from './replay/RecordConfirmModal'
import { ManageShares } from './share/ManageShares'
import { OrderProcessingView } from './scenarios/OrderProcessingView'
import { RegistrationFlowView } from './scenarios/RegistrationFlowView'
import type { ThreadActivity } from '@/types/api'
import { CoroutineState } from '@/types/api'
import { isDemoSessionId, sessionDisplayName } from '@/lib/session-kind'
import { useDockLayout } from '@/hooks/use-dock-layout'
import { deriveRunOutcome, recordedSpanNanos } from '@/lib/run-outcome'
import { countEvidence, evidenceTabLabel } from '@/lib/evidence-summary'
import { openingDockTab, rememberDockTab, rememberedDockTab } from '@/lib/dock-tab-memory'

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
  /** Open with the live stream already on (a just-connected real app, #126). */
  startLive?: boolean
}

export function SessionWorkspace({
  sessionId,
  scenarioId,
  scenarioName,
  readOnly = false,
  startLive = false,
}: SessionWorkspaceProps) {
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

  // Replay + scripted recording (D-01..04, D-23). One hook because the cursor
  // and the recorder freeze the same snapshot and contend for the same seek.
  const {
    liveEvents,
    isConnected,
    clearEvents,
    streamError,
    droppedCount,
    reconnect,
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

  // Refresh cadence (#124). Nothing here polls while the live stream is
  // connected — its throttled, SSE-driven invalidation refreshes the snapshot,
  // threads and metrics — and nothing polls with the stream off: that view is
  // a snapshot the user refreshes. Polling is only the fallback while the
  // stream is ON but its connection is down. This component holds the ONLY
  // polling observer of each key; every other observer passes no interval.
  const fallbackPollMs =
    streamEnabled && !isConnected && !replayActive && !readOnly ? SSE_FALLBACK_POLL_MS : false
  const {
    data: session,
    isLoading,
    isPending,
    error: sessionError,
    refetch,
    dataUpdatedAt,
  } = useSession(sessionId, { pollMs: fallbackPollMs })
  const navigate = useNavigate()
  // In read-only mode the protected /threads fetch is disabled — the shared
  // shell has no Bearer; thread lanes are derived from the shared events below.
  const { data: threadActivity } = useThreadActivity(sessionId, fallbackPollMs, !readOnly)
  const eventCategories = useEventCategories(sessionId, streamEnabled ? liveEvents : undefined)
  // Which rung this session is on (D-6), and the leak set behind the leak chip.
  const rung = useMemo(
    () => deriveRung(sessionId, eventCategories),
    [sessionId, eventCategories],
  )
  const { data: metrics } = useSessionMetrics(sessionId, fallbackPollMs, !readOnly)
  const leakIds = useMemo(
    () => new Set((metrics?.leaks ?? []).map(l => l.coroutineId)),
    [metrics?.leaks],
  )

  const allEvents = streamEnabled ? liveEvents : storedEvents || []

  // The dock's size, fold and maximise (S-1), remembered per browser.
  const dock = useDockLayout()
  // Run / Reset / Clear (S-3). Mutation hooks only — nothing is requested until
  // an action is used, and none is offered in the shared view.
  const scenarioActions = useScenarioActions({
    sessionId,
    scenarioId: scenarioId ?? '',
    scenarioName,
    onClearEvents: clearEvents,
    onRefetch: () => void refetch(),
  })
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
  // When nothing is live any more the run is over, and hiding its coroutines
  // would leave an empty tree: a finished run shows all of them (spec Part 1).
  const { completedCount, activeCoroutines, allFinished } = useMemo(() => {
    const active = panelCoroutines.filter(c => !TERMINAL_STATES.has(c.state as CoroutineState))
    return {
      completedCount: panelCoroutines.length - active.length,
      activeCoroutines: active,
      allFinished: active.length === 0 && panelCoroutines.length > 0,
    }
  }, [panelCoroutines])
  // What the tree/graph render, and how many the cap leaves out — both from the
  // same pool, so "N more" always counts what this view would have shown.
  const { renderedCoroutines, moreCount } = useMemo(() => {
    // A named chip selects that state explicitly across the whole snapshot, and
    // bypasses the active-only default — you asked for completed, you get
    // completed. With no chip, the active set; everything when the user expands
    // the completed aggregate or when the run is over.
    const pool =
      stateFilter !== 'all'
        ? selectCoroutines(panelCoroutines, stateFilter, leakIds)
        : showCompleted || allFinished
          ? panelCoroutines
          : activeCoroutines
    const shown = pool.slice(0, NODE_CAP)
    return { renderedCoroutines: shown, moreCount: pool.length - shown.length }
  }, [stateFilter, showCompleted, allFinished, panelCoroutines, activeCoroutines, leakIds])

  // Unfinished children per parent, over the whole snapshot: the "Waiting for N
  // child coroutines" line must not shrink when a chip or the cap hides them.
  const pendingChildCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const c of panelCoroutines) {
      if (c.parentId && !TERMINAL_STATES.has(c.state as CoroutineState)) {
        counts.set(c.parentId, (counts.get(c.parentId) ?? 0) + 1)
      }
    }
    return counts
  }, [panelCoroutines])

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

  // A demo's run outcome (S-3); an attached app has none.
  const isDemo = hasScenario || isDemoSessionId(sessionId)
  const outcome = useMemo(() => (isDemo && session ? deriveRunOutcome(session) : null), [isDemo, session])
  // The run's length over everything the page holds: the history loaded with
  // it and whatever streamed in since (either may be missing the other's part).
  const runSpanNanos = useMemo(
    () => recordedSpanNanos(storedEvents ?? [], streamEnabled ? liveEvents : []),
    [storedEvents, streamEnabled, liveEvents],
  )
  // Where the replay cursor is, as time since the first event (the view pill).
  const replayAtNanos = useMemo(() => {
    const visible = replay.visibleEvents
    return replayActive && visible.length > 0 ? visible[visible.length - 1]!.tsNanos - visible[0]!.tsNanos : null
  }, [replayActive, replay.visibleEvents])

  // Which dock tab is showing (S-5). The opening tab is decided once, when the
  // session has loaded: a demo that finishes while you watch stays where you
  // are. A tab the user picks wins, and is remembered for this session.
  const [pickedTab, setPickedTab] = useState<DockTabId | null>(null)
  const [openingTab, setOpeningTab] = useState<DockTabId | null>(null)
  if (openingTab === null && session) {
    setOpeningTab(
      rememberedDockTab(sessionId) ??
        openingDockTab({ startedAsDemo: hasScenario, outcome: outcome?.outcome ?? null }),
    )
  }
  const dockTab: DockTabId = pickedTab ?? openingTab ?? 'inspector'
  const selectDockTab = (tab: DockTabId) => {
    setPickedTab(tab)
    rememberDockTab(sessionId, tab)
    // A tab chosen on a folded dock is a tab you want to read.
    if (dock.folded) dock.toggleFold()
  }

  // What the Evidence tab holds, named on its label (S-4).
  const evidenceCounts = useMemo(() => countEvidence(panelEvents), [panelEvents])
  // "Time into the capture" for the thread table: the first retained event.
  const captureStartNanos = useMemo(() => {
    let first: number | null = null
    for (const e of panelEvents) if (first === null || e.tsNanos < first) first = e.tsNanos
    return first
  }, [panelEvents])

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
  // The same applies to a real app the Connect wizard just attached (#126):
  // it opens streaming rather than as a static snapshot.
  const autoEnabledRef = useRef(false)
  const autoLive = !readOnly && (hasScenario || startLive)
  useEffect(() => {
    if (autoLive && !autoEnabledRef.current) {
      autoEnabledRef.current = true
      setStreamEnabled(true)
    }
  }, [autoLive])

  // isPending too: a paused retry (background tab, offline) is still loading,
  // not a missing session (#139).
  if (isLoading || (isPending === true && !sessionError)) {
    return (
      <div className="flex flex-1 items-center justify-center py-20">
        <Spinner size="lg" />
      </div>
    )
  }

  if (!session) {
    // A failed load is one of several distinct things (#139) — a 429 or a dead
    // server must not read as "Session not found".
    if (sessionError) {
      return (
        <ErrorState
          error={sessionError}
          subject="This session"
          onRetry={() => void refetch()}
          action={readOnly ? undefined : { label: 'Back to sessions', onPress: () => void navigate({ to: '/' }) }}
        />
      )
    }
    return (
      <Card>
        <CardBody>
          <p className="text-center text-danger">Session not found</p>
        </CardBody>
      </Card>
    )
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <SessionHeader
        sessionId={sessionId}
        session={session}
        title={hasScenario && scenarioName ? scenarioName : sessionDisplayName(sessionId)}
        rung={rung}
        readOnly={readOnly}
        outcome={outcome}
        runSpanNanos={runSpanNanos}
        streamEnabled={streamEnabled}
        isConnected={isConnected}
        streamError={streamError}
        droppedCount={droppedCount}
        onReconnect={reconnect}
        onToggleStream={() => {
          if (streamEnabled) {
            clearEvents()
          }
          setStreamEnabled(!streamEnabled)
        }}
        replayActive={replayActive}
        replayAtNanos={replayAtNanos}
        newEventsCount={newEventsCount}
        onToggleReplay={replayActive ? exitReplay : () => enterReplay()}
        onExitReplay={exitReplay}
        loadedAtMs={dataUpdatedAt}
        onRefetch={() => refetch()}
        onOpenShares={() => setSharesOpen(true)}
        onOpenChecks={() => setChecksOpen(true)}
        // Run/Reset/Clear are mutations, gated OFF in the read-only shared view (T-03-22).
        scenario={scenarioId && !readOnly ? scenarioActions : undefined}
        panelRef={panelRef}
        panelEvents={panelEvents}
        viewMode={viewMode}
        onRecord={recordReplay.canRecord ? recordReplay.startRecording : undefined}
      />

      {/* The ReplayController bar (D-13), directly under the header. While
          recording, the controller shows the red-dot recording cluster and the
          scripted pipeline drives playback (D-06/D-23). */}
      {replayActive && (
        <ReplayController
          replay={replay}
          isRecording={recordReplay.isRecording}
          elapsedMs={recordReplay.elapsedMs}
          onStopRecording={recordReplay.stopRecording}
        />
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

      {/* The main view and the dock share the rest of the page (S-1). */}
      <div ref={dock.sharedRef} className="flex min-h-[480px] flex-1 flex-col gap-3">
        <CoroutineCanvas
          coroutines={renderedCoroutines}
          viewMode={viewMode}
          onViewModeChange={setViewMode}
          completedCount={completedCount}
          allFinished={allFinished}
          showCompleted={showCompleted}
          onToggleCompleted={() => setShowCompleted(prev => !prev)}
          moreCount={moreCount}
          pendingChildCounts={pendingChildCounts}
          stateFilter={stateFilter}
          stateBar={
            // The state bar (D-4): what the session is doing, and the canvas filter.
            <StateBar
              bare
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
          }
          isDemo={isDemo}
          selectedCoroutineId={selectedCoroutineId}
          // Selection is LIVE-ONLY (PD-01): replay and the read-only shared
          // view stay presentational, with no added interactivity.
          onSelect={
            isLiveView
              ? (id: string) => {
                  setSelectedCoroutineId(id)
                  // A selection is read in the Inspector: show it (S-4).
                  selectDockTab('inspector')
                }
              : undefined
          }
          panelRef={panelRef}
          maximised={dock.maximised}
          onToggleMaximise={dock.toggleMaximise}
        />

        {/* The dock (S-1): one tab per question (S-4). Maximise hides it,
            never unmounts it, and so do folding and switching tabs: a panel
            that re-subscribed later would refetch a stale query (#124). */}
        <SessionDock
          height={dock.height}
          maxHeight={dock.maxHeight}
          folded={dock.folded}
          hidden={dock.maximised}
          onResize={dock.resize}
          onToggleFold={dock.toggleFold}
          bar={
            <DockTabs
              // Problems ("what is wrong?") is the fifth question of S-4. It
              // stays hidden, not empty, until the health API (Part 5) and its
              // tab (Part 7) land.
              tabs={[
                { id: 'inspector', label: 'Inspector' },
                { id: 'evidence', label: evidenceTabLabel(evidenceCounts) },
                { id: 'events', label: 'Events' },
                { id: 'learn', label: 'Learn' },
              ]}
              selected={dockTab}
              onSelect={selectDockTab}
            />
          }
        >
          <DockTabPanel id="inspector" selected={dockTab === 'inspector'}>
            <Inspector
              sessionId={sessionId}
              coroutine={isLiveView ? selectedCoroutine : null}
              readOnly={readOnly}
              // T-3's session summary, live view only: in replay the metrics
              // would report now over a frozen view, and the shared shell has
              // no Bearer for /metrics (M-4).
              emptyState={
                isLiveView ? (
                  <SessionSummary
                    sessionId={sessionId}
                    enabled={!readOnly}
                    coroutineCount={session.coroutineCount}
                    eventCount={session.eventCount}
                  />
                ) : undefined
              }
              // Selection is live-only, so outside the live view the inspector
              // must not invite a click that cannot work (#145).
              placeholder={
                replayActive
                  ? 'The inspector is paused during replay. Exit replay to inspect a coroutine.'
                  : readOnly
                    ? 'Coroutine details are not included in a shared link.'
                    : undefined
              }
              threadActivity={panelThreadActivity}
            />
          </DockTabPanel>
          <DockTabPanel id="evidence" selected={dockTab === 'evidence'}>
            {/* What the resources did (M-1/M-2), and — where the rung is the
                reason nothing was captured — what unlocks it (D-5). */}
            <EvidencePanels
              sessionId={sessionId}
              rung={rung}
              categories={eventCategories}
              counts={evidenceCounts}
              replayActive={replayActive}
              readOnly={readOnly}
              threadActivity={panelThreadActivity}
              captureStartNanos={captureStartNanos}
            />
          </DockTabPanel>
          <DockTabPanel id="events" selected={dockTab === 'events'}>
            {/* Mounted only while shown: a long event list is the heaviest
                render on the page, and a hidden one would re-render on every
                live flush. It fetches nothing, so mounting later costs no
                request. Every other panel stays mounted and is hidden. */}
            {dockTab === 'events' && (
              <EventsPanel events={panelEvents} selectedCoroutine={selectedCoroutine} />
            )}
          </DockTabPanel>
          <DockTabPanel id="learn" selected={dockTab === 'learn'}>
            <LearnPanel>
              {/* Scenario Pipeline View - shown for realistic scenarios */}
              {scenarioId === 'order-processing' && allEvents.length > 0 && (
                <OrderProcessingView events={allEvents} />
              )}
              {scenarioId === 'user-registration' && allEvents.length > 0 && (
                <RegistrationFlowView events={allEvents} />
              )}
            </LearnPanel>
          </DockTabPanel>
        </SessionDock>
      </div>
    </div>
  )
}

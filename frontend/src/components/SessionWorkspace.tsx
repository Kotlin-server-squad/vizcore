import { useState, useEffect, useMemo, useRef, useCallback } from 'react'
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
import { categorizeEvents, useEventCategories } from '@/hooks/use-event-categories'
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
import { CoroutineCanvas, type MainViewMode } from './workspace/CoroutineCanvas'
import { TimelineView } from './workspace/timeline/TimelineView'
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
import type { CoroutineNode, ThreadActivity, VizEvent } from '@/types/api'
import { CoroutineState } from '@/types/api'
import { isDemoSessionId, sessionDisplayName } from '@/lib/session-kind'
import { UNKNOWN_STATE } from '@/lib/cursor-coroutines'
import { metricsAt } from '@/lib/moment-metrics'
import { useDockLayout } from '@/hooks/use-dock-layout'
import { deriveRunOutcome } from '@/lib/run-outcome'
import { useRecordedSpan } from '@/hooks/use-recorded-span'
import { countEvidence, evidenceTabLabel } from '@/lib/evidence-summary'
import { openingDockTab, rememberDockTab, rememberedDockTab } from '@/lib/dock-tab-memory'
import { projectActivity } from '@/lib/activity'
import { eventSubject, resourceIds, resourceNames, type Selection } from '@/lib/selection'
import type { CoroutinePicker } from './sync/CoroutineName'
import { useLookCard, useLookSlot } from '@/looks/look-context'
import { useOneMoment, type ShareBinding } from '@/hooks/use-one-moment'
import { MomentSummary } from './workspace/MomentSummary'
import { useSessionTimeline } from '@/hooks/use-session-timeline'

const EMPTY_COROUTINES: CoroutineNode[] = []

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
  /** A shared link's token and the cut it loaded (Part 8, M-11): its reads go through the token, bound to that cut. */
  share?: ShareBinding
}

export function SessionWorkspace({
  sessionId,
  scenarioId,
  scenarioName,
  readOnly = false,
  startLive = false,
  share,
}: SessionWorkspaceProps) {
  const {
    data: storedEvents,
    isPending: historyPending,
    isError: historyFailed,
  } = useSessionEvents(sessionId)
  const [streamEnabled, setStreamEnabled] = useState(false)
  const [viewMode, setViewMode] = useState<MainViewMode>('graph')
  // The page's one selection (S-2): a coroutine or a resource, read by the
  // tree, the Inspector, Evidence and Events. Set only in the live view, so
  // replay and shared renders stay non-interactive (PD-01).
  const [selection, setSelection] = useState<Selection | null>(null)
  const selectedCoroutineId = selection?.kind === 'coroutine' ? selection.id : null
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
  // A look may start the dock smaller, so its cards fit the first view (card.ts `dockShare`).
  const dock = useDockLayout(useLookCard().dockShare)
  // The layout around the main view and the dock is the look's (PR L0); Classic's by default.
  const sessionBody = useLookSlot('SessionBody')
  // Run / Reset / Clear (S-3). Mutation hooks only — nothing is requested until
  // an action is used, and none is offered in the shared view.
  const scenarioActions = useScenarioActions({
    sessionId,
    scenarioId: scenarioId ?? '',
    scenarioName,
    onClearEvents: () => {
      clearEvents()
      setSelection(null)
    },
    onRefetch: () => void refetch(),
  })
  const hasScenario = !!scenarioId
  // Live-view gate (PD-01): the Surface-001 IDE-dock is LIVE-ONLY. Replay and
  // read-only shared modes keep the existing tabbed, non-interactive layout.
  const isLiveView = !replayActive && !readOnly

  // Panel data source: replay cursor view-models vs. live snapshot (D-17).
  const panelEvents = replayActive ? replay.visibleEvents : allEvents
  const projectedAtCursor = useMemo(
    () => (replayActive ? projectCoroutines(replay.visibleEvents) : []),
    [replayActive, replay.visibleEvents],
  )
  // One moment for every part (Part 8, S-11): the replay cursor or a shared link's loaded cut, and how reads bind to it.
  const oneMoment = useOneMoment({
    sessionId,
    share,
    readOnly,
    replayActive,
    replay,
    projectedAtCursor,
    allEvents,
  })
  const panelCoroutines = replayActive ? oneMoment.coroutines : (session?.coroutines ?? EMPTY_COROUTINES)
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
      // A state unknown at the replay cursor is not known to be unfinished (Part 8, M-9).
      if (c.parentId && !TERMINAL_STATES.has(c.state as CoroutineState) && c.state !== UNKNOWN_STATE) {
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

  // The Timeline (Part 10, S-10): the tree's rows over time, at the page's moment, built only while it is shown.
  const sessionTimeline = useSessionTimeline({
    enabled: viewMode === 'timeline',
    replayActive,
    replayEvents: replay.events,
    cursorEvent: replay.currentEvent,
    events: allEvents,
    droppedCount,
    rows: renderedCoroutines,
    selected: selectedCoroutine,
  })

  // A demo's run outcome (S-3); an attached app has none.
  const isDemo = hasScenario || isDemoSessionId(sessionId)
  const outcome = useMemo(() => (isDemo && session ? deriveRunOutcome(session) : null), [isDemo, session])
  // The run's length over everything the page has held: the history loaded
  // with it and whatever streamed in since — kept when the view is paused.
  const runSpanNanos = useRecordedSpan(storedEvents ?? [], liveEvents)
  // Where the replay cursor is, on the replay clock (the view pill, Part 8 M-2).
  const replayAtNanos = oneMoment.replayAtNanos

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
  // Which Evidence sections have anything, from the same events their contents
  // read: in replay the cursor's (a resource created later is not reported
  // yet), live the shown ones (cleared events capture nothing). The rung keeps
  // the whole session's categories.
  const evidenceCategories = useMemo(() => categorizeEvents(panelEvents), [panelEvents])
  // What each coroutine waits for or holds (S-6), and the resources' names,
  // from the same events as the rest of the page: live, paused or at the
  // replay cursor — so they refresh with every new event.
  const activity = useMemo(() => projectActivity(panelEvents), [panelEvents])
  const names = useMemo(() => resourceNames(panelEvents), [panelEvents])
  const resources = useMemo(() => resourceIds(panelEvents), [panelEvents])
  // A coroutine the snapshot does not hold is not offered as a selection:
  // selecting it would be cleared at once.
  const coroutineIds = useMemo(() => new Set(panelCoroutines.map(c => c.id)), [panelCoroutines])
  const subjectOf = useCallback(
    (event: VizEvent) => {
      const subject = eventSubject(event)
      return subject?.kind === 'coroutine' && !coroutineIds.has(subject.id) ? null : subject
    },
    [coroutineIds],
  )
  const picker = useMemo<CoroutinePicker>(
    () => ({
      select: (id: string) => setSelection({ kind: 'coroutine', id }),
      canSelect: (id: string) => coroutineIds.has(id),
    }),
    [coroutineIds],
  )
  const selectionLabel =
    selection === null
      ? null
      : selection.kind === 'coroutine'
        ? (selectedCoroutine?.label ?? selection.id)
        : (names.get(selection.id) ?? selection.id)
  // A selection made inside a tab keeps you there; a resource opens its
  // Evidence section (S-2), which EvidencePanels unfolds and scrolls to.
  const selectFromEvent = (next: Selection) => {
    setSelection(next)
    if (next.kind === 'resource') selectDockTab('evidence')
  }
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
  // The same for a resource no longer named by the page's events (Clear
  // events, or a history trimmed past it): no Evidence target, no events.
  // In replay a coroutine the cursor has not reached yet keeps its selection (Part 8, M-5): it is cleared only when it
  // is not in the replay at all.
  const keptIds = replayActive ? oneMoment.replayCoroutineIds : coroutineIds
  // While an incomplete replay's summaries are read, a coroutine created before it may still come back from them.
  const summaryPending = replayActive && oneMoment.summaryPending
  useEffect(() => {
    if (selection === null) return
    const gone =
      selection.kind === 'coroutine'
        ? !coroutineIds.has(selection.id) && !keptIds.has(selection.id) && !summaryPending
        : !resources.has(selection.id) && !replayActive
    if (gone) setSelection(null)
  }, [selection, coroutineIds, keptIds, resources, replayActive, summaryPending])
  const pendingSelectionLabel =
    replayActive && selection?.kind === 'coroutine' && !selectedCoroutine
      ? (oneMoment.replayLabels.get(selection.id) ??
        session?.coroutines.find(c => c.id === selection.id)?.label ??
        selection.id)
      : null
  // A coroutine the summaries gave the tree is not missing at the cursor: their read is updating (M-9).
  const pendingSelectionNote =
    selection?.kind === 'coroutine' && (oneMoment.summaryNodes.has(selection.id) || summaryPending)
      ? 'updating to this moment…'
      : undefined
  // The session metrics at the moment, from the coroutines the tree shows and the events up to it (M-1).
  const momentMetrics = useMemo(
    () => (oneMoment.moment ? metricsAt(panelEvents, panelCoroutines) : null),
    [oneMoment.moment, panelEvents, panelCoroutines],
  )

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
    <div data-look-part="workspace" className="flex min-h-0 flex-1 flex-col gap-3">
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
        // A shared view reloads the whole page: the payload, its health and its cut come back together (M-11).
        onRefetch={readOnly ? () => window.location.reload() : () => refetch()}
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
          startNote={oneMoment.startNote}
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

      {/* The main view and the dock share the rest of the page (S-1), laid
          out by the look (Classic: a column, the main view above the dock). */}
      {sessionBody({
        sharedRef: dock.sharedRef,
        overview: { counts: stateCounts, coroutines: panelCoroutines, activity },
        main: (
          <CoroutineCanvas
            coroutines={renderedCoroutines}
            viewMode={viewMode}
            onViewModeChange={setViewMode}
            timeline={
              sessionTimeline.timeline && (
                <TimelineView
                  timeline={sessionTimeline.timeline}
                  rows={renderedCoroutines}
                  cursor={sessionTimeline.cursor}
                  note={sessionTimeline.note}
                  selectedId={selectedCoroutineId}
                  onSelect={(id: string) => {
                    setSelection({ kind: 'coroutine', id })
                    selectDockTab('inspector')
                  }}
                />
              )
            }
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
            activity={activity}
            // One selection in every mode (S-2, S-11): in replay and in a shared
            // link the Inspector shows it at the page's moment (Part 8, M-5).
            onSelect={(id: string) => {
              setSelection({ kind: 'coroutine', id })
              // A selection is read in the Inspector: show it (S-4).
              selectDockTab('inspector')
            }}
            panelRef={panelRef}
            maximised={dock.maximised}
            onToggleMaximise={dock.toggleMaximise}
          />
        ),
        // The dock (S-1): one tab per question (S-4). Maximise hides it,
        // never unmounts it, and so do folding and switching tabs: a panel
        // that re-subscribed later would refetch a stale query (#124).
        dock: (
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
                coroutine={selectedCoroutine}
                readOnly={readOnly}
                // Outside the live view its reads are bound to the page's moment (Part 8, M-3).
                moment={oneMoment.view}
                unavailableReason={oneMoment.unavailableReason}
                pendingSelectionLabel={pendingSelectionLabel}
                pendingSelectionNote={pendingSelectionNote}
                // T-3's session summary: live, from /metrics; at a moment (replay, shared link),
                // the same tiles computed from what the tree shows at that moment (M-1).
                emptyState={
                  isLiveView ? (
                    <SessionSummary
                      sessionId={sessionId}
                      enabled={!readOnly}
                      coroutineCount={session.coroutineCount}
                      eventCount={session.eventCount}
                    />
                  ) : oneMoment.moment && momentMetrics ? (
                    <MomentSummary
                      metrics={momentMetrics}
                      moment={oneMoment.moment}
                      coroutineCount={panelCoroutines.length}
                      incomplete={oneMoment.incomplete}
                    />
                  ) : undefined
                }
                threadActivity={panelThreadActivity}
                // Back to the session summary (T-3) from a selection.
                onClearSelection={() => setSelection(null)}
                activity={selectedCoroutine ? (activity.get(selectedCoroutine.id) ?? null) : null}
                // While the Timeline is the main view, its one sentence for the selected row (S-10).
                sentence={sessionTimeline.sentence}
              />
            </DockTabPanel>
            <DockTabPanel id="evidence" selected={dockTab === 'evidence'}>
              {/* What the resources did (M-1/M-2), and — where the rung is the
                  reason nothing was captured — what unlocks it (D-5). */}
              <EvidencePanels
                sessionId={sessionId}
                rung={rung}
                categories={evidenceCategories}
                counts={evidenceCounts}
                replayActive={replayActive}
                readOnly={readOnly}
                threadActivity={panelThreadActivity}
                captureStartNanos={captureStartNanos}
                history={historyFailed ? 'unavailable' : historyPending ? 'loading' : 'ready'}
                events={panelEvents}
                selection={selection}
                picker={picker}
                onSelectResource={setSelection}
                moment={oneMoment.moment}
              />
            </DockTabPanel>
            <DockTabPanel id="events" selected={dockTab === 'events'}>
              {/* Mounted only while shown: a long event list is the heaviest
                  render on the page, and a hidden one would re-render on every
                  live flush. It fetches nothing, so mounting later costs no
                  request. Every other panel stays mounted and is hidden. */}
              {dockTab === 'events' && (
                <EventsPanel
                  events={panelEvents}
                  selection={selection}
                  selectionLabel={selectionLabel}
                  subjectOf={subjectOf}
                  onSelect={selectFromEvent}
                  onShowAll={() => setSelection(null)}
                  resourceNames={names}
                />
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
        ),
      })}
    </div>
  )
}

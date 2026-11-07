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
import { CoroutineCanvas } from './workspace/CoroutineCanvas'
import { SessionDock } from './workspace/SessionDock'
import { Inspector } from './workspace/Inspector/Inspector'
import { EventsPanel } from './workspace/EventsPanel'
import { DockTabs, DockTabPanel, type DockTabId } from './workspace/DockTabs'
import { LearnPanel } from './workspace/LearnPanel'
import { SessionSummary } from './workspace/SessionSummary'
import { EvidencePanels } from './workspace/EvidencePanels'
import { ChecksModal } from './workspace/ChecksModal'
import { ProblemsPanel, type ChecksLine } from './workspace/ProblemsPanel'
import { ConnectionFacts } from './workspace/ConnectionFacts'
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
import { RoundBar, type RoundOption } from './workspace/RoundBar'
import type { CanvasNote } from './workspace/CoroutineCanvas'
import { useRounds, useRoundSearch, type SubmittedFind } from '@/hooks/use-rounds'
import { LIVE_EVENTS_MAX } from '@/lib/live-events'
import {
  capNote,
  capRendered,
  defaultChoice,
  findResults,
  hasRoundFacts,
  poolFor,
  resolveChoice,
  revealPlan,
  cutPath,
  roundOptionLabel,
  withForced,
  ungroupedCount,
  unknownRoundCount,
  vizcoreCount,
  asOfLabel,
  type CapScope,
  type RoundChoice,
} from '@/lib/round-view'
import type { RoundEntry } from '@/types/api'
import { groupIdOf, groupSiblings, isGroupId, type NameGroup } from '@/lib/name-groups'
import { GroupSummary } from './workspace/Inspector/GroupSummary'
import { useOneMoment, type ShareBinding } from '@/hooks/use-one-moment'
import { MomentSummary } from './workspace/MomentSummary'
import { useSessionHealth, type HealthMode } from '@/hooks/use-session-health'
import { useChecksStatus } from '@/hooks/use-checks-status'
import { useQuietRefresh } from '@/hooks/use-quiet-refresh'
import { asOfText, problemOf } from '@/lib/health-text'

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

const ALL_ROUNDS: RoundChoice = { kind: 'all' }
const NO_IDS: ReadonlySet<string> = new Set()

/** How the round picker names a choice. */
function choiceValue(choice: RoundChoice): string {
  return choice.kind === 'round' ? `r|${choice.roundId}` : choice.kind
}

/** Re-read the round list at most this often while live, after a snapshot refresh (Part 6 plan, "Requests"). */
const ROUNDS_REFRESH_MS = 15_000
/** ...and when the picker gets focus, if the list is older than this. */
const ROUNDS_FOCUS_REFRESH_MS = 2_000

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
  const [viewMode, setViewMode] = useState<'graph' | 'list'>('graph')
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
  // The last checks run (T-8): read once, again after a run in this visit; a shared link has the one it loaded.
  const checksStatus = useChecksStatus(sessionId, !readOnly)
  const refetchChecks = checksStatus.refetch
  useEffect(() => {
    if (validation.data) void refetchChecks()
  }, [validation.data, refetchChecks])
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
  const metricsLeakIds = useMemo(
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
  // The session's health at the page's moment (Part 7, P-1): live; at the replay cursor; or as a shared link loaded it.
  const healthMode: HealthMode = replayActive
    ? oneMoment.view
      ? { kind: 'moment', view: oneMoment.view }
      : { kind: 'loaded', health: null, unavailable: null }
    : readOnly
      ? { kind: 'loaded', health: share?.health ?? null, unavailable: share?.unavailable ?? null }
      : { kind: 'live', pollMs: fallbackPollMs ? fallbackPollMs * 2 : false }
  const sessionHealth = useSessionHealth({ sessionId, mode: healthMode })
  const health = sessionHealth.health
  // The Long-lived filter's coroutines (T-5): live, /metrics' complete list; at a moment, the health's (its first 50).
  const leakIds = useMemo(
    () =>
      !replayActive && !readOnly
        ? metricsLeakIds
        : new Set((health?.observations.longLived.items ?? []).map(o => o.coroutineId)),
    [replayActive, readOnly, metricsLeakIds, health],
  )
  // While the stream is on, a quiet or disconnected app still moves the connection pill and the health (P-1).
  useQuietRefresh({
    enabled: streamEnabled && isConnected && !replayActive && !readOnly,
    reads: [
      { updatedAt: dataUpdatedAt, refetch: () => void refetch() },
      { updatedAt: sessionHealth.updatedAt, refetch: sessionHealth.retry },
    ],
  })
  // --- Big apps (S-12, T-4): one round at a time, find in all rounds, the vizcore toggle. Live and paused view
  // only; replay and the shared view keep today's page (Part 8 binds them to one moment). Rounds and origins are the
  // server's (snapshot `roundId`/`origin`, `/rounds`); nothing here decides what a round is (D-8).
  // A page opened for a demo (its scenario, or an id vizcore mints for its runs) is a demo whatever the server's
  // kind says: the server reads kind from the id, and some demo runs' ids (sync-…) read ATTACHED there.
  const attached = !hasScenario && !isDemoSessionId(sessionId) && session?.kind !== 'DEMO'
  const roundFacts = isLiveView && hasRoundFacts(session?.coroutines ?? [])
  const [includeVizcore, setIncludeVizcore] = useState(false)
  const rounds = useRounds(sessionId, { includeVizcore, enabled: roundFacts })
  const roundsMode = roundFacts && rounds.status === 'success'
  // The opening view is pinned once, when the first list arrives: new rounds never move it.
  const [pinnedChoice, setPinnedChoice] = useState<RoundChoice | null>(null)
  if (roundsMode && pinnedChoice === null) setPinnedChoice(defaultChoice({ attached, firstRound: rounds.rounds[0] }))
  const [pickedChoice, setPickedChoice] = useState<RoundChoice | null>(null)
  // Shown past the cap: a found coroutine and its ancestors (S-12). Cleared when the user picks another view.
  const [forcedIds, setForcedIds] = useState<ReadonlySet<string>>(NO_IDS)
  const [reveal, setReveal] = useState<{ id: string; key: number } | undefined>(undefined)
  const forget = useCallback(() => setForcedIds(NO_IDS), [])
  const vizRule = useMemo(() => ({ includeVizcore }), [includeVizcore])
  const choice = useMemo<RoundChoice>(() => {
    if (!roundsMode) return ALL_ROUNDS
    const fallback = resolveChoice(pinnedChoice ?? ALL_ROUNDS, panelCoroutines, ALL_ROUNDS, vizRule)
    return resolveChoice(pickedChoice ?? fallback, panelCoroutines, fallback, vizRule)
  }, [roundsMode, pinnedChoice, pickedChoice, panelCoroutines, vizRule])
  // The coroutines this view shows, before the state filter and the cap.
  const viewCoroutines = useMemo(
    () => (roundsMode ? poolFor(panelCoroutines, choice, vizRule) : panelCoroutines),
    [roundsMode, panelCoroutines, choice, vizRule],
  )
  // The leak set as the view sees it: the Long-lived chip counts this view only.
  const viewLeakIds = useMemo(() => {
    if (!roundsMode) return leakIds
    const ids = new Set(viewCoroutines.map(c => c.id))
    return new Set([...leakIds].filter(id => ids.has(id)))
  }, [roundsMode, leakIds, viewCoroutines])

  // Group by name (S-12): on by default for an attached app, offered where every shown coroutine shares one round (a
  // round, or the coroutines in no round); never in All rounds, where two siblings can be in different rounds.
  const [groupByName, setGroupByName] = useState<boolean | null>(null)
  const groupingAvailable = roundsMode && (choice.kind === 'round' || choice.kind === 'ungrouped')
  const grouping = groupingAvailable && (groupByName ?? attached)
  const [expandedGroups, setExpandedGroups] = useState<ReadonlySet<string>>(NO_IDS)

  // Active-only "What's running now" derivation (D-08). Default the live view to
  // the non-terminal set, cap rendered nodes at NODE_CAP, and surface the
  // remainder ("N more") + the collapsed completed aggregate ("Show completed").
  // When nothing is live any more the run is over, and hiding its coroutines
  // would leave an empty tree: a finished run shows all of them (spec Part 1).
  const { completedCount, activeCoroutines, allFinished } = useMemo(() => {
    const active = viewCoroutines.filter(c => !TERMINAL_STATES.has(c.state as CoroutineState))
    return {
      completedCount: viewCoroutines.length - active.length,
      activeCoroutines: active,
      allFinished: active.length === 0 && viewCoroutines.length > 0,
    }
  }, [viewCoroutines])
  // What the tree/graph render, and how many the cap leaves out — both from the
  // same pool, so "N more" always counts what this view would have shown.
  const { renderedCoroutines, cap, groups, poolSize } = useMemo(() => {
    // A named chip selects that state explicitly across the whole snapshot, and
    // bypasses the active-only default — you asked for completed, you get
    // completed. With no chip, the active set; everything when the user expands
    // the completed aggregate or when the run is over.
    const pool =
      stateFilter !== 'all'
        ? selectCoroutines(viewCoroutines, stateFilter, viewLeakIds)
        : showCompleted || allFinished
          ? viewCoroutines
          : activeCoroutines
    // A coroutine Find revealed is drawn even past the cap, with its ancestors, whatever the chip shows (S-12).
    const shown = withForced(pool, viewCoroutines, forcedIds)
    if (!grouping) {
      const capped = capRendered(shown, NODE_CAP, forcedIds)
      return { renderedCoroutines: capped.rendered, cap: capped, groups: undefined, poolSize: shown.length }
    }
    // Grouped over the whole view (every state), drawn from what the chip shows: a member finishing never dissolves
    // its group. The cap counts cards; the groups on a found coroutine's path stay drawn with it.
    const shownIds = new Set(shown.map(c => c.id))
    // (The shown coroutines are always part of the view: the pool and the forced path are both drawn from it.)
    const grouped = groupSiblings(viewCoroutines, expandedGroups, shownIds)
    const forcedCards = new Set(forcedIds)
    for (const c of shown) {
      const gid = forcedIds.has(c.id) ? groupIdOf(c) : null
      if (gid && grouped.groups.has(gid)) forcedCards.add(gid)
    }
    const capped = capRendered(grouped.nodes, NODE_CAP, forcedCards)
    // The note names the coroutines in the view (every state), as the group cards' counts do.
    return { renderedCoroutines: capped.rendered, cap: capped, groups: grouped.groups, poolSize: viewCoroutines.length }
  }, [stateFilter, showCompleted, allFinished, viewCoroutines, activeCoroutines, viewLeakIds, forcedIds, grouping, expandedGroups])

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

  // Children per parent in the view before the cap, so a capped parent says "199 of 262 children" (S-12).
  const childTotals = useMemo(() => {
    if (cap.hidden === 0) return undefined
    const totals = new Map<string, number>()
    // Grouped, a parent's children are cards (a group counts once), as drawn.
    const cards = groups ? groupSiblings(viewCoroutines, expandedGroups).nodes : viewCoroutines
    for (const c of cards) if (c.parentId) totals.set(c.parentId, (totals.get(c.parentId) ?? 0) + 1)
    return totals
  }, [cap.hidden, viewCoroutines, groups, expandedGroups])

  /** Counts for the state bar — the whole snapshot, never the capped subset. */
  const stateCounts = useMemo(
    () => deriveStateCounts(viewCoroutines, viewLeakIds),
    [viewCoroutines, viewLeakIds],
  )

  /** The selected coroutine itself — what the inspector reads (D-7). */
  const selectedCoroutine = useMemo(
    () => panelCoroutines.find(c => c.id === selectedCoroutineId) ?? null,
    [panelCoroutines, selectedCoroutineId],
  )

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
  // Whether there is a problem is the health's to say: the choice waits for its first answer, unless a Gallery demo
  // still running opens on Learn anyway.
  const learnFirst = hasScenario && (outcome?.outcome === 'notStarted' || outcome?.outcome === 'running')
  const healthSettled = sessionHealth.status === 'ready' || sessionHealth.status === 'error'
  if (openingTab === null && session && (learnFirst || healthSettled || rememberedDockTab(sessionId) !== null)) {
    setOpeningTab(
      rememberedDockTab(sessionId) ??
        openingDockTab({
          startedAsDemo: hasScenario,
          outcome: outcome?.outcome ?? null,
          attached,
          problems: (health?.problemCount ?? 0) > 0,
        }),
    )
  }
  const dockTab: DockTabId = pickedTab ?? openingTab ?? 'inspector'
  const selectDockTab = (tab: DockTabId) => {
    setPickedTab(tab)
    rememberDockTab(sessionId, tab)
    // A tab chosen on a folded dock is a tab you want to read.
    if (dock.folded) dock.toggleFold()
  }

  // --- The round bar (S-12). ---
  const captureStart = session?.captureStartAppNanos ?? null
  const liveStream = streamEnabled && !replayActive && !readOnly
  // Every round entry the page has read (the list, find results), so a chosen round keeps an option and its anchor.
  const seenEntries = useRef(new Map<string, RoundEntry>())
  for (const e of rounds.rounds) seenEntries.current.set(e.roundId, e)
  const [findQuery, setFindQuery] = useState<SubmittedFind | null>(null)
  const [findOpen, setFindOpen] = useState(false)
  // Read only while the results are open: a closed search sends nothing, whatever the toggle does.
  const search = useRoundSearch(sessionId, { q: roundsMode && findOpen ? findQuery : null })
  for (const e of search.data?.items ?? []) seenEntries.current.set(e.roundId, e)
  const vizcoreN = useMemo(() => vizcoreCount(panelCoroutines), [panelCoroutines])
  const ungroupedN = useMemo(() => ungroupedCount(panelCoroutines, vizRule), [panelCoroutines, vizRule])
  const unknownN = useMemo(() => (roundsMode ? unknownRoundCount(panelCoroutines) : 0), [roundsMode, panelCoroutines])
  // Members per round as the snapshot holds them (fresh with every refresh, so they agree with the tree).
  const membersByRound = useMemo(() => {
    const counts = new Map<string, number>()
    for (const c of panelCoroutines) {
      if (c.roundId && (includeVizcore || c.origin !== 'VIZCORE')) counts.set(c.roundId, (counts.get(c.roundId) ?? 0) + 1)
    }
    return counts
  }, [panelCoroutines, includeVizcore])
  const roundOptions = useMemo<RoundOption[]>(() => {
    const listed = rounds.rounds.map(e => ({
      value: `r|${e.roundId}`,
      // The snapshot's count, zero included: the picker never claims members the canvas cannot draw.
      label: roundOptionLabel(e, membersByRound.get(e.roundId) ?? 0, captureStart),
      problem: e.problem,
    }))
    if (choice.kind !== 'round' || listed.some(o => o.value === `r|${choice.roundId}`)) return listed
    // The chosen round is not among those listed (found, or pushed past them): it keeps an option, without the
    // status words of an older read (H-2).
    const known = seenEntries.current.get(choice.roundId)
    const anchor = panelCoroutines.find(c => c.id === choice.anchorId)
    const kept = known
      ? roundOptionLabel(known, membersByRound.get(choice.roundId) ?? 0, captureStart, { status: false })
      : `${anchor?.label ?? choice.anchorId} · ${membersByRound.get(choice.roundId) ?? 0} coroutines`
    return [{ value: `r|${choice.roundId}`, label: kept, problem: false }, ...listed]
  }, [rounds.rounds, membersByRound, captureStart, choice, panelCoroutines])
  const toChoice = (value: string): RoundChoice => {
    if (value === 'all' || value === 'ungrouped' || value === 'unknown') return { kind: value }
    const roundId = value.slice(2)
    return { kind: 'round', roundId, anchorId: seenEntries.current.get(roundId)?.anchorId ?? roundId }
  }
  const pickView = (next: RoundChoice) => {
    setPickedChoice(next)
    forget()
  }
  // Re-read the list after a snapshot refresh while live, at most every 15 s; never while paused (it would run ahead
  // of the frozen tree).
  const roundsRef = useRef(rounds)
  roundsRef.current = rounds
  useEffect(() => {
    const r = roundsRef.current
    // A failed list is re-read on the same rule, so a transient failure does not leave the page without rounds.
    if (!roundFacts || !liveStream || r.isFetching) return
    if (Date.now() - Math.max(r.dataUpdatedAt, r.errorUpdatedAt) >= ROUNDS_REFRESH_MS) void r.refetch()
  }, [dataUpdatedAt, roundFacts, liveStream])
  const chooseFound = (id: string) => {
    const plan = revealPlan(id, panelCoroutines, {
      stateFilter,
      includeVizcore,
      leakIds,
      anchorOf: roundId => seenEntries.current.get(roundId)?.anchorId,
    })
    if (!plan) return
    setPickedChoice(plan.choice)
    setStateFilter(plan.stateFilter)
    if (plan.showCompleted) setShowCompleted(true)
    setIncludeVizcore(plan.includeVizcore)
    setForcedIds(plan.forcedIds)
    // Every collapsed group on the way to it opens: its own, and those its ancestors are members of (S-12).
    const byId = new Map(panelCoroutines.map(c => [c.id, c]))
    // The whole path, however deep; a coroutine met twice (a cycle in the reported parents) ends the walk.
    const toOpen: string[] = []
    const walked = new Set<string>()
    for (let c = byId.get(id); c && !walked.has(c.id); c = c.parentId ? byId.get(c.parentId) : undefined) {
      walked.add(c.id)
      const gid = groupIdOf(c)
      if (gid) toOpen.push(gid)
    }
    if (toOpen.length > 0) setExpandedGroups(prev => new Set([...prev, ...toOpen]))
    setSelection({ kind: 'coroutine', id })
    selectDockTab('inspector')
    // The Inspector is where a found coroutine is read: a maximised main view gives the dock back.
    if (dock.maximised) dock.toggleMaximise()
    setReveal(prev => ({ id, key: (prev?.key ?? 0) + 1 }))
    setFindOpen(false)
  }
  // "Show" (S-2, T-3): a drawn coroutine is found like a Find match; one the tree does not draw opens from its incident.
  // Either way focus moves to the Inspector's heading.
  const [inspectorFocus, setInspectorFocus] = useState<number | undefined>(undefined)
  const showProblem = (id: string) => {
    if (panelCoroutines.some(c => c.id === id)) chooseFound(id)
    else {
      setSelection({ kind: 'coroutine', id })
      selectDockTab('inspector')
      if (dock.maximised) dock.toggleMaximise()
    }
    setInspectorFocus(k => (k ?? 0) + 1)
  }
  const findView = (() => {
    if (!findOpen || !findQuery) return null
    if (search.status === 'pending') return { status: 'pending' as const, summary: '', groups: [], moreRounds: null, note: null, unsearched: null }
    if (search.status === 'error' || !search.data)
      return { status: 'error' as const, summary: '', groups: [], moreRounds: null, note: null, unsearched: null }
    return {
      status: 'ready' as const,
      // Bound to the rule the search was submitted with, as the server's counts and caps are.
      ...findResults(search.data, panelCoroutines, {
        captureStartAppNanos: captureStart,
        includeVizcore: findQuery.includeVizcore,
        attached,
        unsearched: unknownN,
      }),
    }
  })()
  // A capped view gets the bar too, for the Find its cap note points to (a one-round demo then shows Find only).
  const showRoundBar =
    roundsMode && (attached || rounds.total >= 2 || ungroupedN > 0 || unknownN > 0 || vizcoreN > 0 || cap.hidden > 0)
  const pickerChoices = roundOptions.length + 1 + (ungroupedN > 0 ? 1 : 0) + (unknownN > 0 ? 1 : 0)
  const roundBar = showRoundBar ? (
    <RoundBar
      showPicker={attached || pickerChoices > 2}
      value={choiceValue(choice)}
      options={roundOptions}
      allRounds
      problemLabel={`With a problem · ${asOfLabel({ asOfAppNanos: rounds.asOfAppNanos, asOfSeq: rounds.asOfSeq ?? 0 }, captureStart)}`}
      ungrouped={ungroupedN}
      unknownRound={unknownN}
      canLoadMore={rounds.canLoadMore}
      olderRounds={rounds.canLoadMore ? 0 : Math.max(0, rounds.total - rounds.rounds.length)}
      onChange={value => pickView(toChoice(value))}
      onLoadMore={rounds.loadMore}
      onPickerFocus={() => {
        if (liveStream && !rounds.isFetching && Date.now() - rounds.dataUpdatedAt > ROUNDS_FOCUS_REFRESH_MS) void rounds.refetch()
      }}
      vizcoreCount={vizcoreN}
      includeVizcore={includeVizcore}
      onToggleVizcore={setIncludeVizcore}
      groupToggle={
        groupingAvailable ? { checked: grouping, onChange: (next: boolean) => setGroupByName(next) } : undefined
      }
      originUnknown={attached && session?.origin === 'UNKNOWN'}
      find={{
        results: findView,
        onSubmit: text => {
          // The search keeps the vizcore rule it was submitted with: one request per submit.
          setFindQuery({ text, at: Date.now(), includeVizcore })
          setFindOpen(true)
        },
        onChoose: id => chooseFound(id),
        onOpenRound: roundId => {
          pickView({ kind: 'round', roundId, anchorId: seenEntries.current.get(roundId)?.anchorId ?? roundId })
          setFindOpen(false)
        },
        onClose: () => setFindOpen(false),
      }}
    />
  ) : attached && isLiveView && (session?.coroutines.length ?? 0) > 0 && (!roundFacts || rounds.status === 'error') ? (
    <div data-look-part="round-bar" className="border-b border-default-200 px-3 py-1.5 text-xs text-default-500">
      Rounds unavailable for this session
    </div>
  ) : null
  const capScope: CapScope = !roundsMode
    ? 'view'
    : choice.kind === 'round'
      ? 'round'
      : choice.kind === 'ungrouped'
        ? 'ungrouped'
        : choice.kind === 'unknown'
          ? 'unknown'
          : 'all'
  // A found coroutine deeper than Find draws (S-12): from what is actually drawn, and only while it is.
  const foundPathCut = useMemo(
    () =>
      reveal && forcedIds.size > 0
        ? cutPath(reveal.id, viewCoroutines, new Set(renderedCoroutines.map(c => c.id)))
        : null,
    [reveal, forcedIds, viewCoroutines, renderedCoroutines],
  )
  const canvasNotes: CanvasNote[] = [
    ...(liveStream && liveEvents.length >= LIVE_EVENTS_MAX
      ? [{ key: 'history' as const, text: `The Events tab, the second lines and the Locks, Channels, Flows and Jobs sections read the newest ${LIVE_EVENTS_MAX.toLocaleString('en-US')} events this page received.` }]
      : []),
    ...(foundPathCut
      ? [
          {
            key: 'path' as const,
            text: `The coroutine you found is ${foundPathCut.depth.toLocaleString('en-US')} levels below its round’s root: ${foundPathCut.missing.toLocaleString('en-US')} of its ancestors are not drawn.`,
          },
        ]
      : []),
    ...(isLiveView && (session?.summaryOnlyCount ?? 0) > 0
      ? [{ key: 'summary-only' as const, text: `${session!.summaryOnlyCount!.toLocaleString('en-US')} coroutines are known only from their summaries (their events were trimmed) and are not drawn.` }]
      : []),
  ]
  const emptyReason =
    roundsMode && viewCoroutines.length === 0 && !includeVizcore && vizcoreN > 0 && choice.kind !== 'round'
      ? ('vizcore-hidden' as const)
      : roundsMode && choice.kind === 'round'
        ? ('round' as const)
        : undefined

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
  // The selected group, its members (for the Events tab) and the names of its members (S-12).
  const selectedGroup: NameGroup | undefined = selection?.kind === 'group' ? groups?.get(selection.id) : undefined
  const selectedGroupMembers = useMemo(
    () => (selectedGroup ? new Set(selectedGroup.memberIds) : undefined),
    [selectedGroup],
  )
  const memberLabel = useCallback(
    (id: string) => panelCoroutines.find(c => c.id === id)?.label ?? id,
    [panelCoroutines],
  )
  const toggleGroup = useCallback((id: string) => {
    setExpandedGroups(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])
  const selectionLabel =
    selection === null
      ? null
      : selection.kind === 'coroutine'
        ? (selectedCoroutine?.label ?? selection.id)
        : selection.kind === 'group'
          ? (() => {
              const g = groups?.get(selection.id)
              return g ? `${g.label} · ${g.memberIds.length.toLocaleString('en-US')} coroutines` : selection.id
            })()
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
        ? !coroutineIds.has(selection.id) && !keptIds.has(selection.id) && !summaryPending && !problemOf(health, selection.id)
        : selection.kind === 'group'
          ? !groups?.has(selection.id)
          : !resources.has(selection.id) && !replayActive
    if (gone) setSelection(null)
  }, [selection, coroutineIds, keptIds, resources, replayActive, groups, summaryPending, health])
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

  // --- Problems (Part 7): what the Inspector and the Problems tab need from the health. ---
  // A problem whose coroutine the page does not draw opens from its incident (P-5).
  const problemOnly = useMemo(() => {
    if (selection?.kind !== 'coroutine' || selectedCoroutine) return null
    const problem = problemOf(health, selection.id)
    if (!problem) return null
    const label = problem.kind === 'FAILED' ? problem.failure.name : problem.incident.name
    return { id: selection.id, label: label ?? selection.id, problem }
  }, [selection, selectedCoroutine, health])
  // The health lists only part of its problems: a coroutine it does not list may still have one (P-6).
  const problemsCut =
    !!health &&
    (health.incidents.total > health.incidents.items.length ||
      health.incidents.items.some(i => i.type === 'FAILED_ROUND' && i.failures.total > i.failures.items.length))
  // The newest live event of the selected coroutine: its timeline is re-read after it (P-1).
  const selectedLiveSeq = useMemo(() => {
    if (!liveStream || !selectedCoroutineId) return undefined
    for (let i = liveEvents.length - 1; i >= 0; i--) {
      const e = liveEvents[i] as { coroutineId?: string; seq: number }
      if (e.coroutineId === selectedCoroutineId) return e.seq
    }
    return undefined
  }, [liveStream, selectedCoroutineId, liveEvents])
  const memberLabelOrUndefined = (id: string) => panelCoroutines.find(c => c.id === id)?.label ?? undefined
  // The checks status line (T-8).
  const latestSeq = allEvents.length > 0 ? (allEvents[allEvents.length - 1]?.seq ?? null) : null
  const checksLine: ChecksLine = readOnly
    ? share?.checks
      ? { kind: 'status', status: share.checks, latestSeq: null }
      : { kind: 'unavailable' }
    : checksStatus.data
      ? {
          kind: 'status',
          status: checksStatus.data,
          latestSeq,
          onRun: () => {
            validation.validate()
            setChecksOpen(true)
          },
          onOpen: () => setChecksOpen(true),
        }
      : checksStatus.isError
        ? { kind: 'unavailable' }
        : { kind: 'loading' }

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
        onRefetch={
          readOnly
            ? () => window.location.reload()
            : () => {
                void refetch()
                if (roundFacts) void rounds.refetch()
                if (!replayActive) sessionHealth.retry()
              }
        }
        onOpenShares={() => setSharesOpen(true)}
        onOpenChecks={() => setChecksOpen(true)}
        // Run/Reset/Clear are mutations, gated OFF in the read-only shared view (T-03-22).
        scenario={scenarioId && !readOnly ? scenarioActions : undefined}
        panelRef={panelRef}
        panelEvents={panelEvents}
        viewMode={viewMode}
        onRecord={recordReplay.canRecord ? recordReplay.startRecording : undefined}
        // An attached app's connection, last data and start time (S-3, T-2); a shared link shows what it loaded.
        connection={attached && !readOnly ? <ConnectionFacts session={session} /> : undefined}
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
        lastRun={checksStatus.data?.lastRun ?? null}
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
        overview: { counts: stateCounts, coroutines: viewCoroutines, activity },
        main: (
          <CoroutineCanvas
            coroutines={renderedCoroutines}
            viewMode={viewMode}
            onViewModeChange={setViewMode}
            completedCount={completedCount}
            allFinished={allFinished}
            showCompleted={showCompleted}
            onToggleCompleted={() => {
              setShowCompleted(prev => !prev)
              // A filter the user picks after a find is theirs: the found path is no longer forced (S-12).
              forget()
            }}
            capNote={capNote({
              total: cap.total,
              rendered: cap.rendered.length,
              found: cap.found,
              cap: NODE_CAP,
              scope: capScope,
              canFind: roundsMode && choice.kind !== 'unknown',
              coroutines: groups && groups.size > 0 ? poolSize : undefined,
            })}
            notes={canvasNotes}
            roundBar={roundBar}
            loadingRounds={roundFacts && attached && rounds.status === 'pending'}
            emptyReason={emptyReason}
            reveal={reveal}
            inRound={roundsMode && choice.kind === 'round'}
            childTotals={childTotals}
            pendingChildCounts={pendingChildCounts}
            stateFilter={stateFilter}
            stateBar={
              // The state bar (D-4): what the session is doing, and the canvas filter.
              <StateBar
                bare
                counts={stateCounts}
                filter={stateFilter}
                onFilterChange={next => {
                  setStateFilter(next)
                  forget()
                }}
              />
            }
            isDemo={isDemo}
            // A selected group is ringed like a coroutine (S-12).
            selectedCoroutineId={selection?.kind === 'group' ? selection.id : selectedCoroutineId}
            activity={activity}
            // One selection in every mode (S-2, S-11): in replay and in a shared
            // link the Inspector shows it at the page's moment (Part 8, M-5).
            onSelect={(id: string) => {
              setSelection(isGroupId(id) ? { kind: 'group', id } : { kind: 'coroutine', id })
              // A selection is read in the Inspector: show it (S-4).
              selectDockTab('inspector')
            }}
            groups={groups}
            onToggleGroup={toggleGroup}
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
                // Problems ("what is wrong?") first, with a count only when there are problems (S-8).
                tabs={[
                  { id: 'problems', label: 'Problems', badge: health?.problemCount ?? 0 },
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
            <DockTabPanel id="problems" selected={dockTab === 'problems'}>
              <ProblemsPanel
                result={sessionHealth}
                captureStartAppNanos={captureStart}
                onPage={id => coroutineIds.has(id)}
                labelOf={memberLabelOrUndefined}
                onShow={showProblem}
                onLongLived={() => {
                  setStateFilter('leaks')
                  forget()
                  if (dock.maximised) dock.toggleMaximise()
                }}
                longLivedNote={
                  (replayActive || readOnly) &&
                  health &&
                  health.observations.longLived.total > health.observations.longLived.items.length
                    ? 'the filter shows the first 50'
                    : undefined
                }
                checks={checksLine}
              />
            </DockTabPanel>
            <DockTabPanel id="inspector" selected={dockTab === 'inspector'}>
              {selectedGroup && (
                <GroupSummary
                  sessionId={sessionId}
                  group={selectedGroup}
                  parentLabel={
                    selectedGroup.parentId
                      ? (panelCoroutines.find(c => c.id === selectedGroup.parentId)?.label ?? selectedGroup.parentId)
                      : null
                  }
                  memberLabel={memberLabel}
                  onRevealMember={chooseFound}
                  onToggle={toggleGroup}
                  onClearSelection={() => setSelection(null)}
                />
              )}
              {/* Kept mounted under a group's summary, so its queries stay subscribed (#124). */}
              <div hidden={!!selectedGroup}>
              <Inspector
                sessionId={sessionId}
                coroutine={selectedCoroutine}
                readOnly={readOnly}
                // Outside the live view its reads are bound to the page's moment (Part 8, M-3).
                moment={oneMoment.view}
                unavailableReason={oneMoment.unavailableReason}
                pendingSelectionLabel={problemOnly ? null : pendingSelectionLabel}
                problem={selectedCoroutine ? problemOf(health, selectedCoroutine.id) : null}
                problemAsOf={health ? asOfText(health.asOf, captureStart) : undefined}
                problemsCut={problemsCut}
                problemOnly={problemOnly}
                captureStartAppNanos={captureStart}
                focusKey={inspectorFocus}
                liveSeq={selectedLiveSeq}
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
                      counts={health?.counts}
                      captureStartAppNanos={captureStart}
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
              />
              </div>
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
                  groupMembers={selectedGroupMembers}
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

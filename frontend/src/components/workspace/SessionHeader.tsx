import type { ReactNode, RefObject } from 'react'
import {
  Button,
  Chip,
  Dropdown,
  DropdownItem,
  DropdownMenu,
  DropdownSection,
  DropdownTrigger,
  Tooltip,
} from '@heroui/react'
import { FiCheck, FiMoreHorizontal, FiPause, FiPlay, FiRadio } from 'react-icons/fi'
import { useCapabilities } from '@/hooks/use-capabilities'
import { useLook, useLookSlot } from '@/looks/look-context'
import { useLookExperiment } from '@/looks/experiment/LookExperimentLayer'
import { parseLook } from '@/looks/registry'
import { RUNG_LABEL, type Rung } from '@/lib/fidelity-rung'
import { formatDuration } from '@/lib/format-duration'
import type { RunOutcome, RunOutcomeFacts } from '@/lib/run-outcome'
import { ExportMenu } from '../export/ExportMenu'
import { RunScenarioButton } from './RunScenarioButton'
import type { ScenarioActions } from './use-scenario-actions'
import type { SessionSnapshot, VizEvent } from '@/types/api'

interface SessionHeaderProps {
  sessionId: string
  session: SessionSnapshot
  /** The scenario's name, or the app name the session id was minted from (#145). */
  title: string
  /** Which fidelity rung this session is on (D-6). */
  rung: Rung
  readOnly: boolean
  /** A demo run's outcome (S-3); null for an attached app. */
  outcome: RunOutcomeFacts | null
  /** From the first to the last event the page holds; shown once the run has ended. */
  runSpanNanos: number | null
  /** Live SSE stream state + toggle. */
  streamEnabled: boolean
  isConnected: boolean
  /** Why the stream is not connected, or null while healthy (#137). */
  streamError?: string | null
  /** Events the backend shed under overload since the stream started (#137). */
  droppedCount?: number
  /** Re-open the stream once its automatic retries are spent. */
  onReconnect?: () => void
  onToggleStream: () => void
  /** Replay state + toggle (D-01). */
  replayActive: boolean
  /** Time from the first event to the replay cursor's, on the app's clock. */
  replayAtNanos: number | null
  newEventsCount: number
  onToggleReplay: () => void
  onExitReplay: () => void
  /** When the shared view loaded its data (epoch ms). */
  loadedAtMs: number
  onRefetch: () => void
  onOpenShares: () => void
  /** Opens the session-checks report (M-3). */
  onOpenChecks: () => void
  /** Run / Reset / Clear — only for a scenario session outside the shared view. */
  scenario?: ScenarioActions
  /** ExportMenu wiring (ADR-018 / EXPT-01/02 / D-22). */
  panelRef: RefObject<HTMLDivElement | null>
  panelEvents: VizEvent[]
  viewMode: 'graph' | 'list'
  onRecord?: () => void
  /** The app connection, last data and start time of an attached app (S-3, T-2; Part 7), beside the view pill. */
  connection?: ReactNode
}

/**
 * The session's header (spec S-3, which revises triage T-7). It keeps three
 * facts apart: the run outcome of a demo (Running, Finished, Failed — a run
 * with any failed coroutine is Failed, in red), the app connection of an
 * attached app (`connection`: the pill, last data, start time) and the
 * view (updating, paused, replay, loaded). Then the actions: Run, then Run
 * again (demos, S-13), Replay, Pause view, Export and "⋯".
 *
 * Presentational apart from `useCapabilities`, which decides whether Share is
 * offered — nothing else in the workspace reads it.
 */
export function SessionHeader({
  sessionId,
  session,
  title,
  rung,
  readOnly,
  outcome,
  runSpanNanos,
  streamEnabled,
  isConnected,
  streamError = null,
  droppedCount = 0,
  onReconnect,
  onToggleStream,
  replayActive,
  replayAtNanos,
  newEventsCount,
  onToggleReplay,
  onExitReplay,
  loadedAtMs,
  onRefetch,
  onOpenShares,
  onOpenChecks,
  scenario,
  panelRef,
  panelEvents,
  viewMode,
  onRecord,
  connection,
}: SessionHeaderProps) {
  // Sharing is DB-backed (ADR-019); in memory mode the share routes are absent.
  // Gate the Share item on the server capability so we never offer an action
  // that 404s (explicit false only — stay enabled while still loading).
  const { data: capabilities } = useCapabilities()
  const sharingDisabled = capabilities?.sharingEnabled === false
  // The Look switch (PR L0): offered only where the page has one (not the shared view).
  const { look, setLook, looks } = useLook()
  // The look experiment, while it runs (null otherwise: the menu is as without it).
  const experiment = useLookExperiment()
  // A line under the title, if the look has one (Classic: none).
  const headerSummary = useLookSlot('HeaderSummary')
  // The page's own actions in "⋯".
  const actionItems = [
    // Owner only: a shared link can never re-share (ADR-019, T-03-22).
    ...(readOnly
      ? []
      : [
          <DropdownItem
            key="share"
            description={sharingDisabled ? 'Sharing requires storage.type=database' : undefined}
          >
            Share
          </DropdownItem>,
        ]),
    // Session checks (M-3) — validation is an action, not a tab.
    <DropdownItem key="checks">Checks</DropdownItem>,
    <DropdownItem key="refresh">Refresh</DropdownItem>,
    ...(scenario
      ? [
          <DropdownItem key="reset" description="Deletes this session and starts a fresh one">
            Reset session
          </DropdownItem>,
          <DropdownItem key="clear" description="Empties the live event list; keeps the session">
            Clear events
          </DropdownItem>,
        ]
      : []),
  ]

  return (
    <header data-look-part="header" className="space-y-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <h1 className="text-xl font-bold">{title}</h1>
        {headerSummary({
          outcome,
          runSpanNanos,
          spanIsFloor: droppedCount > 0,
          replayActive,
          replayAtNanos,
          events: panelEvents,
        })}
        {/* Which fidelity rung this session is on (D-6). */}
        <Chip size="sm" variant="flat" color={rung === 'demo' ? 'default' : 'primary'} data-testid="rung-badge">
          {RUNG_LABEL[rung]}
        </Chip>
        {/* A live region, so a screen reader hears a run fail without moving
            focus (#290). Always mounted for a demo, so the change is heard. */}
        {outcome && (
          <span role="status" aria-label="Run outcome" aria-atomic="true">
            <OutcomePill
              facts={outcome}
              spanNanos={runSpanNanos}
              // Events the backend shed may have been the first or the last.
              spanIsFloor={droppedCount > 0}
            />
          </span>
        )}
        <ViewPill
          readOnly={readOnly}
          replayActive={replayActive}
          replayAtNanos={replayAtNanos}
          streamEnabled={streamEnabled}
          isConnected={isConnected}
          streamError={streamError}
          loadedAtMs={loadedAtMs}
        />
        {connection}
        {!readOnly && streamEnabled && !isConnected && streamError && onReconnect && (
          <Button size="sm" variant="light" onPress={onReconnect}>
            Reconnect
          </Button>
        )}
        {droppedCount > 0 && (
          <Tooltip content="The backend shed these events under load, so the view may be missing detail. Structural events (create/complete) are never shed.">
            <Chip color="warning" variant="flat" size="sm" data-testid="dropped-count">
              {droppedCount === 1 ? '1 event dropped' : `${droppedCount} events dropped`}
            </Chip>
          </Tooltip>
        )}
        {/* New-events badge (D-02/D-04): clickable, exits replay + applies
            buffered events. Hidden when N = 0. */}
        {replayActive && newEventsCount > 0 && (
          <button type="button" aria-label="Exit replay and jump to live" onClick={onExitReplay}>
            <Chip size="sm" color="warning" variant="dot">
              {newEventsCount === 1 ? '1 new event' : `${newEventsCount} new events`}
            </Chip>
          </button>
        )}
        <span className="text-sm text-default-500">
          <span>{session.coroutineCount} coroutines</span> · <span>{session.eventCount} events</span>
        </span>

        <div className="flex-1" />

        <div data-look-part="header-actions" className="flex flex-wrap items-center gap-2">
          {scenario && outcome && (
            <RunScenarioButton
              outcome={outcome.outcome}
              isPending={scenario.isRunPending}
              isRunAgainPending={scenario.isRunAgainPending}
              onRun={() => void scenario.run()}
              onRunAgain={() => void scenario.runAgain()}
            />
          )}
          {/* Replay toggle (D-01) — always available, the shared view included. */}
          <Button
            size="sm"
            color={replayActive ? 'primary' : 'default'}
            variant={replayActive ? 'flat' : 'bordered'}
            startContent={<FiPlay aria-hidden />}
            onPress={onToggleReplay}
          >
            {replayActive ? 'Exit replay' : 'Replay'}
          </Button>
          {/* A shared session is a frozen capture: no live stream to pause (T-03-22). */}
          {!readOnly && (
            <Button
              size="sm"
              variant="bordered"
              startContent={streamEnabled ? <FiPause aria-hidden /> : <FiRadio aria-hidden />}
              onPress={onToggleStream}
            >
              {streamEnabled ? 'Pause view' : 'Resume view'}
            </Button>
          )}
          {/* Export is its own menu (PNG / SVG / JSON / Record): menus do not nest. */}
          <ExportMenu
            getPanelEl={() => panelRef.current}
            sessionId={sessionId}
            sessionName={title}
            events={panelEvents}
            panel={viewMode === 'graph' ? 'graph' : 'tree'}
            onRecord={onRecord}
          />
          <Dropdown>
            <DropdownTrigger>
              <Button size="sm" variant="bordered" isIconOnly aria-label="More actions">
                <FiMoreHorizontal aria-hidden />
              </Button>
            </DropdownTrigger>
            <DropdownMenu
              aria-label="More actions"
              disabledKeys={[
                ...(sharingDisabled ? ['share'] : []),
                ...(scenario?.isResetPending ? ['reset'] : []),
              ]}
              onAction={key => {
                if (key === 'share') onOpenShares()
                else if (key === 'checks') onOpenChecks()
                else if (key === 'refresh') onRefetch()
                else if (key === 'reset') void scenario?.reset()
                else if (key === 'clear') scenario?.clear()
                else if (key === RATE_LOOK_KEY) experiment?.openVote()
                else if (typeof key === 'string' && key.startsWith(LOOK_KEY)) {
                  const next = parseLook(key.slice(LOOK_KEY.length))
                  if (next !== look) experiment?.switched(look, next)
                  setLook?.(next)
                }
              }}
            >
              {setLook
                ? [
                    <DropdownSection key="actions" aria-label="Session actions" showDivider>
                      {actionItems}
                    </DropdownSection>,
                    // Trial looks for this page (PR L0); the losing ones are deleted after the trial.
                    <DropdownSection key="look" title="Look · trial" showDivider={experiment !== null}>
                      {looks.map(l => (
                        <DropdownItem
                          key={`${LOOK_KEY}${l.id}`}
                          textValue={l.label}
                          aria-current={l.id === look ? 'true' : undefined}
                          endContent={l.id === look ? <FiCheck aria-hidden /> : undefined}
                        >
                          {l.label}
                        </DropdownItem>
                      ))}
                    </DropdownSection>,
                    // The look experiment's vote, always reachable while it runs.
                    ...(experiment
                      ? [
                          <DropdownSection key="rate" aria-label="Rate this look">
                            <DropdownItem key={RATE_LOOK_KEY}>Rate this look</DropdownItem>
                          </DropdownSection>,
                        ]
                      : []),
                  ]
                : actionItems}
            </DropdownMenu>
          </Dropdown>
        </div>
      </div>
      {scenario?.errorText && (
        <p role="alert" className="text-sm text-danger">
          {scenario.errorText}
        </p>
      )}
    </header>
  )
}

/** Menu keys of the Look items: `look:a`. */
const LOOK_KEY = 'look:'

/** Menu key of "Rate this look" (the look experiment). */
const RATE_LOOK_KEY = 'rate-look'

function OutcomePill({
  facts: { outcome, failedCount, ended },
  spanNanos,
  spanIsFloor,
}: {
  facts: RunOutcomeFacts
  spanNanos: number | null
  spanIsFloor: boolean
}) {
  // A duration only once nothing runs any more: a failed run whose other
  // coroutines still run has no length yet.
  const span = ended && spanNanos !== null ? ` · ${formatDuration(spanNanos, { atLeast: spanIsFloor })}` : ''
  const failed = `${failedCount} ${failedCount === 1 ? 'coroutine' : 'coroutines'} failed`
  const text: Record<RunOutcome, string> = {
    notStarted: 'Not run yet',
    running: 'Running',
    finished: `Finished${span}`,
    failed: `Failed · ${failed}${span}`,
  }
  return (
    <Chip
      size="sm"
      variant="dot"
      color={outcome === 'failed' ? 'danger' : outcome === 'running' ? 'primary' : 'default'}
      data-testid="run-outcome"
      title={span ? 'From the first to the last event this page holds' : undefined}
      className={outcome === 'failed' ? 'border-danger text-danger' : undefined}
    >
      {text[outcome]}
    </Chip>
  )
}

function ViewPill({
  readOnly,
  replayActive,
  replayAtNanos,
  streamEnabled,
  isConnected,
  streamError,
  loadedAtMs,
}: {
  readOnly: boolean
  replayActive: boolean
  replayAtNanos: number | null
  streamEnabled: boolean
  isConnected: boolean
  streamError: string | null
  loadedAtMs: number
}) {
  const [text, color] = replayActive
    ? [replayAtNanos !== null ? `Replay at ${formatDuration(replayAtNanos)}` : 'Replay', 'primary' as const]
    : readOnly
      ? [`Loaded at ${new Date(loadedAtMs).toLocaleTimeString()}`, 'default' as const]
      : !streamEnabled
        ? ['View paused', 'default' as const]
        : isConnected
          ? ['Updating', 'success' as const]
          : [streamError ?? 'Connecting…', streamError ? ('danger' as const) : ('warning' as const)]
  return (
    <Chip size="sm" variant="dot" color={color} data-testid="view-status">
      {text}
    </Chip>
  )
}

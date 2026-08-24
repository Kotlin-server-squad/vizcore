import type { RefObject } from 'react'
import {
  Card,
  CardBody,
  CardHeader,
  Chip,
  Button,
  Tooltip,
} from '@heroui/react'
import { FiRefreshCw, FiRadio, FiGitBranch, FiList, FiPlay, FiShare2, FiCheckSquare } from 'react-icons/fi'
import { motion, AnimatePresence } from 'framer-motion'
import { useCapabilities } from '@/hooks/use-capabilities'
import { RUNG_LABEL, type Rung } from '@/lib/fidelity-rung'
import { ExportMenu } from '../export/ExportMenu'
import type { SessionSnapshot, VizEvent } from '@/types/api'

/**
 * The workspace's identity strip and toolbar.
 *
 * Presentational apart from `useCapabilities`, which lives here because the
 * server's sharing capability feeds exactly one thing — whether the Share
 * button is offered — and nothing else in the workspace reads it.
 */
interface SessionHeaderProps {
  sessionId: string
  session: SessionSnapshot
  scenarioName?: string
  hasScenario: boolean
  /** Which fidelity rung this session is on (D-6). */
  rung: Rung
  readOnly: boolean
  /** Live SSE stream state + toggle. */
  streamEnabled: boolean
  isConnected: boolean
  onToggleStream: () => void
  /** Replay state + toggle (D-01). */
  replayActive: boolean
  newEventsCount: number
  onToggleReplay: () => void
  onExitReplay: () => void
  /** Graph/list canvas mode. */
  viewMode: 'graph' | 'list'
  onViewModeChange: (next: 'graph' | 'list') => void
  onRefetch: () => void
  onOpenShares: () => void
  /** Opens the session-checks report (M-3) — validation, no longer a tab. */
  onOpenChecks: () => void
  /** ExportMenu wiring (ADR-018 / EXPT-01/02 / D-22). */
  panelRef: RefObject<HTMLDivElement | null>
  panelEvents: VizEvent[]
  onRecord?: () => void
}

export function SessionHeader({
  sessionId,
  session,
  scenarioName,
  hasScenario,
  rung,
  readOnly,
  streamEnabled,
  isConnected,
  onToggleStream,
  replayActive,
  newEventsCount,
  onToggleReplay,
  onExitReplay,
  viewMode,
  onViewModeChange,
  onRefetch,
  onOpenShares,
  onOpenChecks,
  panelRef,
  panelEvents,
  onRecord,
}: SessionHeaderProps) {
  // Sharing is DB-backed (ADR-019); in memory mode the share routes are absent.
  // Gate the Share affordance on the server capability so we never offer an
  // action that 404s (explicit false only — stay enabled while still loading).
  const { data: capabilities } = useCapabilities()
  const sharingDisabled = capabilities?.sharingEnabled === false

  return (
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
          <div className="flex items-center gap-2">
            <p className="font-mono text-sm text-default-500">{sessionId}</p>
            {/* Which fidelity rung this session is on (D-6). */}
            <Chip
              size="sm"
              variant="flat"
              color={rung === 'demo' ? 'default' : 'primary'}
              data-testid="rung-badge"
            >
              {RUNG_LABEL[rung]}
            </Chip>
          </div>
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
                onClick={onExitReplay}
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
            onPress={onRefetch}
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
                onPress={onToggleStream}
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
              onPress={onToggleReplay}
            >
              {replayActive ? 'Exit Replay' : 'Replay'}
            </Button>
          </div>

          <div className="flex items-center gap-2">
            {/* Share / Manage-shares trigger (D-11/D-13) — owner only, gated
                OFF in the read-only shared view so a shared link can never
                re-share (ADR-019, T-03-22). */}
            {!readOnly && (
              <Tooltip
                content="Sharing requires storage.type=database"
                isDisabled={!sharingDisabled}
              >
                {/* Span wrapper so the Tooltip still shows over a disabled button. */}
                <span className="inline-flex">
                  <Button
                    size="sm"
                    variant="bordered"
                    startContent={<FiShare2 />}
                    onPress={onOpenShares}
                    isDisabled={sharingDisabled}
                  >
                    Share
                  </Button>
                </span>
              </Tooltip>
            )}

            {/* Session checks (M-3) — validation is an action, not a tab. */}
            <Button
              size="sm"
              variant="bordered"
              startContent={<FiCheckSquare />}
              onPress={onOpenChecks}
            >
              Checks
            </Button>

            {/* Export menu (ADR-018 / EXPT-01/02 / D-22). */}
            <ExportMenu
              getPanelEl={() => panelRef.current}
              sessionId={sessionId}
              sessionName={scenarioName ?? sessionId}
              events={panelEvents}
              panel={viewMode === 'graph' ? 'graph' : 'tree'}
              onRecord={onRecord}
            />

            {/* View Mode Toggle */}
            <Button
              size="sm"
              variant={viewMode === 'graph' ? 'flat' : 'light'}
              color={viewMode === 'graph' ? 'primary' : 'default'}
              startContent={<FiGitBranch />}
              onPress={() => onViewModeChange('graph')}
            >
              Graph View
            </Button>
            <Button
              size="sm"
              variant={viewMode === 'list' ? 'flat' : 'light'}
              color={viewMode === 'list' ? 'primary' : 'default'}
              startContent={<FiList />}
              onPress={() => onViewModeChange('list')}
            >
              List View
            </Button>
          </div>
        </div>
      </CardBody>
    </Card>
  )
}

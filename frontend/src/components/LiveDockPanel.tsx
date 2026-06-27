import type { ReactNode } from 'react'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { SessionMetrics } from './SessionMetrics'
import { LeakList } from './LeakList'
import { LivePill } from './LivePill'

interface LiveDockPanelProps {
  sessionId: string
  /** Drives the LivePill + the metrics poll cadence (mirrors SessionMetrics). */
  streamEnabled: boolean
  /**
   * Read-only shared view (T-08-08): the shared shell carries no Bearer, so the
   * protected /metrics fetch/poll must be disabled. Forwarded as `!readOnly` to
   * the dock's own leak-data hook call.
   */
  readOnly: boolean
  /**
   * The live "what's running now" list (CoroutineTree/Graph + Show-completed
   * controls), derived and passed in by SessionDetails so the dock stays a
   * presentational shell (Pitfall 2 — keep SessionDetails legible).
   */
  liveList: ReactNode
  /**
   * The Surface-002 source panel for the selected coroutine. Wired by Plan 02;
   * a muted placeholder renders in its place while absent.
   */
  sourcePanel?: ReactNode
}

/**
 * Surface 001 — the IDE-docked live view (sketch winner C + B's metric tiles).
 *
 * Reflows the shipped Phase-8 live panel into the locked IDE-dock: a header
 * strip of metric tiles (the reused, already-themed `SessionMetrics`, with its
 * internal leak Card suppressed) + the LIVE/DEMO pill, over a two-column grid —
 * the left column hosting the live list and a single inline amber `LeakList`,
 * the right column reserving the ~320px source/metrics slot.
 *
 * Mounted ONLY in the live branch of SessionDetails (`!replayActive && !readOnly`)
 * — replay/shared keep the existing tabbed layout (PD-01).
 *
 * Leak placement (PD-02, LOCKED): the header strip is tiles-only
 * (`showLeaks={false}`); leaks render exactly ONCE, inline in the left column,
 * via the standalone `LeakList` fed by the SAME `useSessionMetrics` data (React
 * Query dedupes the `['session-metrics', sessionId]` key, so this shares one
 * network fetch with the strip's internal call). Amber/warning only — never
 * danger (PD-04).
 *
 * Literal Tailwind classes only (IN-12) — no `cn()`, no template-string class
 * composition.
 */
export function LiveDockPanel({
  sessionId,
  streamEnabled,
  readOnly,
  liveList,
  sourcePanel,
}: LiveDockPanelProps) {
  // Leak-data source (PD-02). Shares the React Query key with the strip's
  // SessionMetrics, so no extra network fetch is incurred.
  const { data: metrics } = useSessionMetrics(sessionId, streamEnabled, !readOnly)

  return (
    <div className="mt-8 rounded-xl bg-content1 border-t-2 border-primary min-h-[200px]">
      {/* Header strip: LIVE/DEMO pill + tiles-only metric strip (PD-04a). */}
      <div className="flex items-center justify-between gap-4 p-6">
        <LivePill streamEnabled={streamEnabled} />
        <SessionMetrics
          sessionId={sessionId}
          isLive={streamEnabled}
          enabled={!readOnly}
          showLeaks={false}
        />
      </div>

      {/* Two-column dock body: 1fr live list + 320px source/metrics slot. */}
      <div className="grid grid-cols-[1fr_320px] gap-8 p-6">
        {/* Left column — the live "what's running now" list + the single
            inline amber LeakList (mounted exactly once in the dock). */}
        <div className="space-y-4">
          {liveList}
          {metrics && metrics.leaks.length > 0 && (
            <LeakList leaks={metrics.leaks} leakThresholdMs={metrics.leakThresholdMs} />
          )}
        </div>

        {/* Right column — the Surface-002 source panel slot (Plan 02). */}
        <div className="space-y-4">
          {sourcePanel ?? (
            <div className="text-sm text-default-400">
              Select a coroutine to view its source
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

import type { ReactNode } from 'react'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { SessionMetrics } from '../SessionMetrics'
import { LeakList } from '../LeakList'
import { LivePill } from '../LivePill'

interface WorkspaceBodyProps {
  sessionId: string
  /** Drives the LivePill + the metrics poll cadence (mirrors SessionMetrics). */
  streamEnabled: boolean
  /**
   * Read-only shared view (T-08-08): the shared shell carries no Bearer, so the
   * protected /metrics fetch/poll must be disabled. Forwarded as `!readOnly` to
   * the body's own leak-data hook call.
   */
  readOnly: boolean
  /**
   * Whether the header strip of metric tiles renders (M-4). False in replay,
   * where live numbers over a frozen view would simply be wrong, and false in
   * the read-only shared view, which has no Bearer for /metrics. The leak list
   * follows the strip — it is the same data, and it is equally live.
   */
  showMetrics: boolean
  /**
   * The "what's running now" canvas (CoroutineTree/Graph + Show-completed
   * controls), derived and passed in by SessionWorkspace so the body stays a
   * presentational shell (Pitfall 2 — keep SessionWorkspace legible).
   */
  liveList: ReactNode
  /**
   * The right-hand inspector for the selected coroutine (D-7). A muted
   * placeholder renders in its place while absent.
   */
  inspector?: ReactNode
}

/**
 * The workspace body — Surface 001's IDE-dock, now the one layout for all
 * three modes (M-4).
 *
 * A header strip of metric tiles (the reused, already-themed `SessionMetrics`,
 * with its internal leak Card suppressed) + the LIVE/DEMO pill, over a
 * two-column grid — the left column hosting the canvas and a single inline
 * amber `LeakList`, the right column the ~320px inspector.
 *
 * Replay and the read-only shared view mount the same body with `showMetrics`
 * off: keeping a second, tabbed layout for them is the exact debt the workspace
 * redesign exists to remove, but neither may show live metrics.
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
export function WorkspaceBody({
  sessionId,
  streamEnabled,
  readOnly,
  showMetrics,
  liveList,
  inspector,
}: WorkspaceBodyProps) {
  // Leak-data source (PD-02). Shares the React Query key with the strip's
  // SessionMetrics, so no extra network fetch is incurred.
  const { data: metrics } = useSessionMetrics(sessionId, streamEnabled, !readOnly)

  return (
    <div className="mt-8 rounded-xl bg-content1 border-t-2 border-primary min-h-[200px]">
      {/* Header strip: LIVE/DEMO pill + tiles-only metric strip (PD-04a). */}
      {showMetrics && (
        <div className="flex items-center justify-between gap-4 p-6">
          <LivePill streamEnabled={streamEnabled} />
          <SessionMetrics
            sessionId={sessionId}
            isLive={streamEnabled}
            enabled={!readOnly}
            showLeaks={false}
          />
        </div>
      )}

      {/* Two-column dock body: 1fr live list + 320px source/metrics slot. */}
      <div className="grid grid-cols-[1fr_320px] gap-8 p-6">
        {/* Left column — the live "what's running now" list + the single
            inline amber LeakList (mounted exactly once in the dock). */}
        <div className="space-y-4">
          {liveList}
          {showMetrics && metrics && metrics.leaks.length > 0 && (
            <LeakList leaks={metrics.leaks} leakThresholdMs={metrics.leakThresholdMs} />
          )}
        </div>

        {/* Right column — the inspector (D-7). */}
        <div className="space-y-4">
          {inspector ?? (
            <div className="text-sm text-default-400">
              Select a coroutine to view its source
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

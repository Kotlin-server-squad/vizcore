import type { ReactNode } from 'react'
import { useSessionMetrics } from '@/hooks/use-session-metrics'
import { SessionMetrics } from '../SessionMetrics'
import { LeakList } from '../LeakList'

interface WorkspaceBodyProps {
  sessionId: string
  /**
   * Read-only shared view (T-08-08): the shared shell carries no Bearer, so the
   * protected /metrics fetch/poll must be disabled. Forwarded as `!readOnly` to
   * the body's own leak-data hook call.
   */
  readOnly: boolean
  /**
   * Whether the strip of metric tiles renders (M-4). False in replay,
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
 * A two-column grid — the canvas and a single inline amber `LeakList` on the
 * left, the ~320px inspector on the right — over a strip of metric tiles (the
 * reused, already-themed `SessionMetrics`, with its internal leak Card
 * suppressed). The tree comes first (spec Part 1); the live indicator is the
 * header's stream chip, so the body has none of its own.
 *
 * Replay and the read-only shared view mount the same body with `showMetrics`
 * off: keeping a second, tabbed layout for them is the exact debt the workspace
 * redesign exists to remove, but neither may show live metrics.
 *
 * Leak placement (PD-02, LOCKED): the metric strip is tiles-only
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
  readOnly,
  showMetrics,
  liveList,
  inspector,
}: WorkspaceBodyProps) {
  // Leak-data source (PD-02). Shares the React Query key with the strip's
  // SessionMetrics, so no extra network fetch is incurred. A pure observer —
  // the workspace owns the refresh cadence (#124).
  const { data: metrics } = useSessionMetrics(sessionId, false, !readOnly)

  return (
    <div className="mt-8 rounded-xl bg-content1 border-t-2 border-primary min-h-[200px]">
      {/* Two-column body: canvas + 320px inspector.
          `minmax(0, 1fr)` rather than `1fr`, and `min-w-0` on the column: a
          grid track sized `1fr` still expands past its container when its
          content has a large min-content width, and the coroutine graph does.
          Without this the canvas overruns the grid and covers the inspector. */}
      <div className="grid grid-cols-[minmax(0,1fr)_320px] gap-8 p-6">
        {/* Left column — the canvas + the single inline amber LeakList
            (mounted exactly once). */}
        <div className="min-w-0 space-y-4">
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

      {/* Metric tiles, below the tree (spec Part 1: the tree comes first). The
          live indicator is the header's stream chip; the body no longer has one. */}
      {showMetrics && (
        <div className="px-6 pb-6">
          <SessionMetrics sessionId={sessionId} enabled={!readOnly} showLeaks={false} />
        </div>
      )}
    </div>
  )
}

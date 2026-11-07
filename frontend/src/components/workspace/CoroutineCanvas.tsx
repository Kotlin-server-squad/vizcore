import { useEffect, type ReactNode, type RefObject } from 'react'
import { Button } from '@heroui/react'
import { FiMaximize2, FiMinimize2 } from 'react-icons/fi'
import { CoroutineTree } from '../CoroutineTree'
import { CoroutineTreeGraph } from '../CoroutineTreeGraph'
import { EmptyState } from '../EmptyState'
import type { StateFilter } from '@/lib/state-counts'
import type { CoroutineNode } from '@/types/api'
import type { ActivityLine } from '@/lib/activity'

interface CoroutineCanvasProps {
  /** Already filtered and capped by the caller — the canvas renders what it is given. */
  coroutines: CoroutineNode[]
  viewMode: 'graph' | 'list'
  onViewModeChange: (next: 'graph' | 'list') => void
  /** Terminal coroutines collapsed out of the default active-only view (D-08). */
  completedCount: number
  /** Nothing is live and the canvas shows the whole finished run (spec Part 1). */
  allFinished?: boolean
  showCompleted: boolean
  onToggleCompleted: () => void
  /** What the node cap leaves out, in words (`capNote`); null or absent when everything is drawn. */
  capNote?: string | null
  /** Notes on what the page holds (the live history, summary-only coroutines), under the canvas. */
  notes?: readonly CanvasNote[]
  /** The big-app controls (S-12): round picker, find, toggles. Under the toolbar. */
  roundBar?: ReactNode
  /** An attached app's rounds are still loading: say so rather than draw every coroutine first. */
  loadingRounds?: boolean
  /**
   * Why an empty canvas is empty when the session itself is not: a round view (`round`), or only vizcore's own
   * coroutines hidden by the toggle (`vizcore-hidden`). Absent: the session-wide empty states.
   */
  emptyReason?: 'round' | 'vizcore-hidden'
  /** Bring this coroutine into view (Find, S-12): the graph centres it, the list scrolls to it. Once per `key`. */
  reveal?: { id: string; key: number }
  /** The canvas shows one round: its heading says so. */
  inRound?: boolean
  /** Children per parent in the view before the cap (the graph's "2 of 262 children"). */
  childTotals?: ReadonlyMap<string, number>
  /** Unfinished children per parent over the whole snapshot (see `CoroutineTree`). */
  pendingChildCounts?: ReadonlyMap<string, number>
  /** Each coroutine's second line (S-6); shown in every mode, read from the same events as the tree. */
  activity?: ReadonlyMap<string, ActivityLine>
  /** Which chip the state bar has active — decides which empty state is honest. */
  stateFilter: StateFilter
  /** The state bar, already bound to the filter (rendered in the toolbar). */
  stateBar: ReactNode
  /** A vizcore demo run, not the user's app: its empty state says to run the scenario. */
  isDemo?: boolean
  selectedCoroutineId: string | null
  /** Undefined makes the canvas non-interactive (replay / read-only shared, PD-01). */
  onSelect?: (id: string) => void
  /** ExportMenu captures this region. */
  panelRef: RefObject<HTMLDivElement | null>
  /** The dock is hidden and the main view takes the page (S-1). */
  maximised: boolean
  onToggleMaximise: () => void
}

/**
 * The main view (spec S-1): the coroutine tree or graph in a box that takes
 * the page's free height, with the view switch, the state bar, the heading and
 * Maximise in its toolbar.
 *
 * Presentational — every derivation (the active/terminal split, the node cap,
 * the state filter) happens in the caller so this component cannot disagree
 * with the state bar about what is on screen.
 */
export function CoroutineCanvas({
  coroutines,
  viewMode,
  onViewModeChange,
  completedCount,
  allFinished = false,
  showCompleted,
  onToggleCompleted,
  capNote,
  notes = [],
  roundBar,
  loadingRounds = false,
  emptyReason,
  reveal,
  inRound = false,
  childTotals,
  pendingChildCounts,
  activity,
  stateFilter,
  stateBar,
  isDemo = false,
  selectedCoroutineId,
  onSelect,
  panelRef,
  maximised,
  onToggleMaximise,
}: CoroutineCanvasProps) {
  // The list scrolls inside its box: bring a revealed row into it.
  const revealKey = reveal?.key
  const revealId = reveal?.id
  useEffect(() => {
    if (revealKey === undefined || !revealId || viewMode !== 'list') return
    const row = panelRef.current?.querySelector(`[data-node-id="${CSS.escape(revealId)}"]`)
    row?.scrollIntoView?.({ block: 'center' })
  }, [revealKey, revealId, viewMode, panelRef])

  return (
    <section
      data-look-part="main"
      aria-label="Main view"
      className="flex min-h-[320px] min-w-0 flex-1 flex-col rounded-xl border border-default-200 bg-content1"
    >
      <div data-look-part="main-toolbar" className="flex flex-wrap items-center gap-2 border-b border-default-200 px-3 py-2">
        <div role="group" aria-label="View" className="flex gap-0.5 rounded-medium bg-default-100 p-0.5">
          <ViewButton label="Graph" pressed={viewMode === 'graph'} onPress={() => onViewModeChange('graph')} />
          <ViewButton label="List" pressed={viewMode === 'list'} onPress={() => onViewModeChange('list')} />
        </div>
        <h2 className="text-sm font-semibold">
          {inRound
            ? allFinished
              ? 'This round has finished'
              : <>What&apos;s running in this round</>
            : allFinished
              ? 'Everything has finished'
              : <>What&apos;s running now</>}
        </h2>
        {stateBar}
        {completedCount > 0 && !allFinished && (
          <Button size="sm" variant="flat" onPress={onToggleCompleted}>
            {showCompleted ? `Hide completed (${completedCount})` : `Show completed (${completedCount})`}
          </Button>
        )}
        <div className="flex-1" />
        <Button
          size="sm"
          variant="bordered"
          aria-pressed={maximised}
          startContent={maximised ? <FiMinimize2 aria-hidden /> : <FiMaximize2 aria-hidden />}
          onPress={onToggleMaximise}
        >
          {maximised ? 'Restore dock' : 'Maximise'}
        </Button>
      </div>

      {roundBar}

      {/* Gate on what the canvas will actually render, not on the
          active set — a state filter can select terminal coroutines,
          and gating on activeCoroutines hid them behind the
          "no app connected" empty state. */}
      <div data-look-part="main-body" className="flex min-h-0 flex-1 flex-col p-3">
        {loadingRounds ? (
          <div className="flex flex-1 items-center justify-center text-sm text-default-500">Loading rounds…</div>
        ) : coroutines.length === 0 ? (
          <div className="flex flex-1 items-center justify-center">
            {emptyReason === 'vizcore-hidden' ? (
              <EmptyState
                title="Nothing to show here"
                description="Only vizcore's own coroutines were captured in this view. Turn on vizcore connection to see them."
              />
            ) : emptyReason === 'round' && stateFilter !== 'all' ? (
              <EmptyState
                title={`No ${stateFilter} coroutines in this round`}
                description="Nothing in this round matches the selected state. Pick another state, another round, or clear the filter."
              />
            ) : stateFilter === 'all' ? (
              // Say what is actually true: a demo not run yet, or an app that has not
              // sent anything yet. A finished run is never empty — it shows its
              // coroutines.
              isDemo ? (
                <EmptyState
                  title="No coroutines yet"
                  description="Run the scenario to see its coroutines here."
                />
              ) : (
                <EmptyState
                  title="No live coroutines yet"
                  // H-4: the first empty result is where sampling is explained.
                  description="Start your instrumented app and call VizcoreClient.start(...). Running coroutines will appear here as they are captured: vizcore samples them on a poll, so one that starts and ends between two polls may not appear."
                />
              )
            ) : (
              <EmptyState
                title={`No ${stateFilter} coroutines`}
                description="Nothing in this session matches the selected state. Pick another state, or clear the filter to see everything."
              />
            )}
          </div>
        ) : (
          <>
            {/* The list scrolls inside the box; the graph fills it and pans. */}
            <div ref={panelRef} className={viewMode === 'graph' ? 'min-h-0 flex-1' : 'min-h-0 flex-1 overflow-auto'}>
              {/* Source-selection is LIVE-ONLY (PD-01): omit
                  onSelect/selectedNodeId in replay/shared so those
                  renders stay presentational with no added
                  interactivity (Pitfall 1 back-compat). */}
              {viewMode === 'graph' ? (
                <CoroutineTreeGraph
                  reveal={reveal}
                  childTotals={childTotals}
                  coroutines={coroutines}
                  onSelect={onSelect}
                  selectedNodeId={onSelect ? selectedCoroutineId : undefined}
                  activity={activity}
                />
              ) : (
                <CoroutineTree
                  coroutines={coroutines}
                  onSelect={onSelect}
                  selectedNodeId={onSelect ? selectedCoroutineId : undefined}
                  pendingChildCounts={pendingChildCounts}
                  activity={activity}
                />
              )}
            </div>
            {capNote && (
              <div data-look-part="cap-note" className="mt-2 text-xs text-default-500">
                {capNote}
              </div>
            )}
          </>
        )}
        {notes.map(note => (
          <div key={note.key} data-look-part={`${note.key}-note`} className="mt-1 text-xs text-default-500">
            {note.text}
          </div>
        ))}
      </div>
    </section>
  )
}

/** A line under the canvas about what the page holds; `key` names its look hook (`history-note`, `summary-only-note`). */
export interface CanvasNote {
  key: 'history' | 'summary-only'
  text: string
}

function ViewButton({ label, pressed, onPress }: { label: string; pressed: boolean; onPress: () => void }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onPress}
      className={
        pressed
          ? 'rounded-small bg-content1 px-3 py-1 text-xs font-semibold text-foreground shadow-small'
          : 'rounded-small px-3 py-1 text-xs text-default-500 hover:text-foreground'
      }
    >
      {label}
    </button>
  )
}

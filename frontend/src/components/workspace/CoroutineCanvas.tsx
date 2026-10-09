import type { RefObject } from 'react'
import { Card, CardBody, Button } from '@heroui/react'
import { CoroutineTree } from '../CoroutineTree'
import { CoroutineTreeGraph } from '../CoroutineTreeGraph'
import { EmptyState } from '../EmptyState'
import type { StateFilter } from '@/lib/state-counts'
import type { CoroutineNode } from '@/types/api'

interface CoroutineCanvasProps {
  /** Already filtered and capped by the caller — the canvas renders what it is given. */
  coroutines: CoroutineNode[]
  viewMode: 'graph' | 'list'
  /** Terminal coroutines collapsed out of the default active-only view (D-08). */
  completedCount: number
  showCompleted: boolean
  onToggleCompleted: () => void
  /** Active coroutines beyond the node cap. */
  moreCount: number
  /** Which chip the state bar has active — decides which empty state is honest. */
  stateFilter: StateFilter
  selectedCoroutineId: string | null
  /** Undefined makes the canvas non-interactive (replay / read-only shared, PD-01). */
  onSelect?: (id: string) => void
  /** ExportMenu captures this region. */
  panelRef: RefObject<HTMLDivElement | null>
}

/**
 * The workspace's default body (D-4): the active coroutine set, filtered by the
 * state bar, rendered as a tree or a graph.
 *
 * Presentational — every derivation (the active/terminal split, the node cap,
 * the state filter) happens in the caller so this component cannot disagree
 * with the state bar about what is on screen.
 */
export function CoroutineCanvas({
  coroutines,
  viewMode,
  completedCount,
  showCompleted,
  onToggleCompleted,
  moreCount,
  stateFilter,
  selectedCoroutineId,
  onSelect,
  panelRef,
}: CoroutineCanvasProps) {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">What&apos;s running now</h3>
        {completedCount > 0 && (
          <Button
            size="sm"
            variant="flat"
            onPress={onToggleCompleted}
          >
            {showCompleted
              ? `Hide completed (${completedCount})`
              : `Show completed (${completedCount})`}
          </Button>
        )}
      </div>

      {/* Gate on what the canvas will actually render, not on the
          active set — a state filter can select terminal coroutines,
          and gating on activeCoroutines hid them behind the
          "no app connected" empty state. */}
      {coroutines.length === 0 ? (
        stateFilter === 'all' ? (
          <EmptyState
            title="No live coroutines yet"
            description="Start your instrumented app and call VizcoreClient.start(...). Running coroutines will appear here in real time."
          />
        ) : (
          <EmptyState
            title={`No ${stateFilter} coroutines`}
            description="Nothing in this session matches the selected state. Pick another state, or clear the filter to see everything."
          />
        )
      ) : (
        <Card>
          <CardBody className="overflow-auto">
            <div ref={panelRef}>
              {/* Source-selection is LIVE-ONLY (PD-01): omit
                  onSelect/selectedNodeId in replay/shared so those
                  renders stay presentational with no added
                  interactivity (Pitfall 1 back-compat). */}
              {viewMode === 'graph' ? (
                <CoroutineTreeGraph
                  coroutines={coroutines}
                  onSelect={onSelect}
                  selectedNodeId={onSelect ? selectedCoroutineId : undefined}
                />
              ) : (
                <CoroutineTree
                  coroutines={coroutines}
                  onSelect={onSelect}
                  selectedNodeId={onSelect ? selectedCoroutineId : undefined}
                />
              )}
            </div>
            {moreCount > 0 && (
              <div className="text-xs text-default-500 mt-2">
                {moreCount} more coroutines
              </div>
            )}
          </CardBody>
        </Card>
      )}
    </div>
  )
}

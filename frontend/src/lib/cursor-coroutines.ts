import { CoroutineState, type CoroutineNode, type SuspensionPoint } from '@/types/api'

/**
 * A coroutine whose state at the replay cursor the server cannot rebuild (its events before the cursor were trimmed and
 * its summary changed after it): shown as unknown, never as a guess (H-3). Not one of the recorded states, so no state
 * chip counts it.
 */
export const UNKNOWN_STATE = 'UNKNOWN' as CoroutineState

/** The fields of a `…/hierarchy?atSeq=` node (backend `HierarchyNodeDto`) the cursor's tree needs. */
export interface MomentHierarchyNode {
  id: string
  parentId: string | null
  name: string
  scopeId: string | null
  jobId: string | null
  /** The projection's state string: ACTIVE reads RUNNING. */
  state: string
  /** The summary's state at the cursor (the health's classification), when the node has a summary. */
  summaryState?: string | null
  /** Built from the summary only: the events that created it are not retained. */
  summaryOnly?: boolean
  /** Replay only: its state at the cursor is not known. */
  partial?: boolean
  createdAtNanos: number
  completedAtNanos?: number | null
  /** What the durable summary keeps (Part 8, M-10). */
  creationPoint?: SuspensionPoint | null
  lastSuspensionPoint?: SuspensionPoint | null
  dispatcherName?: string | null
  currentThreadName?: string | null
  exceptionType?: string | null
  exceptionMessage?: string | null
  summary?: {
    lastThread?: { threadId?: number; threadName?: string | null } | null
    dispatcher?: string | null
  } | null
}

const RECORDED = new Set<string>(Object.values(CoroutineState))

function stateOf(node: MomentHierarchyNode): CoroutineState {
  if (node.partial) return UNKNOWN_STATE
  const raw = node.summaryState ?? (node.state === 'RUNNING' ? CoroutineState.ACTIVE : node.state)
  return RECORDED.has(raw) ? (raw as CoroutineState) : UNKNOWN_STATE
}

/**
 * The cursor's coroutines when the replay does not start at the session's beginning (Part 8, M-9): the coroutines the
 * replayed events created, as the page projects them, plus, before them, every coroutine the server knows at the
 * cursor that those events never created, from its durable summary (`fromSummary`).
 */
export function mergeSummaryNodes(
  projected: readonly CoroutineNode[],
  atCursor: readonly MomentHierarchyNode[],
): CoroutineNode[] {
  const known = new Set(projected.map(c => c.id))
  const added = atCursor
    .filter(n => !known.has(n.id))
    .map(
      (n): CoroutineNode => ({
        id: n.id,
        jobId: n.jobId ?? '',
        parentId: n.parentId,
        scopeId: n.scopeId ?? '',
        label: n.name,
        state: stateOf(n),
        fromSummary: true,
      }),
    )
  return [...added, ...projected]
}

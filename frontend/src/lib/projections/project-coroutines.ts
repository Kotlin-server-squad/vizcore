/**
 * Pure client-side reducer: VizEvent[] -> CoroutineNode[].
 *
 * Reimplements the authoritative backend state machine
 * (coroutine-viz-core/session/EventApplier.kt) in TypeScript so replay-driven
 * panels can derive the coroutine snapshot from the `visibleEvents` slice
 * instead of the server `session.coroutines` snapshot (ADR-017 / OQ-1).
 *
 * The output deep-equals the server's GET /api/sessions/{id} `coroutines` list
 * for the same event prefix: same ids, parentId, scopeId, label, and terminal /
 * in-progress state. Nodes are emitted in CREATION order (first CoroutineCreated
 * seen), matching the backend RuntimeSnapshot.coroutines insertion-ordered map.
 *
 * Framework-free (no React imports) and side-effect-free — does not mutate its
 * input — so it is safe to call inside a useMemo.
 *
 * State machine (mirrors EventApplier.kt):
 *   CoroutineCreated       -> new node, state CREATED (parentId/scope/label set)
 *   CoroutineStarted       -> ACTIVE
 *   CoroutineSuspended     -> SUSPENDED
 *   CoroutineResumed       -> ACTIVE
 *   CoroutineBodyCompleted -> WAITING_FOR_CHILDREN
 *   CoroutineCompleted     -> COMPLETED  (terminal)
 *   CoroutineCancelled     -> CANCELLED  (terminal)
 *   CoroutineFailed        -> FAILED     (terminal)
 * Terminal state is classified by EVENT KIND, never by message text (FIX-03).
 * Lifecycle events for a coroutine that was never created are ignored (the
 * backend logs a warning and applies no state change).
 */

import { CoroutineState, type CoroutineEvent, type CoroutineNode, type VizEvent } from '@/types/api'

/** Coroutine lifecycle event kinds this reducer transitions on. */
const LIFECYCLE_KINDS: ReadonlySet<string> = new Set([
  'coroutine.created',
  'coroutine.started',
  'coroutine.suspended',
  'coroutine.resumed',
  'coroutine.body-completed',
  'coroutine.completed',
  'coroutine.cancelled',
  'coroutine.failed',
])

function isCoroutineEvent(event: VizEvent): event is CoroutineEvent {
  return LIFECYCLE_KINDS.has(event.kind) && 'coroutineId' in event
}

/**
 * Fold coroutine lifecycle events into the current CoroutineNode[] snapshot.
 *
 * @param events - VizEvents (typically the replay `visibleEvents` slice), in seq order
 * @returns CoroutineNode[] in creation order, deep-equal to the server snapshot
 */
export function projectCoroutines(events: VizEvent[]): CoroutineNode[] {
  // Insertion-ordered map: preserves first-seen (creation) order on iteration,
  // matching the backend RuntimeSnapshot.coroutines (LinkedHashMap) order.
  const nodes = new Map<string, CoroutineNode>()

  for (const event of events) {
    if (!isCoroutineEvent(event)) continue

    if (event.kind === 'coroutine.created') {
      // A duplicate CoroutineCreated keeps the original node (the backend map
      // would overwrite, but re-creation is not expected in a real stream;
      // first-write-wins preserves creation order deterministically).
      if (!nodes.has(event.coroutineId)) {
        nodes.set(event.coroutineId, {
          id: event.coroutineId,
          jobId: event.jobId,
          parentId: event.parentCoroutineId,
          scopeId: event.scopeId,
          label: event.label,
          state: CoroutineState.CREATED,
        })
      }
      continue
    }

    // All other lifecycle events require an existing node (EventApplier null-guard).
    const node = nodes.get(event.coroutineId)
    if (!node) continue

    switch (event.kind) {
      case 'coroutine.started':
      case 'coroutine.resumed':
        node.state = CoroutineState.ACTIVE
        break
      case 'coroutine.suspended':
        node.state = CoroutineState.SUSPENDED
        break
      case 'coroutine.body-completed':
        node.state = CoroutineState.WAITING_FOR_CHILDREN
        break
      case 'coroutine.completed':
        node.state = CoroutineState.COMPLETED
        break
      case 'coroutine.cancelled':
        node.state = CoroutineState.CANCELLED
        break
      case 'coroutine.failed':
        node.state = CoroutineState.FAILED
        break
    }
  }

  return Array.from(nodes.values())
}

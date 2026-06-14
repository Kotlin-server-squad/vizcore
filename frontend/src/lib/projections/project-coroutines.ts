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
 * A re-emitted CoroutineCreated for an existing id is LAST-write-wins (overwrites
 * the node fields and resets state to CREATED) at the original insertion
 * position — exactly the backend LinkedHashMap re-assignment semantics.
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
      // Mirror the backend EventApplier.handleCreated exactly (WR-01): the
      // server assigns `snapshot.coroutines[id] = CoroutineNode(..., CREATED)`
      // into a LinkedHashMap, i.e. LAST-write-wins on every field (parentId /
      // scopeId / label) AND a reset to state CREATED, while re-assigning an
      // existing key preserves its ORIGINAL insertion position. A JS Map's
      // `set` on an existing key likewise preserves position, so always writing
      // a fresh node reproduces the server snapshot for re-emitted CoroutineCreated
      // and keeps the deep-equal-against-server invariant (T-02-05) intact.
      nodes.set(event.coroutineId, {
        id: event.coroutineId,
        jobId: event.jobId,
        parentId: event.parentCoroutineId,
        scopeId: event.scopeId,
        label: event.label,
        state: CoroutineState.CREATED,
      })
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

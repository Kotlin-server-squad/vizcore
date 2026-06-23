/**
 * Unit tests for projectCoroutines — the pure reducer that folds a VizEvent[]
 * stream into the CoroutineNode[] shape the server's GET /api/sessions/{id}
 * snapshot returns (and that buildCoroutineTree consumes).
 *
 * The backend EventApplier (coroutine-viz-core/session/EventApplier.kt) is the
 * authoritative state machine; this reducer reimplements it in TS. The expected
 * CoroutineNode[] in each test is the SERVER SNAPSHOT ORACLE — the exact list
 * `RuntimeSnapshot.coroutines.values()` (insertion / creation order) would yield
 * for the same event prefix. Assertions compare full objects (toEqual), never
 * presence-only checks (a presence-only assertion previously shipped a defect —
 * see 01-VERIFICATION.md history).
 */

import { describe, it, expect } from 'vitest'
import { projectCoroutines } from './project-coroutines'
import { CoroutineState, type CoroutineNode, type VizEvent } from '@/types/api'

/**
 * Build a coroutine lifecycle event. Field names mirror the wire CoroutineEvent
 * (coroutineId / jobId / parentCoroutineId / scopeId / label), so the same
 * fixture feeds both the reducer and any wire-shape consumer.
 */
function coroEvent(
  kind: VizEvent['kind'],
  overrides: {
    coroutineId: string
    seq: number
    tsNanos: number
    jobId?: string
    parentCoroutineId?: string | null
    scopeId?: string
    label?: string | null
  },
): VizEvent {
  return {
    sessionId: 'session-1',
    seq: overrides.seq,
    tsNanos: overrides.tsNanos,
    kind,
    coroutineId: overrides.coroutineId,
    jobId: overrides.jobId ?? `job-${overrides.coroutineId}`,
    parentCoroutineId: overrides.parentCoroutineId ?? null,
    scopeId: overrides.scopeId ?? 'scope-1',
    label: overrides.label ?? overrides.coroutineId,
  } as VizEvent
}

function node(overrides: Partial<CoroutineNode> & Pick<CoroutineNode, 'id' | 'state'>): CoroutineNode {
  return {
    id: overrides.id,
    jobId: overrides.jobId ?? `job-${overrides.id}`,
    parentId: overrides.parentId ?? null,
    scopeId: overrides.scopeId ?? 'scope-1',
    label: overrides.label ?? overrides.id,
    state: overrides.state,
  }
}

/**
 * Canonical captured-session fixture: a parent that launches two children.
 * - c-root: created, started, body-completed, completed
 * - c-child-a: created, started, suspended, resumed, completed (clean child)
 * - c-child-b: created, started, failed
 *
 * Events are ordered by seq (ascending), the order the server stores them.
 */
function sessionEvents(): VizEvent[] {
  return [
    coroEvent('coroutine.created', { coroutineId: 'c-root', seq: 0, tsNanos: 1_000, label: 'root' }),
    coroEvent('coroutine.started', { coroutineId: 'c-root', seq: 1, tsNanos: 1_100, label: 'root' }),
    coroEvent('coroutine.created', { coroutineId: 'c-child-a', seq: 2, tsNanos: 1_200, parentCoroutineId: 'c-root', label: 'child-a' }),
    coroEvent('coroutine.started', { coroutineId: 'c-child-a', seq: 3, tsNanos: 1_300, parentCoroutineId: 'c-root', label: 'child-a' }),
    coroEvent('coroutine.created', { coroutineId: 'c-child-b', seq: 4, tsNanos: 1_400, parentCoroutineId: 'c-root', label: 'child-b' }),
    coroEvent('coroutine.started', { coroutineId: 'c-child-b', seq: 5, tsNanos: 1_500, parentCoroutineId: 'c-root', label: 'child-b' }),
    coroEvent('coroutine.suspended', { coroutineId: 'c-child-a', seq: 6, tsNanos: 1_600, parentCoroutineId: 'c-root', label: 'child-a' }),
    coroEvent('coroutine.failed', { coroutineId: 'c-child-b', seq: 7, tsNanos: 1_700, parentCoroutineId: 'c-root', label: 'child-b' }),
    coroEvent('coroutine.resumed', { coroutineId: 'c-child-a', seq: 8, tsNanos: 1_800, parentCoroutineId: 'c-root', label: 'child-a' }),
    coroEvent('coroutine.completed', { coroutineId: 'c-child-a', seq: 9, tsNanos: 1_900, parentCoroutineId: 'c-root', label: 'child-a' }),
    coroEvent('coroutine.body-completed', { coroutineId: 'c-root', seq: 10, tsNanos: 2_000, label: 'root' }),
    coroEvent('coroutine.completed', { coroutineId: 'c-root', seq: 11, tsNanos: 2_100, label: 'root' }),
  ]
}

describe('projectCoroutines', () => {
  it('is a pure function (does not mutate its input)', () => {
    const events = sessionEvents()
    const snapshot = JSON.stringify(events)
    projectCoroutines(events)
    expect(JSON.stringify(events)).toBe(snapshot)
  })

  it('deep-equals the server snapshot oracle for a full captured session', () => {
    // Oracle: RuntimeSnapshot.coroutines.values() after applying ALL events.
    // Insertion (creation) order: c-root, c-child-a, c-child-b.
    const expected: CoroutineNode[] = [
      node({ id: 'c-root', label: 'root', state: CoroutineState.COMPLETED }),
      node({ id: 'c-child-a', parentId: 'c-root', label: 'child-a', state: CoroutineState.COMPLETED }),
      node({ id: 'c-child-b', parentId: 'c-root', label: 'child-b', state: CoroutineState.FAILED }),
    ]

    expect(projectCoroutines(sessionEvents())).toEqual(expected)
  })

  it('classifies the terminal trio by event kind: FAILED, CANCELLED, COMPLETED (FIX-03)', () => {
    const events: VizEvent[] = [
      coroEvent('coroutine.created', { coroutineId: 'c-done', seq: 0, tsNanos: 100 }),
      coroEvent('coroutine.started', { coroutineId: 'c-done', seq: 1, tsNanos: 200 }),
      coroEvent('coroutine.completed', { coroutineId: 'c-done', seq: 2, tsNanos: 300 }),
      coroEvent('coroutine.created', { coroutineId: 'c-victim', seq: 3, tsNanos: 400 }),
      coroEvent('coroutine.started', { coroutineId: 'c-victim', seq: 4, tsNanos: 500 }),
      coroEvent('coroutine.cancelled', { coroutineId: 'c-victim', seq: 5, tsNanos: 600 }),
      coroEvent('coroutine.created', { coroutineId: 'c-boom', seq: 6, tsNanos: 700 }),
      coroEvent('coroutine.started', { coroutineId: 'c-boom', seq: 7, tsNanos: 800 }),
      coroEvent('coroutine.failed', { coroutineId: 'c-boom', seq: 8, tsNanos: 900 }),
    ]

    expect(projectCoroutines(events)).toEqual([
      node({ id: 'c-done', state: CoroutineState.COMPLETED }),
      node({ id: 'c-victim', state: CoroutineState.CANCELLED }),
      node({ id: 'c-boom', state: CoroutineState.FAILED }),
    ])
  })

  it('yields in-progress state for a prefix of events (replay cursor mid-session)', () => {
    // Cursor at seq 7 (c-child-b just failed): c-child-a is SUSPENDED (seq 6),
    // c-root is still ACTIVE (started but not body-completed), c-child-b FAILED.
    const prefix = sessionEvents().slice(0, 8)

    expect(projectCoroutines(prefix)).toEqual([
      node({ id: 'c-root', label: 'root', state: CoroutineState.ACTIVE }),
      node({ id: 'c-child-a', parentId: 'c-root', label: 'child-a', state: CoroutineState.SUSPENDED }),
      node({ id: 'c-child-b', parentId: 'c-root', label: 'child-b', state: CoroutineState.FAILED }),
    ])
  })

  it('transitions CREATED -> ACTIVE on started and ACTIVE -> SUSPENDED on suspended', () => {
    const created = projectCoroutines([
      coroEvent('coroutine.created', { coroutineId: 'c-1', seq: 0, tsNanos: 10 }),
    ])
    expect(created[0]!.state).toBe(CoroutineState.CREATED)

    const started = projectCoroutines([
      coroEvent('coroutine.created', { coroutineId: 'c-1', seq: 0, tsNanos: 10 }),
      coroEvent('coroutine.started', { coroutineId: 'c-1', seq: 1, tsNanos: 20 }),
    ])
    expect(started[0]!.state).toBe(CoroutineState.ACTIVE)

    const suspended = projectCoroutines([
      coroEvent('coroutine.created', { coroutineId: 'c-1', seq: 0, tsNanos: 10 }),
      coroEvent('coroutine.started', { coroutineId: 'c-1', seq: 1, tsNanos: 20 }),
      coroEvent('coroutine.suspended', { coroutineId: 'c-1', seq: 2, tsNanos: 30 }),
    ])
    expect(suspended[0]!.state).toBe(CoroutineState.SUSPENDED)
  })

  it('transitions to WAITING_FOR_CHILDREN on body-completed before final completion', () => {
    const waiting = projectCoroutines([
      coroEvent('coroutine.created', { coroutineId: 'c-1', seq: 0, tsNanos: 10 }),
      coroEvent('coroutine.started', { coroutineId: 'c-1', seq: 1, tsNanos: 20 }),
      coroEvent('coroutine.body-completed', { coroutineId: 'c-1', seq: 2, tsNanos: 30 }),
    ])
    expect(waiting[0]!.state).toBe(CoroutineState.WAITING_FOR_CHILDREN)
  })

  it('ignores lifecycle events for an unknown coroutine (no CoroutineCreated seen)', () => {
    // A started/completed for a coroutine never created produces no node —
    // mirrors EventApplier's null-guard (logs a warning, no state created).
    const result = projectCoroutines([
      coroEvent('coroutine.started', { coroutineId: 'ghost', seq: 0, tsNanos: 10 }),
      coroEvent('coroutine.completed', { coroutineId: 'ghost', seq: 1, tsNanos: 20 }),
    ])
    expect(result).toEqual([])
  })

  it('returns an empty array for no events', () => {
    expect(projectCoroutines([])).toEqual([])
  })

  it('re-emitted CoroutineCreated is last-write-wins at the original position (WR-01)', () => {
    // Oracle: the backend EventApplier does `coroutines[id] = CoroutineNode(..,
    // CREATED)` into a LinkedHashMap. Re-assigning an EXISTING key overwrites
    // every field AND resets state to CREATED, while keeping the key's ORIGINAL
    // insertion position. So a stream that creates c-1, advances it, creates
    // c-2, then RE-creates c-1 with changed metadata must:
    //   - keep c-1 first (original insertion order preserved on re-set), and
    //   - reflect the NEW metadata + a reset to CREATED for c-1.
    const events: VizEvent[] = [
      coroEvent('coroutine.created', { coroutineId: 'c-1', seq: 0, tsNanos: 100, parentCoroutineId: null, scopeId: 'scope-A', label: 'first' }),
      coroEvent('coroutine.started', { coroutineId: 'c-1', seq: 1, tsNanos: 200, label: 'first' }),
      coroEvent('coroutine.created', { coroutineId: 'c-2', seq: 2, tsNanos: 300, label: 'second' }),
      // Re-emit c-1 with DIFFERENT parent/scope/label — backend overwrites + resets state.
      coroEvent('coroutine.created', { coroutineId: 'c-1', seq: 3, tsNanos: 400, parentCoroutineId: 'c-2', scopeId: 'scope-B', label: 'first-rebound' }),
    ]

    expect(projectCoroutines(events)).toEqual([
      // Position preserved (c-1 still first), fields overwritten, state reset to CREATED.
      node({ id: 'c-1', parentId: 'c-2', scopeId: 'scope-B', label: 'first-rebound', state: CoroutineState.CREATED }),
      node({ id: 'c-2', label: 'second', state: CoroutineState.CREATED }),
    ])
  })
})

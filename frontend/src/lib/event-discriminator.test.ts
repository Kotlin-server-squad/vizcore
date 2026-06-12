/**
 * Discriminator regression test: proves that raw backend payloads
 * with the `type` field (not `kind`) are correctly normalized
 * to carry a defined `kind` field matching the frontend VizEventKind.
 *
 * This file intentionally uses RAW backend-shaped payloads (objects with
 * `type`, no `kind`) — the whole point is to exercise the type->kind
 * mapping on the real wire shape, not pre-normalized fixtures.
 */
import { describe, it, expect } from 'vitest'
import { normalizeEvent, normalizeEvents, eventTypeToKind } from './utils'
import { JOB_EVENT_KINDS } from '@/types/api'

// All 17 backend event type values (from the backend kotlinx-serialization discriminator)
// These are the PascalCase type names the backend emits via JSON `type` field
const BACKEND_TYPE_VALUES = [
  'CoroutineCreated',
  'CoroutineStarted',
  'CoroutineSuspended',
  'CoroutineResumed',
  'CoroutineBodyCompleted',
  'CoroutineCompleted',
  'CoroutineCancelled',
  'CoroutineFailed',
  'ThreadAssigned',
  'DispatcherSelected',
  'DeferredValueAvailable',
  'DeferredAwaitStarted',
  'DeferredAwaitCompleted',
  'JobStateChanged',
  'JobCancellationRequested',
  'JobJoinRequested',
  'JobJoinCompleted',
] as const

type BackendType = (typeof BACKEND_TYPE_VALUES)[number]

/** Build a raw backend event payload — only `type`, no `kind` */
function rawBackendEvent(type: BackendType, seq = 1) {
  return {
    type,
    sessionId: 'session-test',
    seq,
    tsNanos: 1_000_000_000,
    coroutineId: 'coroutine-1',
    jobId: 'job-1',
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: null,
  }
}

describe('normalizeEvent — raw backend {type} payload', () => {
  it('maps JobStateChanged type to kind === "JobStateChanged"', () => {
    const raw = rawBackendEvent('JobStateChanged')
    const result = normalizeEvent(raw)

    expect(result.kind).toBe('JobStateChanged')
    expect('type' in result).toBe(false)
  })

  it('result kind for JobStateChanged is in JOB_EVENT_KINDS', () => {
    const raw = rawBackendEvent('JobStateChanged')
    const result = normalizeEvent(raw)

    expect(JOB_EVENT_KINDS.has(result.kind)).toBe(true)
  })

  it('preserves other event fields when normalizing', () => {
    const raw = rawBackendEvent('JobStateChanged', 42)
    const result = normalizeEvent(raw)

    expect(result.sessionId).toBe('session-test')
    expect(result.seq).toBe(42)
    expect(result.tsNanos).toBe(1_000_000_000)
  })

  it('returns event with kind preserved when already normalized (no double-mapping)', () => {
    const alreadyNormalized = {
      kind: 'JobStateChanged' as const,
      sessionId: 'session-test',
      seq: 1,
      tsNanos: 1_000_000_000,
      coroutineId: 'coroutine-1',
      jobId: 'job-1',
      parentCoroutineId: null,
      scopeId: 'scope-1',
      label: null,
    }
    const result = normalizeEvent(alreadyNormalized)

    expect(result.kind).toBe('JobStateChanged')
    expect(result).toBe(alreadyNormalized) // same object reference returned
  })

  it('yields a defined, non-empty kind for all 17 backend type values', () => {
    for (const type of BACKEND_TYPE_VALUES) {
      const raw = rawBackendEvent(type as BackendType)
      const result = normalizeEvent(raw)

      expect(result.kind, `kind should be defined for type "${type}"`).toBeTruthy()
      expect(typeof result.kind, `kind should be a string for type "${type}"`).toBe('string')
      expect(result.kind.length, `kind should be non-empty for type "${type}"`).toBeGreaterThan(0)
    }
  })

  it('does not leave a "type" field on the normalized event', () => {
    for (const type of BACKEND_TYPE_VALUES) {
      const raw = rawBackendEvent(type as BackendType)
      const result = normalizeEvent(raw)

      expect(
        'type' in result,
        `"type" field should be removed after normalization for type "${type}"`,
      ).toBe(false)
    }
  })
})

describe('normalizeEvents — array normalization end-to-end', () => {
  it('normalizes a mixed array of raw backend events', () => {
    const rawEvents = [
      rawBackendEvent('JobStateChanged', 1),
      rawBackendEvent('JobJoinRequested', 2),
      rawBackendEvent('JobCancellationRequested', 3),
      rawBackendEvent('CoroutineCreated', 4),
      rawBackendEvent('CoroutineFailed', 5),
    ]

    const normalized = normalizeEvents(rawEvents)

    expect(normalized).toHaveLength(5)
    for (const event of normalized) {
      expect(event.kind).toBeTruthy()
    }
  })

  it('JobStateChanged events in the array are recognized by JOB_EVENT_KINDS', () => {
    const rawEvents = [
      rawBackendEvent('CoroutineCreated', 1),
      rawBackendEvent('JobStateChanged', 2),
      rawBackendEvent('JobStateChanged', 3),
      rawBackendEvent('CoroutineFailed', 4),
    ]

    const normalized = normalizeEvents(rawEvents)

    // All events should have a defined kind
    expect(normalized.every(e => !!e.kind)).toBe(true)

    // Exactly the job events should pass JOB_EVENT_KINDS membership
    const jobEvents = normalized.filter(e => JOB_EVENT_KINDS.has(e.kind))
    expect(jobEvents).toHaveLength(2)

    // Filter for JobStateChanged specifically
    const jobStateEvents = normalized.filter(e => e.kind === 'JobStateChanged')
    expect(jobStateEvents).toHaveLength(2)
  })

  it('returns empty array unchanged', () => {
    expect(normalizeEvents([])).toEqual([])
  })

  it('simulates SessionDetails jobStates derivation: raw REST payload -> Map keyed by jobId', () => {
    // Mirrors the real wire shape from GET /api/sessions/{id}/events
    // (no kind field, only type)
    const rawRestPayload = [
      { type: 'CoroutineCreated', sessionId: 's1', seq: 1, tsNanos: 1000, coroutineId: 'c1', jobId: 'j1', parentCoroutineId: null, scopeId: 'scope', label: null },
      { type: 'JobStateChanged', sessionId: 's1', seq: 2, tsNanos: 2000, coroutineId: 'c1', jobId: 'j1', parentCoroutineId: null, scopeId: 'scope', label: null, isActive: true, isCompleted: false, isCancelled: false, childrenCount: 0 },
      { type: 'JobStateChanged', sessionId: 's1', seq: 3, tsNanos: 3000, coroutineId: 'c2', jobId: 'j2', parentCoroutineId: null, scopeId: 'scope', label: null, isActive: true, isCompleted: false, isCancelled: false, childrenCount: 1 },
      { type: 'CoroutineFailed', sessionId: 's1', seq: 4, tsNanos: 4000, coroutineId: 'c1', jobId: 'j1', parentCoroutineId: null, scopeId: 'scope', label: null },
    ]

    const normalized = normalizeEvents(rawRestPayload)

    // Every event has a defined kind
    expect(normalized.every(e => !!e.kind)).toBe(true)

    // Replicate SessionDetails jobStates useMemo logic
    const jobStates = new Map<string, (typeof normalized)[number]>()
    for (const event of normalized) {
      if (event.kind === 'JobStateChanged') {
        const jobEvent = event as { jobId: string } & (typeof normalized)[number]
        jobStates.set(jobEvent.jobId, event)
      }
    }

    // Two distinct jobs should appear
    expect(jobStates.size).toBe(2)
    expect(jobStates.has('j1')).toBe(true)
    expect(jobStates.has('j2')).toBe(true)
  })
})

describe('eventTypeToKind — direct mapping', () => {
  it('maps all 17 backend type values to a defined kind', () => {
    for (const type of BACKEND_TYPE_VALUES) {
      const kind = eventTypeToKind(type)
      expect(kind, `eventTypeToKind("${type}") should be defined`).toBeTruthy()
    }
  })

  it('maps JobStateChanged to "JobStateChanged"', () => {
    expect(eventTypeToKind('JobStateChanged')).toBe('JobStateChanged')
  })

  it('maps JobJoinRequested to "JobJoinRequested"', () => {
    expect(eventTypeToKind('JobJoinRequested')).toBe('JobJoinRequested')
  })

  it('maps JobCancellationRequested to "JobCancellationRequested"', () => {
    expect(eventTypeToKind('JobCancellationRequested')).toBe('JobCancellationRequested')
  })

  it('maps JobJoinCompleted to "JobJoinCompleted"', () => {
    expect(eventTypeToKind('JobJoinCompleted')).toBe('JobJoinCompleted')
  })

  it('maps CoroutineCreated to "coroutine.created" (kebab-case for lifecycle events)', () => {
    expect(eventTypeToKind('CoroutineCreated')).toBe('coroutine.created')
  })

  it('maps CoroutineFailed to "coroutine.failed"', () => {
    expect(eventTypeToKind('CoroutineFailed')).toBe('coroutine.failed')
  })
})

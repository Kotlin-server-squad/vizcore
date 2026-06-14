/**
 * Unit tests for projectThreadActivity — the pure reducer that folds
 * ThreadAssigned events into the Map<threadId, ThreadEvent[]> wire shape that
 * GET /sessions/{id}/threads returns and that buildThreadLanes consumes.
 *
 * The backend ProjectionService (coroutine-viz-core/session/ProjectionService.kt)
 * is the authoritative builder: each ThreadAssigned appends an ASSIGNED
 * ThreadEvent to threadActivity[threadId], keyed by threadId.toString(), in
 * arrival (seq) order. The expected Map in each test is the SERVER ORACLE — the
 * exact threads endpoint payload for the same event prefix. Its output feeds
 * straight into buildThreadLanes (round-trip asserted below).
 */

import { describe, it, expect } from 'vitest'
import { projectThreadActivity } from './project-thread-activity'
import { buildThreadLanes } from '../thread-lanes'
import type { ThreadActivity, VizEvent } from '@/types/api'

function threadAssigned(overrides: {
  coroutineId: string
  threadId: number
  threadName?: string
  dispatcherName?: string | null
  seq: number
  tsNanos: number
}): VizEvent {
  return {
    sessionId: 'session-1',
    seq: overrides.seq,
    tsNanos: overrides.tsNanos,
    kind: 'thread.assigned',
    coroutineId: overrides.coroutineId,
    jobId: `job-${overrides.coroutineId}`,
    parentCoroutineId: null,
    scopeId: 'scope-1',
    label: overrides.coroutineId,
    threadId: overrides.threadId,
    threadName: overrides.threadName ?? `worker-${overrides.threadId}`,
    dispatcherName: 'dispatcherName' in overrides ? overrides.dispatcherName : 'Default',
  } as VizEvent
}

describe('projectThreadActivity', () => {
  it('is a pure function (does not mutate its input)', () => {
    const events = [threadAssigned({ coroutineId: 'c-1', threadId: 57, seq: 0, tsNanos: 1000 })]
    const snapshot = JSON.stringify(events)
    projectThreadActivity(events)
    expect(JSON.stringify(events)).toBe(snapshot)
  })

  it('deep-equals the server threads-endpoint oracle for a captured session', () => {
    const events: VizEvent[] = [
      threadAssigned({ coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', dispatcherName: 'Default', seq: 0, tsNanos: 1000 }),
      threadAssigned({ coroutineId: 'c-2', threadId: 80, threadName: 'worker-2', dispatcherName: 'IO', seq: 1, tsNanos: 2000 }),
      threadAssigned({ coroutineId: 'c-3', threadId: 57, threadName: 'worker-1', dispatcherName: 'Default', seq: 2, tsNanos: 3000 }),
    ]

    // Oracle: threadActivity keyed by threadId.toString(), each list in seq order,
    // every entry an ASSIGNED ThreadEvent (the projection never emits RELEASED).
    const expected: ThreadActivity = {
      '57': [
        { coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' },
        { coroutineId: 'c-3', threadId: 57, threadName: 'worker-1', timestamp: 3000, eventType: 'ASSIGNED', dispatcherName: 'Default' },
      ],
      '80': [
        { coroutineId: 'c-2', threadId: 80, threadName: 'worker-2', timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'IO' },
      ],
    }

    expect(projectThreadActivity(events)).toEqual(expected)
  })

  it('produces a Map shape that buildThreadLanes consumes without throwing', () => {
    const events: VizEvent[] = [
      threadAssigned({ coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', dispatcherName: 'Default', seq: 0, tsNanos: 1000 }),
      threadAssigned({ coroutineId: 'c-2', threadId: 80, threadName: 'worker-2', dispatcherName: 'IO', seq: 1, tsNanos: 2000 }),
    ]

    const activity = projectThreadActivity(events)
    const lanes = buildThreadLanes(activity)

    expect(lanes.threads.map(t => t.threadId).sort((a, b) => a - b)).toEqual([57, 80])
    expect(lanes.dispatcherInfo.map(d => d.name).sort()).toEqual(['Default', 'IO'])
  })

  it('ignores non-ThreadAssigned events', () => {
    const events: VizEvent[] = [
      {
        sessionId: 'session-1',
        seq: 0,
        tsNanos: 100,
        kind: 'coroutine.created',
        coroutineId: 'c-1',
        jobId: 'job-c-1',
        parentCoroutineId: null,
        scopeId: 'scope-1',
        label: 'c-1',
      } as VizEvent,
      threadAssigned({ coroutineId: 'c-1', threadId: 57, seq: 1, tsNanos: 200 }),
    ]

    expect(projectThreadActivity(events)).toEqual({
      '57': [
        { coroutineId: 'c-1', threadId: 57, threadName: 'worker-57', timestamp: 200, eventType: 'ASSIGNED', dispatcherName: 'Default' },
      ],
    })
  })

  it('preserves a null dispatcherName from the event', () => {
    const events: VizEvent[] = [
      threadAssigned({ coroutineId: 'c-1', threadId: 7, dispatcherName: null, seq: 0, tsNanos: 100 }),
    ]

    expect(projectThreadActivity(events)).toEqual({
      '7': [
        { coroutineId: 'c-1', threadId: 7, threadName: 'worker-7', timestamp: 100, eventType: 'ASSIGNED', dispatcherName: null },
      ],
    })
  })

  it('returns an empty map for no events', () => {
    expect(projectThreadActivity([])).toEqual({})
  })
})

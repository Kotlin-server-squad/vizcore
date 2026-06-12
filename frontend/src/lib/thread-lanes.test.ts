/**
 * Unit tests for buildThreadLanes — the pure adapter that converts the
 * REAL wire shape of GET /sessions/{id}/threads (Map<threadId, ThreadEvent[]>)
 * into the derived ThreadActivityResponse view model.
 *
 * All assertions compare exact values (toEqual on full objects / toBe on
 * numbers). Name-presence assertions are deliberately avoided — a previous
 * defect shipped green behind one (see 01-VERIFICATION.md history).
 */

import { describe, it, expect } from 'vitest'
import { buildThreadLanes } from './thread-lanes'
import type { ThreadActivity, ThreadEvent } from '@/types/api'

function evt(overrides: Partial<ThreadEvent> & Pick<ThreadEvent, 'coroutineId' | 'threadId' | 'timestamp' | 'eventType'>): ThreadEvent {
  return {
    threadName: `worker-${overrides.threadId}`,
    dispatcherName: null,
    ...overrides,
  }
}

/**
 * Canonical wire fixture (mirrors the shape curl showed in UAT round 2):
 * thread 57 ("worker-1", dispatcher Default): ASSIGNED c-1 @1000, RELEASED c-1 @5000
 * thread 80 ("worker-2", dispatcher IO):      ASSIGNED c-2 @2000 (left open)
 */
function wireFixture(): ThreadActivity {
  return {
    '57': [
      evt({ coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
      evt({ coroutineId: 'c-1', threadId: 57, threadName: 'worker-1', timestamp: 5000, eventType: 'RELEASED', dispatcherName: 'Default' }),
    ],
    '80': [
      evt({ coroutineId: 'c-2', threadId: 80, threadName: 'worker-2', timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'IO' }),
    ],
  }
}

describe('buildThreadLanes', () => {
  it('pairs ASSIGNED/RELEASED into closed segments and leaves unmatched ASSIGNED open', () => {
    const result = buildThreadLanes(wireFixture())

    const lane57 = result.threads.find(t => t.threadId === 57)!
    expect(lane57.segments).toEqual([
      {
        coroutineId: 'c-1',
        coroutineName: null,
        startNanos: 1000,
        endNanos: 5000,
        state: 'ACTIVE',
      },
    ])

    const lane80 = result.threads.find(t => t.threadId === 80)!
    expect(lane80.segments).toEqual([
      {
        coroutineId: 'c-2',
        coroutineName: null,
        startNanos: 2000,
        endNanos: null,
        state: 'ACTIVE',
      },
    ])
  })

  it('computes utilization over the global session span, closing open segments at the max timestamp', () => {
    const result = buildThreadLanes(wireFixture())

    // span = 5000 - 1000 = 4000
    // thread 57: busy = 5000 - 1000 = 4000 -> utilization exactly 1
    // thread 80: busy = 5000 - 2000 = 3000 -> utilization exactly 0.75
    const lane57 = result.threads.find(t => t.threadId === 57)!
    const lane80 = result.threads.find(t => t.threadId === 80)!
    expect(lane57.utilization).toBe(1)
    expect(lane80.utilization).toBe(0.75)
  })

  it('groups threads into dispatcherInfo by resolved dispatcher name', () => {
    const result = buildThreadLanes(wireFixture())

    expect(result.dispatcherInfo).toEqual([
      { id: 'Default', name: 'Default', threadIds: [57], queueDepth: null },
      { id: 'IO', name: 'IO', threadIds: [80], queueDepth: null },
    ])
  })

  it('derives lane metadata (numeric threadId, threadName, dispatcherName) from the events', () => {
    const result = buildThreadLanes(wireFixture())

    const lane57 = result.threads.find(t => t.threadId === 57)!
    expect(lane57.threadId).toBe(57)
    expect(lane57.threadName).toBe('worker-1')
    expect(lane57.dispatcherName).toBe('Default')
    expect(lane57.dispatcherId).toBe('Default')
  })

  it('handles degenerate inputs: empty map, missing dispatcherName, zero span', () => {
    // Empty map -> empty derived model
    expect(buildThreadLanes({})).toEqual({ threads: [], dispatcherInfo: [] })

    // Events without dispatcherName group under 'Unknown'
    const noDispatcher: ThreadActivity = {
      '7': [
        evt({ coroutineId: 'c-9', threadId: 7, threadName: 'worker-7', timestamp: 100, eventType: 'ASSIGNED' }),
        evt({ coroutineId: 'c-9', threadId: 7, threadName: 'worker-7', timestamp: 200, eventType: 'RELEASED' }),
      ],
    }
    const unknownResult = buildThreadLanes(noDispatcher)
    expect(unknownResult.dispatcherInfo).toEqual([
      { id: 'Unknown', name: 'Unknown', threadIds: [7], queueDepth: null },
    ])
    expect(unknownResult.threads[0]!.dispatcherName).toBe(null)

    // Single-event map: span = 0 -> utilization 0, no NaN
    const singleEvent: ThreadActivity = {
      '3': [evt({ coroutineId: 'c-3', threadId: 3, timestamp: 4242, eventType: 'ASSIGNED', dispatcherName: 'Default' })],
    }
    const zeroSpan = buildThreadLanes(singleEvent)
    expect(zeroSpan.threads[0]!.utilization).toBe(0)
    expect(Number.isNaN(zeroSpan.threads[0]!.utilization)).toBe(false)
  })

  it('closes the previous segment on duplicate ASSIGNED for the same coroutine (IN-13)', () => {
    // ASSIGNED @1000, ASSIGNED @2000 (duplicate — e.g. the intermediate
    // RELEASED was dropped by the bounded EventStore), RELEASED @3000.
    const duplicateAssigned: ThreadActivity = {
      '21': [
        evt({ coroutineId: 'c-dup', threadId: 21, timestamp: 1000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
        evt({ coroutineId: 'c-dup', threadId: 21, timestamp: 2000, eventType: 'ASSIGNED', dispatcherName: 'Default' }),
        evt({ coroutineId: 'c-dup', threadId: 21, timestamp: 3000, eventType: 'RELEASED', dispatcherName: 'Default' }),
      ],
    }

    const result = buildThreadLanes(duplicateAssigned)
    const lane = result.threads[0]!

    // The first segment is closed at the duplicate's timestamp — NOT left
    // open (an orphan would count as busy until the global max timestamp
    // and report c-dup as permanently active).
    expect(lane.segments).toEqual([
      {
        coroutineId: 'c-dup',
        coroutineName: null,
        startNanos: 1000,
        endNanos: 2000,
        state: 'ACTIVE',
      },
      {
        coroutineId: 'c-dup',
        coroutineName: null,
        startNanos: 2000,
        endNanos: 3000,
        state: 'ACTIVE',
      },
    ])

    // No open segments remain -> nothing reported as still active
    expect(lane.segments.filter(s => s.endNanos == null)).toEqual([])

    // span = 2000, busy = (2000-1000) + (3000-2000) = 2000 -> utilization 1
    expect(lane.utilization).toBe(1)
  })

  it('ignores RELEASED events with no prior ASSIGNED for that coroutine without throwing', () => {
    const orphanRelease: ThreadActivity = {
      '12': [
        evt({ coroutineId: 'c-x', threadId: 12, timestamp: 500, eventType: 'RELEASED', dispatcherName: 'IO' }),
      ],
    }

    expect(() => buildThreadLanes(orphanRelease)).not.toThrow()
    const result = buildThreadLanes(orphanRelease)
    expect(result.threads[0]!.segments).toEqual([])
  })
})

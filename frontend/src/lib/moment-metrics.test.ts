import { describe, it, expect } from 'vitest'
import { metricsAt } from './moment-metrics'
import { UNKNOWN_STATE } from './cursor-coroutines'
import { CoroutineState, type CoroutineNode, type VizEvent } from '@/types/api'

const node = (id: string, state: CoroutineState, scopeId = 'Dispatchers.Default'): CoroutineNode =>
  ({ id, jobId: 'j', parentId: null, scopeId, label: id, state }) as CoroutineNode

let seq = 0
const at = (s: number) => s * 1e9
const created = (id: string, ts: number, scopeId = 'Dispatchers.Default') =>
  ({ seq: ++seq, tsNanos: ts, sessionId: 's', kind: 'coroutine.created', coroutineId: id, jobId: 'j', parentCoroutineId: null, scopeId, label: id }) as unknown as VizEvent
const event = (kind: string, id: string, ts: number) =>
  ({ seq: ++seq, tsNanos: ts, sessionId: 's', kind, coroutineId: id, jobId: 'j', parentCoroutineId: null, scopeId: 'x', label: id }) as unknown as VizEvent

describe('metricsAt', () => {
  it('folds the peak from the replayed events, as /metrics does', () => {
    const events = [
      created('a', at(0)),
      created('b', at(1), 'Dispatchers.IO'),
      created('c', at(2)),
      event('coroutine.completed', 'a', at(3)),
      event('coroutine.failed', 'b', at(4)),
    ]
    expect(metricsAt(events, []).peak).toBe(3)
  })

  it('reads active and dispatcher load from the coroutines the tree shows, unknown states counted apart', () => {
    const coroutines = [
      node('a', CoroutineState.ACTIVE),
      node('b', CoroutineState.SUSPENDED, 'Dispatchers.IO'),
      node('c', CoroutineState.COMPLETED),
      node('d', UNKNOWN_STATE),
      { ...node('e', CoroutineState.CREATED), scopeId: '' },
    ]
    const m = metricsAt([], coroutines)
    expect(m.active).toBe(3)
    expect(m.unknown).toBe(1)
    expect(m.dispatcherUtilization).toEqual({ 'Dispatchers.Default': 1, 'Dispatchers.IO': 1, unknown: 1 })
  })

  it('reads events per second over the 10 s before the moment', () => {
    const events = [created('a', at(0)), event('coroutine.started', 'a', at(15)), event('coroutine.suspended', 'a', at(20)), event('coroutine.resumed', 'a', at(24))]
    // 15 s, 20 s and 24 s fall in (14 s, 24 s]; 0 s does not.
    expect(metricsAt(events, []).throughputPerSec).toBeCloseTo(0.3)
  })

  it('ignores events of a coroutine it never saw created, and counts a re-created one once', () => {
    const events = [event('coroutine.completed', 'ghost', at(0)), created('a', at(1)), created('a', at(2))]
    expect(metricsAt(events, []).peak).toBe(1)
  })

  it('is all zeros with nothing replayed', () => {
    expect(metricsAt([], [])).toEqual({ active: 0, unknown: 0, peak: 0, throughputPerSec: 0, dispatcherUtilization: {} })
  })
})

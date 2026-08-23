import { describe, it, expect } from 'vitest'
import { CoroutineState, type CoroutineNode } from '@/types/api'
import { deriveStateCounts, matchesFilter } from './state-counts'

const node = (state: CoroutineState, id = Math.random().toString()): CoroutineNode =>
  ({ id, jobId: id, parentId: null, scopeId: 's', label: null, state }) as CoroutineNode

describe('deriveStateCounts', () => {
  it('returns all zeros for an empty session', () => {
    const c = deriveStateCounts([])
    expect(c).toEqual({
      running: 0,
      suspended: 0,
      completed: 0,
      cancelled: 0,
      failed: 0,
      total: 0,
    })
  })

  it('counts WAITING_FOR_CHILDREN as running — the parent is still live work', () => {
    const c = deriveStateCounts([
      node(CoroutineState.ACTIVE),
      node(CoroutineState.WAITING_FOR_CHILDREN),
    ])
    expect(c.running).toBe(2)
  })

  it('counts CREATED as running — it is in flight, not finished', () => {
    expect(deriveStateCounts([node(CoroutineState.CREATED)]).running).toBe(1)
  })

  it('gives CANCELLED its own bucket rather than folding it into completed or failed', () => {
    const c = deriveStateCounts([node(CoroutineState.CANCELLED)])
    expect(c.cancelled).toBe(1)
    expect(c.completed).toBe(0)
    expect(c.failed).toBe(0)
  })

  it('buckets each remaining state correctly', () => {
    const c = deriveStateCounts([
      node(CoroutineState.SUSPENDED),
      node(CoroutineState.COMPLETED),
      node(CoroutineState.FAILED),
    ])
    expect(c.suspended).toBe(1)
    expect(c.completed).toBe(1)
    expect(c.failed).toBe(1)
  })

  it('never silently drops a coroutine — the buckets sum to the total', () => {
    const all = Object.values(CoroutineState).map((s) => node(s))
    const c = deriveStateCounts(all)
    expect(c.total).toBe(all.length)
    expect(c.running + c.suspended + c.completed + c.cancelled + c.failed).toBe(all.length)
  })
})

describe('matchesFilter', () => {
  it('admits everything under the "all" filter', () => {
    for (const s of Object.values(CoroutineState)) {
      expect(matchesFilter(s, 'all')).toBe(true)
    }
  })

  it('agrees with deriveStateCounts about what running means', () => {
    expect(matchesFilter(CoroutineState.WAITING_FOR_CHILDREN, 'running')).toBe(true)
    expect(matchesFilter(CoroutineState.CREATED, 'running')).toBe(true)
    expect(matchesFilter(CoroutineState.SUSPENDED, 'running')).toBe(false)
  })

  it('keeps cancelled out of the failed filter', () => {
    expect(matchesFilter(CoroutineState.CANCELLED, 'failed')).toBe(false)
    expect(matchesFilter(CoroutineState.CANCELLED, 'cancelled')).toBe(true)
  })
})

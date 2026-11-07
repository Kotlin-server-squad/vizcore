import { describe, it, expect } from 'vitest'
import { mergeSummaryNodes, UNKNOWN_STATE, type MomentHierarchyNode } from './cursor-coroutines'
import { CoroutineState, type CoroutineNode } from '@/types/api'

const client = (id: string, state: CoroutineState, parentId: string | null = null): CoroutineNode => ({
  id,
  jobId: 'j',
  parentId,
  scopeId: 's',
  label: id,
  state,
})

const server = (id: string, over: Partial<MomentHierarchyNode> = {}): MomentHierarchyNode => ({
  id,
  parentId: null,
  name: id,
  scopeId: null,
  jobId: null,
  state: 'RUNNING',
  summaryState: 'ACTIVE',
  summaryOnly: true,
  partial: false,
  createdAtNanos: 0,
  ...over,
})

describe('mergeSummaryNodes', () => {
  it('adds the coroutines the replayed events never created, before the others', () => {
    const merged = mergeSummaryNodes(
      [client('child', CoroutineState.ACTIVE, 'root')],
      [server('root', { summaryState: 'SUSPENDED' }), server('child', { summaryOnly: false })],
    )
    expect(merged.map(c => [c.id, c.state, c.fromSummary ?? false])).toEqual([
      ['root', CoroutineState.SUSPENDED, true],
      ['child', CoroutineState.ACTIVE, false],
    ])
  })

  it('keeps the state the replayed events give a coroutine they created', () => {
    const merged = mergeSummaryNodes([client('a', CoroutineState.COMPLETED)], [server('a', { summaryState: 'ACTIVE' })])
    expect(merged).toEqual([client('a', CoroutineState.COMPLETED)])
  })

  it('shows a coroutine whose state at the cursor the server cannot rebuild as unknown, never a guess', () => {
    const [node] = mergeSummaryNodes([], [server('a', { partial: true, summaryState: 'ACTIVE' })])
    expect(node!.state).toBe(UNKNOWN_STATE)
  })

  it('reads the projection state when there is no summary state, RUNNING as Active', () => {
    const [node] = mergeSummaryNodes([], [server('a', { summaryState: null, state: 'RUNNING' })])
    expect(node!.state).toBe(CoroutineState.ACTIVE)
  })

  it('names a coroutine by its label, and keeps its parent link', () => {
    const [node] = mergeSummaryNodes([], [server('a', { name: 'worker-1', parentId: 'p', jobId: 'job', scopeId: 'sc' })])
    expect(node).toMatchObject({ id: 'a', label: 'worker-1', parentId: 'p', jobId: 'job', scopeId: 'sc' })
  })
})

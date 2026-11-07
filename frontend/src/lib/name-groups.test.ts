import { describe, it, expect } from 'vitest'
import { CoroutineState, type CoroutineNode } from '@/types/api'
import { groupIdOf, groupSiblings, nameStem } from './name-groups'

const node = (id: string, over: Partial<CoroutineNode> = {}): CoroutineNode => ({
  id,
  jobId: `job-${id}`,
  parentId: null,
  scopeId: 'scope',
  label: id,
  state: CoroutineState.COMPLETED,
  roundId: 'round:r',
  origin: 'APP',
  ...over,
})

const NONE = new Set<string>()
const ids = (cs: CoroutineNode[]) => cs.map(c => c.id)

describe('nameStem', () => {
  it('is the name without its trailing number', () => {
    expect(nameStem('order-17')).toBe('order-')
    expect(nameStem('a2')).toBe('a')
    expect(nameStem('coroutine#12')).toBe('coroutine#')
  })

  it('is null for a name without a trailing number, a bare number, or no name', () => {
    expect(nameStem('worker')).toBeNull()
    expect(nameStem('17')).toBeNull()
    expect(nameStem(null)).toBeNull()
  })
})

describe('groupSiblings (S-12: siblings only, ancestry never hidden)', () => {
  const tree = [
    node('r'),
    node('o17', { parentId: 'r', label: 'order-17' }),
    node('o18', { parentId: 'r', label: 'order-18', state: CoroutineState.FAILED }),
    node('o19', { parentId: 'r', label: 'order-19', state: CoroutineState.ACTIVE }),
    node('db', { parentId: 'o17', label: 'db-query' }),
    node('w', { parentId: 'r', label: 'worker' }),
  ]

  it('folds siblings whose names differ only in a trailing number into one group in their place', () => {
    const { nodes, groups } = groupSiblings(tree, NONE)
    const id = groupIdOf(tree[1]!)!
    expect(ids(nodes)).toEqual(['r', id, 'w'])
    const group = groups.get(id)!
    expect(group).toMatchObject({ label: 'order-*', parentId: 'r', memberIds: ['o17', 'o18', 'o19'], below: 1, expanded: false })
    expect(group.counts).toEqual({ COMPLETED: 1, FAILED: 1, ACTIVE: 1 })
    expect(nodes[1]).toMatchObject({ id, parentId: 'r', label: 'order-*' })
  })

  it('expanded, keeps the members and their subtrees under the group', () => {
    const id = groupIdOf(tree[1]!)!
    const { nodes, groups } = groupSiblings(tree, new Set([id]))
    expect(ids(nodes)).toEqual(['r', id, 'o17', 'o18', 'o19', 'db', 'w'])
    expect(nodes.find(c => c.id === 'o18')!.parentId).toBe(id)
    expect(nodes.find(c => c.id === 'db')!.parentId).toBe('o17')
    expect(groups.get(id)!.expanded).toBe(true)
  })

  it('hides a group nested below a collapsed group’s member, card and all', () => {
    const cs = [
      node('r'),
      node('o1', { parentId: 'r', label: 'order-1' }),
      node('o2', { parentId: 'r', label: 'order-2' }),
      node('f1', { parentId: 'o1', label: 'fetch-1' }),
      node('f2', { parentId: 'o1', label: 'fetch-2' }),
    ]
    const outer = groupIdOf(cs[1]!)!
    const inner = groupIdOf(cs[3]!)!
    expect(ids(groupSiblings(cs, new Set([inner])).nodes)).toEqual(['r', outer])
    expect(groupSiblings(cs, NONE).groups.get(outer)!.below).toBe(2)
    expect(ids(groupSiblings(cs, new Set([outer])).nodes)).toEqual(['r', outer, 'o1', 'o2', inner])
  })

  it('keeps two parents’ order-* children apart', () => {
    const cs = [
      node('a'),
      node('b'),
      node('a1', { parentId: 'a', label: 'order-1' }),
      node('a2', { parentId: 'a', label: 'order-2' }),
      node('b1', { parentId: 'b', label: 'order-1' }),
      node('b2', { parentId: 'b', label: 'order-2' }),
    ]
    const { groups } = groupSiblings(cs, NONE)
    expect([...groups.values()].map(g => [g.parentId, g.memberIds])).toEqual([
      ['a', ['a1', 'a2']],
      ['b', ['b1', 'b2']],
    ])
  })

  it('never groups siblings of different rounds (a container root’s children)', () => {
    const cs = [
      node('server'),
      node('q1', { parentId: 'server', label: 'request-1', roundId: 'round:q1' }),
      node('q2', { parentId: 'server', label: 'request-2', roundId: 'round:q2' }),
    ]
    expect(groupSiblings(cs, NONE).groups.size).toBe(0)
  })

  it('leaves a single numbered sibling as a card of its own, and unnamed ones alone', () => {
    const cs = [node('r'), node('x', { parentId: 'r', label: 'order-1' }), node('n1', { parentId: 'r', label: null }), node('n2', { parentId: 'r', label: null })]
    const { nodes, groups } = groupSiblings(cs, NONE)
    expect(groups.size).toBe(0)
    expect(ids(nodes)).toEqual(['r', 'x', 'n1', 'n2'])
  })

  it('groups root siblings of one round too', () => {
    const cs = [node('w1', { label: 'worker-1' }), node('w2', { label: 'worker-2' })]
    expect(groupSiblings(cs, NONE).groups.size).toBe(1)
  })

  it('gives a group the same id whatever is expanded, so expansion survives a refresh', () => {
    expect(groupIdOf(tree[1]!)).toBe(groupIdOf(tree[2]!))
    expect(groupIdOf(tree[5]!)).toBeNull()
  })
})

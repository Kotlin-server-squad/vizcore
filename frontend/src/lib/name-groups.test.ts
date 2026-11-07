import { describe, it, expect } from 'vitest'
import { CoroutineState, type CoroutineNode } from '@/types/api'
import { creationSiteText, groupIdOf, groupSiblings, nameStem } from './name-groups'

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

  it('a group stands while any member is shown: members leaving the shown set never dissolve it', () => {
    const cs = [node('r'), ...[1, 2, 3].map(i => node(`o${i}`, { parentId: 'r', label: `order-${i}`, state: i === 1 ? CoroutineState.ACTIVE : CoroutineState.COMPLETED }))]
    const id = groupIdOf(cs[1]!)!
    const { nodes, groups } = groupSiblings(cs, NONE, new Set(['r', 'o1']))
    expect(ids(nodes)).toEqual(['r', id])
    expect(groups.get(id)!.counts).toEqual({ ACTIVE: 1, COMPLETED: 2 })
    expect(ids(groupSiblings(cs, new Set([id]), new Set(['r', 'o1'])).nodes)).toEqual(['r', id, 'o1'])
    expect(ids(groupSiblings(cs, NONE, new Set(['r'])).nodes)).toEqual(['r'])
  })

  it('shows a collapsed group whose members are filtered out when something below them is shown', () => {
    const cs = [
      node('r'),
      node('o1', { parentId: 'r', label: 'order-1', state: CoroutineState.SUSPENDED }),
      node('o2', { parentId: 'r', label: 'order-2', state: CoroutineState.SUSPENDED }),
      node('kid', { parentId: 'o1', label: 'charge', state: CoroutineState.ACTIVE }),
    ]
    const id = groupIdOf(cs[1]!)!
    expect(ids(groupSiblings(cs, NONE, new Set(['r', 'kid'])).nodes)).toEqual(['r', id])
  })

  it('stays linear with many siblings none of which is shown', () => {
    const many = [node('r', { state: CoroutineState.ACTIVE }), ...Array.from({ length: 20_000 }, (_, i) => node(`s${i}`, { parentId: 'r', label: `fetch-${i}` }))]
    const started = performance.now()
    groupSiblings(many, NONE, new Set(['r']))
    expect(performance.now() - started).toBeLessThan(500)
  })

  it('stays linear with groups nested under groups', () => {
    // 4,000 levels, each a pair of siblings under the first sibling of the level above.
    const cs = [node('root')]
    let parent = 'root'
    for (let level = 0; level < 4_000; level++) {
      cs.push(node(`a${level}`, { parentId: parent, label: `a-${level}-1` }), node(`b${level}`, { parentId: parent, label: `a-${level}-2` }))
      parent = `a${level}`
    }
    const started = performance.now()
    const { nodes, groups } = groupSiblings(cs, NONE, new Set(cs.map(c => c.id)))
    expect(performance.now() - started).toBeLessThan(500)
    expect(nodes).toHaveLength(2)
    expect(groups.get(groupIdOf(cs[1]!)!)!.below).toBe(cs.length - 3)
  })

  it('still groups siblings whose ancestry loops (a cycle in the reported parents)', () => {
    const cs = [
      node('p', { parentId: 'q' }),
      node('q', { parentId: 'p' }),
      node('w1', { parentId: 'q', label: 'worker-1' }),
      node('w2', { parentId: 'q', label: 'worker-2' }),
      node('x', { parentId: 'w1', label: 'parse' }),
    ]
    const id = groupIdOf(cs[2]!)!
    const { nodes, groups } = groupSiblings(cs, NONE, new Set(cs.map(c => c.id)))
    expect(groups.get(id)).toMatchObject({ memberIds: ['w1', 'w2'], below: 1 })
    expect(ids(nodes)).toEqual(['p', 'q', id])
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

describe('creationSiteText (the shared creation site needs complete knowledge)', () => {
  const at = (fn: string, file: string, line: number) => ({ function: fn, fileName: file, lineNumber: line, reason: 'launch' })
  const members = ['a', 'b', 'c']

  it('says "All created at" only when every member is known and all are the same', () => {
    const sites = new Map([['a', at('fetch', 'App.kt', 42)], ['b', at('fetch', 'App.kt', 42)], ['c', at('fetch', 'App.kt', 42)]])
    expect(creationSiteText(members, sites)).toBe('All created at App.kt:42 (fetch)')
  })

  it('lists the known sites with their counts, and the rest as unavailable', () => {
    const sites = new Map([['a', at('fetch', 'App.kt', 42)], ['b', at('load', 'Db.kt', 7)], ['c', null]])
    expect(creationSiteText(members, sites)).toBe('At App.kt:42 (fetch) for 1, Db.kt:7 (load) for 1; unavailable for 1')
  })

  it('says unavailable when no member’s site is known', () => {
    expect(creationSiteText(members, new Map())).toBe('Creation site unavailable')
  })
})

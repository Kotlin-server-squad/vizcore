import { describe, it, expect } from 'vitest'
import { buildCoroutineTree } from './utils'

const node = (id: string, parentId: string | null = null) => ({ id, parentId })

describe('buildCoroutineTree', () => {
  // The list is in start order (the server's snapshot, the replay's projection): every view draws siblings as given.
  it('keeps the list’s order for roots and for each parent’s children', () => {
    const tree = buildCoroutineTree([
      node('root-2'),
      node('z-first', 'root-2'),
      node('root-1'),
      node('a-second', 'root-2'),
      node('m-third', 'root-2'),
      node('only', 'root-1'),
    ])
    expect(tree.map(n => n.id)).toEqual(['root-2', 'root-1'])
    expect(tree[0]!.children.map(n => n.id)).toEqual(['z-first', 'a-second', 'm-third'])
    expect(tree[1]!.children.map(n => n.id)).toEqual(['only'])
  })

  it('draws a coroutine whose parent is not in the list as a root, in its place', () => {
    const tree = buildCoroutineTree([node('a'), node('orphan', 'gone'), node('b')])
    expect(tree.map(n => n.id)).toEqual(['a', 'orphan', 'b'])
  })
})

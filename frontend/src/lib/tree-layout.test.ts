import { describe, it, expect } from 'vitest'
import { chooseRowWidth, layoutTree, type LayoutInput, type TreeLayout } from './tree-layout'

const opts = { nodeWidth: 100, nodeHeight: 50, hGap: 20, vGap: 40, maxRowWidth: 1000 }

function leaf(id: string): LayoutInput {
  return { id, children: [] }
}

function node(id: string, ...children: LayoutInput[]): LayoutInput {
  return { id, children }
}

function at(layout: TreeLayout, id: string) {
  const p = layout.nodes.get(id)
  if (!p) throw new Error(`no position for ${id}`)
  return p
}

const centerX = (layout: TreeLayout, id: string) => at(layout, id).x + opts.nodeWidth / 2

/** The tree from the flowwave UAT: a request whose notifications child has children of its own. */
const dashboard = node(
  'request',
  leaf('tasks'),
  leaf('pets'),
  node('notifications', leaf('unread'), leaf('recent')),
  leaf('failing'),
)

describe('layoutTree', () => {
  it('places a single node at the origin and sizes the layout to it', () => {
    const layout = layoutTree([leaf('a')], opts)

    expect(at(layout, 'a')).toEqual({ x: 0, y: 0 })
    expect(layout.width).toBe(100)
    expect(layout.height).toBe(50)
    expect(layout.edges).toEqual([])
  })

  it('puts children one level below their parent', () => {
    const layout = layoutTree([dashboard], opts)

    expect(at(layout, 'tasks').y).toBe(at(layout, 'request').y + opts.nodeHeight + opts.vGap)
    expect(at(layout, 'unread').y).toBe(at(layout, 'notifications').y + opts.nodeHeight + opts.vGap)
  })

  it('centres a parent over its first and last child', () => {
    const layout = layoutTree([dashboard], opts)

    expect(centerX(layout, 'request')).toBe((centerX(layout, 'tasks') + centerX(layout, 'failing')) / 2)
    expect(centerX(layout, 'notifications')).toBe((centerX(layout, 'unread') + centerX(layout, 'recent')) / 2)
  })

  it('gives a child with its own children enough room that no two nodes on a row overlap', () => {
    const layout = layoutTree([dashboard], opts)

    const rows = new Map<number, number[]>()
    for (const { x, y } of layout.nodes.values()) rows.set(y, [...(rows.get(y) ?? []), x])
    for (const xs of rows.values()) {
      xs.sort((a, b) => a - b)
      for (let i = 1; i < xs.length; i++) {
        expect(xs[i]! - xs[i - 1]!).toBeGreaterThanOrEqual(opts.nodeWidth + opts.hGap)
      }
    }
  })

  it('keeps every node inside the layout bounds', () => {
    const layout = layoutTree([dashboard, leaf('other')], opts)

    for (const { x, y } of layout.nodes.values()) {
      expect(x).toBeGreaterThanOrEqual(0)
      expect(y).toBeGreaterThanOrEqual(0)
      expect(x + opts.nodeWidth).toBeLessThanOrEqual(layout.width)
      expect(y + opts.nodeHeight).toBeLessThanOrEqual(layout.height)
    }
  })

  it('draws one edge per child, from the parent bottom centre to the child top centre', () => {
    const layout = layoutTree([dashboard], opts)

    expect(layout.edges.map((e) => e.childId).sort()).toEqual(
      ['failing', 'notifications', 'pets', 'recent', 'tasks', 'unread'],
    )
    for (const edge of layout.edges) {
      const parent = at(layout, edge.parentId)
      const child = at(layout, edge.childId)
      expect(edge.from).toEqual({ x: parent.x + opts.nodeWidth / 2, y: parent.y + opts.nodeHeight })
      expect(edge.to).toEqual({ x: child.x + opts.nodeWidth / 2, y: child.y })
      expect(edge.path.startsWith(`M ${edge.from.x} ${edge.from.y}`)).toBe(true)
      expect(edge.path.endsWith(`${edge.to.x} ${edge.to.y}`)).toBe(true)
    }
  })

  it('places roots side by side and wraps them into rows at the maximum row width', () => {
    const roots = ['r1', 'r2', 'r3', 'r4', 'r5'].map(leaf)
    // 100 wide + 20 gap: three fit in 360, the fourth would need 460
    const layout = layoutTree(roots, { ...opts, maxRowWidth: 360 })

    expect(at(layout, 'r1').y).toBe(0)
    expect(at(layout, 'r3').y).toBe(0)
    expect(at(layout, 'r4').y).toBeGreaterThan(0)
    expect(at(layout, 'r4').x).toBe(0)
    expect(at(layout, 'r2').x).toBe(at(layout, 'r1').x + opts.nodeWidth + opts.hGap)
  })

  it('starts the next row of roots below the tallest tree in the row before', () => {
    const tall = node('tall', node('mid', leaf('deep')))
    const layout = layoutTree([tall, leaf('short'), leaf('next')], { ...opts, maxRowWidth: 240 })

    const tallBottom = at(layout, 'deep').y + opts.nodeHeight
    expect(at(layout, 'next').y).toBeGreaterThanOrEqual(tallBottom + opts.vGap)
  })

  it('lets small roots fill a row as wide as the widest tree', () => {
    // the wide tree is 580 across, so the view is scaled to 580 anyway; four leaves fit in 580
    const wide = node('wide', ...['a', 'b', 'c', 'd', 'e'].map(leaf))
    const layout = layoutTree([wide, ...['r1', 'r2', 'r3', 'r4'].map(leaf)], { ...opts, maxRowWidth: 300 })

    expect(at(layout, 'r4').y).toBe(at(layout, 'r1').y)
    expect(layout.width).toBe(580)
  })

  it('keeps a tree wider than the maximum row width on a row of its own', () => {
    const wide = node('wide', ...['a', 'b', 'c', 'd', 'e'].map(leaf))
    const layout = layoutTree([leaf('before'), wide, leaf('after')], { ...opts, maxRowWidth: 300 })

    expect(at(layout, 'wide').y).toBeGreaterThan(at(layout, 'before').y)
    expect(at(layout, 'after').y).toBeGreaterThan(at(layout, 'a').y)
    expect(layout.width).toBeGreaterThan(300)
  })
})

describe('chooseRowWidth', () => {
  const size = { nodeWidth: 100, nodeHeight: 50, hGap: 20, vGap: 40, minReadableScale: 0.5 }

  it('keeps a few roots at full size within the canvas width', () => {
    expect(chooseRowWidth(3, 800, 600, size)).toBe(800)
  })

  it('uses more columns for many roots, but no wider than readable at the minimum scale', () => {
    expect(chooseRowWidth(500, 800, 600, size)).toBe(1600)
  })

  it('grows with the number of roots between those bounds', () => {
    const some = chooseRowWidth(60, 800, 600, size)
    expect(some).toBeGreaterThan(800)
    expect(some).toBeLessThan(1600)
  })

  it('falls back to the given default before the canvas is measured', () => {
    expect(chooseRowWidth(10, 0, 0, size, 1200)).toBe(1200)
  })
})

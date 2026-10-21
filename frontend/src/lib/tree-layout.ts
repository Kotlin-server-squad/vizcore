/**
 * Positions for the coroutine graph (#216, #147).
 *
 * Every subtree is as wide as its children need, so a child with children of
 * its own pushes its siblings apart instead of spilling over them, and every
 * edge is computed from the same positions the nodes are drawn at, so a line
 * always meets its card. Roots sit side by side and wrap into rows at
 * [LayoutOptions.maxRowWidth], or at the widest root's width when that is
 * wider (the view is scaled to that tree anyway, so small roots may fill the
 * same width); a root wider than the row limit gets a row of its own.
 *
 * Pure: the graph view draws what this returns and the tests check it here.
 */

export interface LayoutInput {
  id: string
  children: LayoutInput[]
}

export interface LayoutOptions {
  nodeWidth: number
  nodeHeight: number
  /** Horizontal space between neighbouring subtrees and roots. */
  hGap: number
  /** Vertical space between a parent and its children, and between rows of roots. */
  vGap: number
  /** Roots wrap to a new row once a row would be wider than this. */
  maxRowWidth: number
}

export interface Point {
  x: number
  y: number
}

export interface LayoutEdge {
  parentId: string
  childId: string
  /** Bottom centre of the parent. */
  from: Point
  /** Top centre of the child. */
  to: Point
  /** SVG path: down from the parent, across at mid-gap, down into the child. */
  path: string
}

export interface TreeLayout {
  /** Top-left corner of each node. */
  nodes: Map<string, Point>
  edges: LayoutEdge[]
  width: number
  height: number
}

interface Extent {
  width: number
  height: number
}

export function layoutTree(roots: LayoutInput[], opts: LayoutOptions): TreeLayout {
  const { nodeWidth, nodeHeight, hGap, vGap, maxRowWidth } = opts
  const extents = new Map<string, Extent>()
  const nodes = new Map<string, Point>()
  const edges: LayoutEdge[] = []

  function measure(node: LayoutInput): Extent {
    let childrenWidth = 0
    let childrenHeight = 0
    node.children.forEach((child, i) => {
      const e = measure(child)
      childrenWidth += e.width + (i > 0 ? hGap : 0)
      childrenHeight = Math.max(childrenHeight, e.height)
    })
    const extent = {
      width: Math.max(nodeWidth, childrenWidth),
      height: nodeHeight + (node.children.length > 0 ? vGap + childrenHeight : 0),
    }
    extents.set(node.id, extent)
    return extent
  }

  /** Places [node]'s subtree in the box starting at [left], [top]; returns the node's centre x. */
  function place(node: LayoutInput, left: number, top: number): number {
    const width = extents.get(node.id)!.width
    let centerX = left + width / 2
    if (node.children.length > 0) {
      const childTop = top + nodeHeight + vGap
      let cursor = left
      const centers = node.children.map((child) => {
        const c = place(child, cursor, childTop)
        cursor += extents.get(child.id)!.width + hGap
        return c
      })
      centerX = (centers[0]! + centers[centers.length - 1]!) / 2
      node.children.forEach((child, i) => edges.push(edge(node.id, child.id, centerX, top, centers[i]!, childTop)))
    }
    nodes.set(node.id, { x: centerX - nodeWidth / 2, y: top })
    return centerX
  }

  function edge(parentId: string, childId: string, px: number, parentTop: number, cx: number, childTop: number): LayoutEdge {
    const from = { x: px, y: parentTop + nodeHeight }
    const to = { x: cx, y: childTop }
    const midY = from.y + vGap / 2
    return {
      parentId,
      childId,
      from,
      to,
      path: `M ${from.x} ${from.y} L ${from.x} ${midY} L ${to.x} ${midY} L ${to.x} ${to.y}`,
    }
  }

  const rootExtents = roots.map(measure)
  const rowLimit = Math.max(maxRowWidth, ...rootExtents.map((e) => e.width))

  let cursorX = 0
  let rowTop = 0
  let rowHeight = 0
  let width = 0
  roots.forEach((root, i) => {
    const e = rootExtents[i]!
    if (cursorX > 0 && cursorX + e.width > rowLimit) {
      rowTop += rowHeight + vGap
      cursorX = 0
      rowHeight = 0
    }
    place(root, cursorX, rowTop)
    width = Math.max(width, cursorX + e.width)
    cursorX += e.width + hGap
    rowHeight = Math.max(rowHeight, e.height)
  })

  return { nodes, edges, width, height: rowTop + rowHeight }
}

export interface RowSizing {
  nodeWidth: number
  nodeHeight: number
  hGap: number
  vGap: number
  /** Rows never get wider than what still reads when scaled down to this. */
  minReadableScale: number
}

/**
 * How wide a row of roots may get on a canvas of [canvasWidth] × [canvasHeight].
 *
 * A few roots stay at full size within the canvas width. Many roots (a session
 * of separate request coroutines) get more columns, aiming for a grid with the
 * canvas's proportions, but never so many that fitting them would scale the
 * cards below [RowSizing.minReadableScale].
 */
export function chooseRowWidth(
  rootCount: number,
  canvasWidth: number,
  canvasHeight: number,
  sizing: RowSizing,
  fallback = 1200,
): number {
  if (canvasWidth <= 0 || canvasHeight <= 0) return fallback
  const slotWidth = sizing.nodeWidth + sizing.hGap
  const slotHeight = sizing.nodeHeight + sizing.vGap
  const columns = Math.ceil(Math.sqrt(rootCount * (canvasWidth / canvasHeight) * (slotHeight / slotWidth)))
  const ideal = columns * slotWidth - sizing.hGap
  return Math.min(canvasWidth / sizing.minReadableScale, Math.max(canvasWidth, ideal))
}

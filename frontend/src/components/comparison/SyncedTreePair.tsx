import { useState } from 'react'
import { Card, CardBody, CardHeader, Chip } from '@heroui/react'
import { buildCoroutineTree } from '@/lib/utils'
import { getStateColors } from '@/lib/coroutine-state-colors'
import type { CoroutineNode, SessionComparison } from '@/types/api'

export interface SyncedTreePairProps {
  sessionAId: string
  sessionBId: string
  coroutinesA: CoroutineNode[]
  coroutinesB: CoroutineNode[]
  comparison: SessionComparison
}

/** A side of the pair, for delta-badge styling. */
type Side = 'a' | 'b'

/** The node the user last clicked — drives the cross-tree selection sync. */
interface SelectedNode {
  id: string
  label: string | null
}

/**
 * Two synchronized coroutine trees rendered side-by-side (D-19). Each side
 * shows its session's tree; nodes unique to one session carry a delta badge +
 * ring outline (A-only warning, B-only secondary). Clicking any node selects
 * it and highlights its counterpart in the other tree with `ring-2 ring-primary`
 * — counterparts are matched by label first, then by coroutineId. There is no
 * scroll/zoom coupling (D-20).
 */
export function SyncedTreePair({
  sessionAId,
  sessionBId,
  coroutinesA,
  coroutinesB,
  comparison,
}: SyncedTreePairProps) {
  const [selected, setSelected] = useState<SelectedNode | null>(null)

  const onlyInA = new Set(comparison.coroutinesOnlyInA)
  const onlyInB = new Set(comparison.coroutinesOnlyInB)

  /** True when `node` is (or is the counterpart of) the current selection. */
  function isHighlighted(node: CoroutineNode): boolean {
    if (!selected) return false
    if (selected.label && node.label) return selected.label === node.label
    return selected.id === node.id
  }

  return (
    <div className="grid grid-cols-2 gap-4" data-testid="synced-tree-pair">
      <TreeColumn
        side="a"
        sessionId={sessionAId}
        coroutines={coroutinesA}
        deltaIds={onlyInA}
        selected={selected}
        isHighlighted={isHighlighted}
        onSelect={setSelected}
      />
      <TreeColumn
        side="b"
        sessionId={sessionBId}
        coroutines={coroutinesB}
        deltaIds={onlyInB}
        selected={selected}
        isHighlighted={isHighlighted}
        onSelect={setSelected}
      />
    </div>
  )
}

interface TreeColumnProps {
  side: Side
  sessionId: string
  coroutines: CoroutineNode[]
  deltaIds: Set<string>
  selected: SelectedNode | null
  isHighlighted: (node: CoroutineNode) => boolean
  onSelect: (node: SelectedNode) => void
}

function TreeColumn({
  side,
  sessionId,
  coroutines,
  deltaIds,
  isHighlighted,
  onSelect,
}: TreeColumnProps) {
  const tree = buildCoroutineTree(coroutines)
  const label = side === 'a' ? 'A' : 'B'

  return (
    <Card data-testid={`synced-tree-${side}`}>
      <CardHeader className="text-lg font-semibold">
        Session {label} —{' '}
        <span className="font-mono text-sm text-default-500 ml-1">{sessionId}</span>
      </CardHeader>
      <CardBody className="space-y-2">
        {tree.length === 0 ? (
          <div className="py-8 text-center text-default-400">
            No coroutines in this session.
          </div>
        ) : (
          tree.map((n) => (
            <SyncedTreeNode
              key={n.id}
              node={n}
              depth={0}
              side={side}
              deltaIds={deltaIds}
              isHighlighted={isHighlighted}
              onSelect={onSelect}
            />
          ))
        )}
      </CardBody>
    </Card>
  )
}

type TreeNode = CoroutineNode & { children: TreeNode[] }

interface SyncedTreeNodeProps {
  node: TreeNode
  depth: number
  side: Side
  deltaIds: Set<string>
  isHighlighted: (node: CoroutineNode) => boolean
  onSelect: (node: SelectedNode) => void
}

function SyncedTreeNode({
  node,
  depth,
  side,
  deltaIds,
  isHighlighted,
  onSelect,
}: SyncedTreeNodeProps) {
  const colors = getStateColors(node.state)
  const isDelta = deltaIds.has(node.id)
  const highlighted = isHighlighted(node)

  // Ring precedence: an active selection ring (primary) wins over the delta
  // outline so the highlighted counterpart is unambiguous.
  const ringClass = highlighted
    ? 'ring-2 ring-primary'
    : isDelta
    ? side === 'a'
      ? 'ring-1 ring-warning'
      : 'ring-1 ring-secondary'
    : ''

  const deltaLabel = side === 'a' ? 'A only' : 'B only'
  const deltaColor = side === 'a' ? 'warning' : 'secondary'

  return (
    <div style={{ marginLeft: `${depth * 16}px` }}>
      <button
        type="button"
        data-testid={`synced-node-${side}-${node.id}`}
        aria-label={`${node.label || node.id} (Session ${side.toUpperCase()})`}
        aria-pressed={highlighted}
        onClick={() => onSelect({ id: node.id, label: node.label })}
        className={`flex w-full items-center justify-between gap-2 rounded-md border border-default-200 bg-content1 px-3 py-2 text-left transition-shadow ${ringClass}`}
      >
        <div className="flex items-center gap-2 min-w-0">
          <div className={colors.text}>
            <colors.Icon className="h-4 w-4 shrink-0" />
          </div>
          <div className="min-w-0">
            <div className="truncate text-sm font-medium">{node.label || node.id}</div>
            <div className="truncate text-xs text-default-500">{node.id}</div>
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          {isDelta && (
            <Chip size="sm" variant="flat" color={deltaColor} data-testid={`delta-${side}`}>
              {deltaLabel}
            </Chip>
          )}
          <Chip size="sm" variant="flat" color={colors.chipColor}>
            {node.state}
          </Chip>
        </div>
      </button>

      {node.children.map((child) => (
        <SyncedTreeNode
          key={child.id}
          node={child}
          depth={depth + 1}
          side={side}
          deltaIds={deltaIds}
          isHighlighted={isHighlighted}
          onSelect={onSelect}
        />
      ))}
    </div>
  )
}

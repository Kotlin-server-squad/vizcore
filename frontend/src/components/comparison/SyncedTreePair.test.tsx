import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SyncedTreePair } from './SyncedTreePair'
import { CoroutineState } from '@/types/api'
import type { CoroutineNode, SessionComparison } from '@/types/api'

function node(id: string, label: string | null = null): CoroutineNode {
  return {
    id,
    jobId: `job-${id}`,
    parentId: null,
    scopeId: 'scope-1',
    label,
    state: CoroutineState.ACTIVE,
  }
}

// A: shares "worker" (coro-shared) with B, plus an A-only node.
// B: shares "worker", plus a B-only node.
const coroutinesA: CoroutineNode[] = [
  node('coro-shared', 'worker'),
  node('coro-a-only', 'a-task'),
]
const coroutinesB: CoroutineNode[] = [
  node('coro-shared', 'worker'),
  node('coro-b-only', 'b-task'),
]

const comparison: SessionComparison = {
  sessionA: 'sess-a',
  sessionB: 'sess-b',
  coroutineCountDiff: 0,
  eventCountDiff: 0,
  totalDurationDiffNanos: 0,
  distinctThreadsDiff: 0,
  coroutinesOnlyInA: ['coro-a-only'],
  coroutinesOnlyInB: ['coro-b-only'],
  commonCoroutines: [
    {
      coroutineId: 'coro-shared',
      label: 'worker',
      stateA: 'ACTIVE',
      stateB: 'ACTIVE',
      eventCountA: 3,
      eventCountB: 3,
    },
  ],
}

function renderPair() {
  return render(
    <SyncedTreePair
      sessionAId="sess-a"
      sessionBId="sess-b"
      coroutinesA={coroutinesA}
      coroutinesB={coroutinesB}
      comparison={comparison}
    />,
  )
}

describe('SyncedTreePair', () => {
  it('renders two trees with A-only/B-only delta badges and ring outlines', () => {
    renderPair()

    // Two session cards with headers (header text + id span are separate nodes).
    const treeA = screen.getByTestId('synced-tree-a')
    const treeB = screen.getByTestId('synced-tree-b')
    expect(within(treeA).getByText(/Session A/)).toBeInTheDocument()
    expect(within(treeA).getByText('sess-a')).toBeInTheDocument()
    expect(within(treeB).getByText(/Session B/)).toBeInTheDocument()
    expect(within(treeB).getByText('sess-b')).toBeInTheDocument()

    // A-only node carries an "A only" warning chip + ring-warning outline.
    const aOnly = screen.getByTestId('synced-node-a-coro-a-only')
    expect(within(aOnly).getByText('A only')).toBeInTheDocument()
    expect(aOnly.className).toContain('ring-warning')

    // B-only node carries a "B only" secondary chip + ring-secondary outline.
    const bOnly = screen.getByTestId('synced-node-b-coro-b-only')
    expect(within(bOnly).getByText('B only')).toBeInTheDocument()
    expect(bOnly.className).toContain('ring-secondary')

    // Common node has no delta ring.
    const commonA = screen.getByTestId('synced-node-a-coro-shared')
    expect(commonA.className).not.toContain('ring-warning')
    expect(commonA.className).not.toContain('ring-secondary')
    expect(within(commonA).queryByText('A only')).not.toBeInTheDocument()
  })

  it('highlights a clicked common node AND its counterpart in the other tree', async () => {
    const user = userEvent.setup()
    renderPair()

    const commonA = screen.getByTestId('synced-node-a-coro-shared')
    await user.click(commonA)

    // Both the clicked node and its B counterpart get ring-primary.
    expect(screen.getByTestId('synced-node-a-coro-shared').className).toContain('ring-primary')
    expect(screen.getByTestId('synced-node-b-coro-shared').className).toContain('ring-primary')
  })

  it('highlights only the A node when an A-only node (no counterpart) is clicked', async () => {
    const user = userEvent.setup()
    renderPair()

    const aOnly = screen.getByTestId('synced-node-a-coro-a-only')
    await user.click(aOnly)

    expect(screen.getByTestId('synced-node-a-coro-a-only').className).toContain('ring-primary')
    // No B node should gain the primary selection ring.
    const bSharedRing = screen.getByTestId('synced-node-b-coro-shared').className
    const bOnlyRing = screen.getByTestId('synced-node-b-coro-b-only').className
    expect(bSharedRing).not.toContain('ring-primary')
    expect(bOnlyRing).not.toContain('ring-primary')
  })
})

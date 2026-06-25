import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { CoroutineTree } from './CoroutineTree'
import type { CoroutineNode } from '@/types/api'
import { CoroutineState } from '@/types/api'

vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => <div {...props}>{children as ReactNode}</div>,
    span: ({ children, ...props }: Record<string, unknown>) => <span {...props}>{children as ReactNode}</span>,
  },
  AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
  LayoutGroup: ({ children }: { children: ReactNode }) => <>{children}</>,
}))

vi.mock('@/lib/animation-throttle', () => ({
  useAnimationSlot: () => false,
}))

function makeCoroutine(overrides: Partial<CoroutineNode> = {}): CoroutineNode {
  return {
    id: 'c-1',
    jobId: 'j-1',
    parentId: null,
    scopeId: 'scope-1',
    label: 'TestCoroutine',
    state: CoroutineState.ACTIVE,
    ...overrides,
  }
}

describe('CoroutineTree', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders empty state when no coroutines', () => {
    render(<CoroutineTree coroutines={[]} />)

    expect(screen.getByText('No coroutines in this session yet.')).toBeInTheDocument()
  })

  it('renders coroutine nodes with labels', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-1', label: 'ParentScope' }),
    ]

    render(<CoroutineTree coroutines={coroutines} />)

    expect(screen.getByText('ParentScope')).toBeInTheDocument()
  })

  it('renders state chips for each coroutine', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-1', label: 'Worker-1', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'c-2', label: 'Worker-2', state: CoroutineState.COMPLETED }),
    ]

    render(<CoroutineTree coroutines={coroutines} />)

    expect(screen.getByText('ACTIVE')).toBeInTheDocument()
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
  })

  it('renders nested tree with indentation', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-parent', label: 'Parent', parentId: null }),
      makeCoroutine({ id: 'c-child', label: 'Child', parentId: 'c-parent' }),
    ]

    render(<CoroutineTree coroutines={coroutines} />)

    expect(screen.getByText('Parent')).toBeInTheDocument()
    expect(screen.getByText('Child')).toBeInTheDocument()
  })

  it('shows waiting indicator for WAITING_FOR_CHILDREN state', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({
        id: 'c-parent',
        label: 'WaitingParent',
        state: CoroutineState.WAITING_FOR_CHILDREN,
      }),
      makeCoroutine({
        id: 'c-child',
        label: 'ActiveChild',
        parentId: 'c-parent',
        state: CoroutineState.ACTIVE,
      }),
    ]

    render(<CoroutineTree coroutines={coroutines} />)

    expect(screen.getByText('WAITING_FOR_CHILDREN')).toBeInTheDocument()
    expect(screen.getByText(/Waiting for.*child coroutine/)).toBeInTheDocument()
  })

  it('shows failure indicator for FAILED state', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({
        id: 'c-fail',
        label: 'FailedWorker',
        state: CoroutineState.FAILED,
      }),
    ]

    render(<CoroutineTree coroutines={coroutines} />)

    expect(screen.getByText('FAILED')).toBeInTheDocument()
    expect(screen.getByText('Coroutine Failed')).toBeInTheDocument()
    expect(
      screen.getByText(/Exception thrown.*will cancel parent and siblings/),
    ).toBeInTheDocument()
  })

  it('makes nodes clickable when onSelect is provided and fires onSelect with the node id (D-06)', async () => {
    const onSelect = vi.fn()
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-7', label: 'Clickable' }),
    ]

    render(<CoroutineTree coroutines={coroutines} onSelect={onSelect} />)

    const node = screen.getByRole('button', { name: 'Open source for Clickable' })
    expect(node).toBeInTheDocument()

    await userEvent.click(node)
    expect(onSelect).toHaveBeenCalledWith('c-7')
  })

  it('applies the ring-2 ring-primary highlight to the selected node (D-05)', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-sel', label: 'Selected' }),
    ]

    render(
      <CoroutineTree
        coroutines={coroutines}
        onSelect={vi.fn()}
        selectedNodeId="c-sel"
      />,
    )

    const node = screen.getByRole('button', { name: 'Open source for Selected' })
    expect(node.className).toContain('ring-2')
    expect(node.className).toContain('ring-primary')
  })

  it('keeps nodes presentational (no role=button) when onSelect is omitted (back-compat)', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-inert', label: 'Presentational' }),
    ]

    render(<CoroutineTree coroutines={coroutines} />)

    expect(screen.getByText('Presentational')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Open source for Presentational' }),
    ).toBeNull()
  })
})

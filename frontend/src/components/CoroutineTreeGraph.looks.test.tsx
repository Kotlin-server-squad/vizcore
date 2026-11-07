import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { ReactNode } from 'react'
import { CoroutineTreeGraph } from './CoroutineTreeGraph'
import { LOOK_STORAGE_KEY, LookProvider, forgetVisitLook } from '@/looks/look-context'
import { CARDS, LOOKS } from '@/looks/registry'
import type { ActivityLine } from '@/lib/activity'
import { withDarkTheme } from '@/lib/export-theme'
import { CoroutineState, type CoroutineNode } from '@/types/api'

// The per-look graph cards: every look draws the same card markup (so click,
// keyboard, the selection ring, the second line and the labels work alike) and
// styles it through data-look-part hooks; the layout reserves the look's size.

vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => (
      <div {...props}>{children as ReactNode}</div>
    ),
  },
  AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))
vi.mock('react-zoom-pan-pinch', async () => {
  const React = await import('react')
  const TransformWrapper = React.forwardRef(function TransformWrapper(
    props: Record<string, unknown>,
    ref: React.ForwardedRef<unknown>
  ) {
    const api = { setTransform: vi.fn(), state: { positionX: 0, positionY: 0, scale: 1 } }
    React.useImperativeHandle(ref, () => api)
    const { children } = props
    return <div>{typeof children === 'function' ? children(api) : (children as ReactNode)}</div>
  })
  return {
    TransformWrapper,
    TransformComponent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  }
})

/** The graph's padding around the tree (CoroutineTreeGraph's PADDING). */
const PADDING = 32

function coroutine(overrides: Partial<CoroutineNode>): CoroutineNode {
  return {
    id: 'c',
    jobId: 'job-c',
    parentId: null,
    scopeId: 'scope-1',
    label: 'c',
    state: CoroutineState.ACTIVE,
    ...overrides,
  }
}

const tree: CoroutineNode[] = [
  coroutine({ id: 'root', label: 'checkout', state: CoroutineState.WAITING_FOR_CHILDREN }),
  coroutine({ id: 'o1', label: 'order-1', parentId: 'root', state: CoroutineState.ACTIVE }),
  coroutine({ id: 'o2', label: 'order-2', parentId: 'root', state: CoroutineState.SUSPENDED }),
  coroutine({ id: 'o3', label: 'order-3', parentId: 'root', state: CoroutineState.COMPLETED }),
]
const activity = new Map<string, ActivityLine>([
  ['o1', { text: 'holds inventory-lock', waiting: false } as ActivityLine],
  ['o2', { text: 'waits for inventory-lock · 1st', waiting: true } as ActivityLine],
])

function renderIn(
  look: string | null,
  props: Partial<Parameters<typeof CoroutineTreeGraph>[0]> = {}
) {
  const graph = <CoroutineTreeGraph coroutines={tree} activity={activity} {...props} />
  if (look === null) return render(graph)
  localStorage.setItem(LOOK_STORAGE_KEY, look)
  return render(<LookProvider>{graph}</LookProvider>)
}

function box(container: HTMLElement, id: string) {
  const el = container.querySelector<HTMLElement>(`[data-testid="graph-node-${id}"]`)!
  return {
    el,
    left: parseFloat(el.style.left),
    top: parseFloat(el.style.top),
    width: parseFloat(el.style.width),
    height: parseFloat(el.style.height),
  }
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
})

describe('graph cards per look', () => {
  it('gives every look a card size, and Classic 280 × 260 (Part 7 added its facts row)', () => {
    for (const { id } of LOOKS) expect(CARDS[id], id).toBeDefined()
    expect(CARDS.classic).toEqual({ width: 280, height: 260 })
  })

  it('draws Classic’s cards at its size outside the session page (no look)', () => {
    const { container } = renderIn(null)
    expect(box(container, 'o1')).toMatchObject({ width: 280, height: 260 })
  })

  it.each(LOOKS.map(l => l.id))(
    'lays look %s out with its own card size, so cards never overlap',
    id => {
      const { width, height } = CARDS[id]
      const { container } = renderIn(id)
      const [root, o1, o2, o3] = ['root', 'o1', 'o2', 'o3'].map(n => box(container, n))
      for (const card of [root!, o1!, o2!, o3!]) expect(card).toMatchObject({ width, height })
      // Siblings side by side, at least a card width apart; children a card height below.
      expect(o2!.left - o1!.left).toBeGreaterThanOrEqual(width)
      expect(o3!.left - o2!.left).toBeGreaterThanOrEqual(width)
      expect(o1!.top - root!.top).toBeGreaterThanOrEqual(height)
    }
  )

  it.each(LOOKS.map(l => l.id))(
    'look %s: every edge leaves its parent’s bottom centre and meets its child’s top centre',
    id => {
      const { width, height } = CARDS[id]
      const { container } = renderIn(id)
      const root = box(container, 'root')
      for (const edge of container.querySelectorAll('[data-testid="graph-edge"]')) {
        const child = box(container, edge.getAttribute('data-child')!)
        const numbers = (edge.getAttribute('d') ?? '').match(/-?\d+(\.\d+)?/g)!.map(Number)
        expect(numbers.slice(0, 2)).toEqual([
          root.left - PADDING + width / 2,
          root.top - PADDING + height,
        ])
        expect(numbers.slice(-2)).toEqual([child.left - PADDING + width / 2, child.top - PADDING])
      }
    }
  )

  it.each(LOOKS.map(l => l.id))('look %s: the "N children" label sits just under its card', id => {
    const { height } = CARDS[id]
    renderIn(id)
    const label = screen.getByText('3 children')
    expect(label).toHaveAttribute('data-look-part', 'node-children')
    expect(parseFloat(label.style.top)).toBe(height + 6)
  })

  it.each(LOOKS.map(l => l.id))(
    'look %s: a card selects by click and keyboard, rings when selected, and keeps its label',
    id => {
      const onSelect = vi.fn()
      renderIn(id, { onSelect, selectedNodeId: 'o2' })
      const card = screen.getByRole('button', { name: 'Open source for order-1' })
      fireEvent.click(card)
      fireEvent.keyDown(card, { key: 'Enter' })
      fireEvent.keyDown(card, { key: ' ' })
      expect(onSelect.mock.calls).toEqual([['o1'], ['o1'], ['o1']])
      expect(screen.getByRole('button', { name: 'Open source for order-2' })).toHaveClass(
        'ring-2',
        'ring-primary'
      )
      expect(card).not.toHaveClass('ring-2')
    }
  )

  it.each(LOOKS.map(l => l.id))(
    'look %s: a card carries the hooks its CSS styles: name, state, second line, ids',
    id => {
      const { container } = renderIn(id)
      const wrapper = box(container, 'o2').el
      expect(wrapper).toHaveAttribute('data-state', 'SUSPENDED')
      const card = wrapper.querySelector('[data-look-part="node"]')!
      expect(card.querySelector('[data-look-part="node-name"]')).toHaveTextContent('order-2')
      expect(card.querySelector('[data-look-part="node-state"]')).toHaveTextContent('Suspended')
      expect(card.querySelector('[data-look-part="node-activity"]')).toHaveTextContent(
        'waits for inventory-lock · 1st'
      )
      expect(card.querySelector('[data-look-part="node-details"]')).toHaveTextContent('job-c')
      expect(card.querySelector('[data-look-part="node-icon"]')).not.toBeNull()
    }
  )

  it.each(LOOKS.map(l => l.id))(
    'look %s: an export keeps the card styling that fits the card size (data-card-look on the graph, not <html>)',
    id => {
      const { getByTestId } = renderIn(id)
      const canvas = getByTestId('graph-canvas')
      expect(canvas).toHaveAttribute('data-card-look', id)
      document.documentElement.className = 'light'
      const during = withDarkTheme(() => ({
        look: document.documentElement.dataset.look,
        cards: canvas.getAttribute('data-card-look'),
      }))
      // The page's look is set aside for the capture (Classic dark), the cards' is not.
      expect(during).toEqual({ look: undefined, cards: id })
      document.documentElement.className = 'dark'
    }
  )

  it('marks Classic’s graph as Classic outside the session page', () => {
    const { getByTestId } = renderIn(null)
    expect(getByTestId('graph-canvas')).toHaveAttribute('data-card-look', 'classic')
  })
})

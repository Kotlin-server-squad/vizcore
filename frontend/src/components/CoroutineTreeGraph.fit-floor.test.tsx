import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, fireEvent } from '@testing-library/react'
import type { ReactNode } from 'react'
import { CoroutineTreeGraph } from './CoroutineTreeGraph'
import { LOOK_STORAGE_KEY, LookProvider, forgetVisitLook } from '@/looks/look-context'
import { CARDS, LOOKS, type LookId } from '@/looks/registry'
import { CoroutineState, type CoroutineNode } from '@/types/api'

// A look's fit zoom floor (card.ts `fitMinZoom`): the first fit keeps the cards
// readable and frames the root and the top of the tree; "Fit to view" still
// shows everything. Classic has no floor and fits as before.

vi.mock('framer-motion', () => ({
  motion: {
    div: ({ children, ...props }: Record<string, unknown>) => (
      <div {...props}>{children as ReactNode}</div>
    ),
  },
  AnimatePresence: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
vi.mock('@/lib/animation-throttle', () => ({ useAnimationSlot: () => false }))

const zoom = vi.hoisted(() => ({ setTransform: vi.fn() }))
vi.mock('react-zoom-pan-pinch', async () => {
  const React = await import('react')
  const TransformWrapper = React.forwardRef(function TransformWrapper(
    props: Record<string, unknown>,
    ref: React.ForwardedRef<unknown>
  ) {
    const api = { setTransform: zoom.setTransform, state: { positionX: 0, positionY: 0, scale: 1 } }
    React.useImperativeHandle(ref, () => api)
    const { children } = props
    return <div>{typeof children === 'function' ? children(api) : (children as ReactNode)}</div>
  })
  return {
    TransformWrapper,
    TransformComponent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  }
})

type ResizeCallback = (entries: ResizeObserverEntry[], observer: ResizeObserver) => void
const observers = new Set<ResizeCallback>()
class FakeResizeObserver {
  constructor(private readonly callback: ResizeCallback) {}
  observe() {
    observers.add(this.callback)
  }
  unobserve() {}
  disconnect() {
    observers.delete(this.callback)
  }
}
function resizeCanvas(width: number, height: number) {
  act(() => {
    for (const cb of observers)
      cb([{ contentRect: { width, height } } as ResizeObserverEntry], {} as ResizeObserver)
  })
}

const TOOLBAR_INSET = 40

/** A root with eight children and a grandchild: too wide and too tall for a short graph area. */
const tree: CoroutineNode[] = [
  {
    id: 'root',
    jobId: 'j',
    parentId: null,
    scopeId: 's',
    label: 'checkout',
    state: CoroutineState.WAITING_FOR_CHILDREN,
  },
  ...Array.from({ length: 8 }, (_, i) => ({
    id: `o${i}`,
    jobId: 'j',
    parentId: 'root',
    scopeId: 's',
    label: `order-${i}`,
    state: CoroutineState.SUSPENDED,
  })),
  {
    id: 'deep',
    jobId: 'j',
    parentId: 'o0',
    scopeId: 's',
    label: 'reserve',
    state: CoroutineState.ACTIVE,
  },
]

function renderIn(look: LookId | null) {
  const graph = <CoroutineTreeGraph coroutines={tree} />
  if (look === null) return render(graph)
  localStorage.setItem(LOOK_STORAGE_KEY, look)
  return render(<LookProvider>{graph}</LookProvider>)
}

/** The view the last fit set: x, y, scale. */
const lastView = () => {
  const call = zoom.setTransform.mock.calls.at(-1)!
  return { x: call[0] as number, y: call[1] as number, scale: call[2] as number }
}

/** The scale that shows the whole tree in a canvas, as the graph computes it. */
function fitAllScale(container: HTMLElement, width: number, height: number) {
  const inner = container.querySelector<HTMLElement>('[data-testid="graph-edges"]')!
  const contentWidth = parseFloat(inner.getAttribute('width')!)
  const contentHeight = parseFloat(inner.getAttribute('height')!)
  return Math.min(1, width / contentWidth, Math.max(0.5, (height - TOOLBAR_INSET) / contentHeight))
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
  zoom.setTransform.mockClear()
  observers.clear()
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
})

const floored = LOOKS.filter(l => CARDS[l.id].fitMinZoom !== undefined).map(l => l.id)

describe('the fit zoom floor', () => {
  it('is set for every trial look, at least 0.9, and not for Classic', () => {
    expect(CARDS.classic.fitMinZoom).toBeUndefined()
    for (const look of LOOKS.filter(l => l.trial)) {
      expect(CARDS[look.id].fitMinZoom, look.id).toBeGreaterThanOrEqual(0.9)
    }
  })

  it('leaves Classic fitting the whole tree, as before', () => {
    const { container } = renderIn('classic')
    resizeCanvas(1400, 300)
    const scale = fitAllScale(container, 1400, 300)
    expect(scale).toBeLessThan(0.9)
    expect(lastView()).toEqual({
      x:
        (1400 -
          scale *
            parseFloat(
              container.querySelector('[data-testid="graph-edges"]')!.getAttribute('width')!
            )) /
        2,
      y: TOOLBAR_INSET,
      scale,
    })
  })

  it.each(floored)(
    'look %s: a tree too big for the floor opens at the floor, framing the root and the top',
    look => {
      const floor = CARDS[look].fitMinZoom!
      const card = CARDS[look]
      const { container } = renderIn(look)
      resizeCanvas(1400, 300)
      expect(fitAllScale(container, 1400, 300)).toBeLessThan(floor)
      const view = lastView()
      expect(view.scale).toBe(floor)
      expect(view.y).toBe(TOOLBAR_INSET)
      // The root's card is in view, centred across the canvas.
      const root = container.querySelector<HTMLElement>('[data-testid="graph-node-root"]')!
      const rootCentre = view.x + (parseFloat(root.style.left) + card.width / 2) * floor
      expect(rootCentre).toBeCloseTo(700, 0)
    }
  )

  it.each(floored)('look %s: "Fit to view" still shows the whole tree', look => {
    const { container } = renderIn(look)
    resizeCanvas(1400, 300)
    fireEvent.click(screen.getByRole('button', { name: 'Fit to view' }))
    expect(lastView().scale).toBeCloseTo(fitAllScale(container, 1400, 300), 6)
  })

  it.each(floored)('look %s: a tree that fits above the floor is fitted as in Classic', look => {
    const { container } = renderIn(look)
    resizeCanvas(4000, 3000)
    const scale = fitAllScale(container, 4000, 3000)
    expect(scale).toBeGreaterThanOrEqual(CARDS[look].fitMinZoom!)
    expect(lastView().scale).toBeCloseTo(scale, 6)
  })

  it.each(floored)(
    'look %s: a narrow canvas at the floor keeps the left edge of the tree, not empty space',
    look => {
      const { container } = renderIn(look)
      resizeCanvas(500, 300)
      const view = lastView()
      const width = parseFloat(
        container.querySelector('[data-testid="graph-edges"]')!.getAttribute('width')!
      )
      expect(view.x).toBeLessThanOrEqual(0)
      expect(view.x).toBeGreaterThanOrEqual(500 - width * view.scale)
    }
  )
})

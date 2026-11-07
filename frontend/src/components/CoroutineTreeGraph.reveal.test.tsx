import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { CoroutineTreeGraph } from './CoroutineTreeGraph'
import { LOOK_STORAGE_KEY, LookProvider, forgetVisitLook } from '@/looks/look-context'
import { CARDS } from '@/looks/registry'
import { CoroutineState, type CoroutineNode } from '@/types/api'

// Find reveals a coroutine (S-12): the graph pans to centre its card, zooming in to a readable size.

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

const lastView = () => {
  const call = zoom.setTransform.mock.calls.at(-1)!
  return { x: call[0] as number, y: call[1] as number, scale: call[2] as number }
}

beforeEach(() => {
  localStorage.clear()
  forgetVisitLook()
  zoom.setTransform.mockClear()
  observers.clear()
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
})

describe('revealing a coroutine', () => {
  it('centres its card, at least at a readable zoom, and leaves the view to the user once they zoom', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'classic')
    const { container, rerender } = render(
      <LookProvider>
        <CoroutineTreeGraph coroutines={tree} />
      </LookProvider>,
    )
    resizeCanvas(1400, 300)
    expect(lastView().scale).toBeLessThanOrEqual(0.5)

    rerender(
      <LookProvider>
        <CoroutineTreeGraph coroutines={tree} reveal={{ id: 'o7', key: 1 }} />
      </LookProvider>,
    )
    const view = lastView()
    expect(view.scale).toBeGreaterThanOrEqual(0.5)
    const card = container.querySelector<HTMLElement>('[data-testid="graph-node-o7"]')!
    const { width, height } = CARDS.classic
    expect(view.x + (parseFloat(card.style.left) + width / 2) * view.scale).toBeCloseTo(700, 0)
    expect(view.y + (parseFloat(card.style.top) + height / 2) * view.scale).toBeCloseTo(150, 0)

    // After the user zooms, the view is theirs: a new layout does not move it back.
    fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
    const calls = zoom.setTransform.mock.calls.length
    rerender(
      <LookProvider>
        <CoroutineTreeGraph coroutines={[...tree]} reveal={{ id: 'o7', key: 1 }} />
      </LookProvider>,
    )
    expect(zoom.setTransform.mock.calls.length).toBe(calls)
  })

  it('centres it again when the canvas changes size, until the user pans', () => {
    localStorage.setItem(LOOK_STORAGE_KEY, 'classic')
    const { container } = render(
      <LookProvider>
        <CoroutineTreeGraph coroutines={tree} reveal={{ id: 'o7', key: 1 }} />
      </LookProvider>,
    )
    resizeCanvas(1400, 800)
    resizeCanvas(1400, 300)
    const view = lastView()
    const card = container.querySelector<HTMLElement>('[data-testid="graph-node-o7"]')!
    expect(view.y + (parseFloat(card.style.top) + CARDS.classic.height / 2) * view.scale).toBeCloseTo(150, 0)
  })
})


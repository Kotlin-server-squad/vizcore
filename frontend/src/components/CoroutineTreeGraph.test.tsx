import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import type { ReactNode } from 'react'
import { CoroutineTreeGraph } from './CoroutineTreeGraph'
import { findSvgRoot } from '@/lib/export-svg'
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

/** The mocked zoom library: it keeps the view's transform and reports changes like the real one. */
const zoom = vi.hoisted(() => ({
  setTransform: vi.fn(),
  props: null as null | Record<string, unknown>,
  state: { positionX: 0, positionY: 0, scale: 1 },
}))

vi.mock('react-zoom-pan-pinch', async () => {
  const React = await import('react')
  const TransformWrapper = React.forwardRef(function TransformWrapper(
    props: Record<string, unknown>,
    ref: React.ForwardedRef<unknown>,
  ) {
    zoom.props = props
    const api = {
      zoomIn: vi.fn(),
      zoomOut: vi.fn(),
      resetTransform: vi.fn(),
      setTransform: zoom.setTransform,
      get state() {
        return zoom.state
      },
    }
    React.useImperativeHandle(ref, () => api)
    const { children } = props
    return <div>{typeof children === 'function' ? children(api) : (children as ReactNode)}</div>
  })
  return {
    TransformWrapper,
    TransformComponent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  }
})

/** jsdom has no ResizeObserver: this one lets a test say how big the canvas is. */
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
    for (const cb of observers) {
      cb([{ contentRect: { width, height } } as ResizeObserverEntry], {} as ResizeObserver)
    }
  })
}

/** Moves the mocked view and reports it the way the library does. */
function moveView(next: { positionX: number; positionY: number; scale: number }) {
  zoom.state = next
  ;(zoom.props?.onTransform as ((r: unknown, s: unknown) => void) | undefined)?.({ state: next }, next)
}
/** A mouse down on the canvas, at the view as it stands. */
const press = () => act(() => (zoom.props?.onPanningStart as (r: unknown) => void)({ state: zoom.state }))
/** A mouse move during the press that pans the view by [dx], [dy]. */
const drag = (dx: number, dy = 0) =>
  act(() => {
    const next = { ...zoom.state, positionX: zoom.state.positionX + dx, positionY: zoom.state.positionY + dy }
    ;(zoom.props?.onPanning as (r: unknown) => void)({ state: next })
    if (dx !== 0 || dy !== 0) moveView(next)
  })

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

describe('CoroutineTreeGraph', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    zoom.state = { positionX: 0, positionY: 0, scale: 1 }
    zoom.setTransform.mockImplementation((positionX: number, positionY: number, scale: number) =>
      moveView({ positionX, positionY, scale }),
    )
    observers.clear()
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  })

  it('renders empty state when no coroutines', () => {
    render(<CoroutineTreeGraph coroutines={[]} />)

    expect(screen.getByText('No coroutines in this session yet.')).toBeInTheDocument()
  })

  it('renders with coroutine data', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-root', label: 'RootScope', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'c-child', label: 'ChildWorker', parentId: 'c-root', state: CoroutineState.COMPLETED }),
    ]

    render(<CoroutineTreeGraph coroutines={coroutines} />)

    expect(screen.getByText('RootScope')).toBeInTheDocument()
    expect(screen.getByText('ChildWorker')).toBeInTheDocument()
  })

  it('shows active count indicator', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-1', label: 'Worker-1', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'c-2', label: 'Worker-2', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'c-3', label: 'Worker-3', state: CoroutineState.COMPLETED }),
    ]

    render(<CoroutineTreeGraph coroutines={coroutines} />)

    expect(screen.getByText('2 coroutines actively running')).toBeInTheDocument()
  })

  it('renders SVG elements for connections', () => {
    const coroutines: CoroutineNode[] = [
      makeCoroutine({ id: 'c-parent', label: 'Parent', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'c-child-1', label: 'Child-1', parentId: 'c-parent', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'c-child-2', label: 'Child-2', parentId: 'c-parent', state: CoroutineState.COMPLETED }),
    ]

    const { container: _container } = render(<CoroutineTreeGraph coroutines={coroutines} />)

    // The graph renders connection lines between parent and children using div elements
    // with specific CSS classes for the vertical/horizontal connector lines
    expect(screen.getByText('Parent')).toBeInTheDocument()
    expect(screen.getByText('Child-1')).toBeInTheDocument()
    expect(screen.getByText('Child-2')).toBeInTheDocument()
    // Verify the branching structure is present (relationship label showing child count)
    expect(screen.getByText('2 children')).toBeInTheDocument()
  })

  describe('layout', () => {
    const nested: CoroutineNode[] = [
      makeCoroutine({ id: 'req', label: 'request', state: CoroutineState.SUSPENDED }),
      makeCoroutine({ id: 'tasks', label: 'dashboard.tasks', parentId: 'req', state: CoroutineState.COMPLETED }),
      makeCoroutine({ id: 'inbox', label: 'dashboard.notifications', parentId: 'req', state: CoroutineState.SUSPENDED }),
      makeCoroutine({ id: 'unread', label: 'unread', parentId: 'inbox', state: CoroutineState.ACTIVE }),
      makeCoroutine({ id: 'recent', label: 'recent', parentId: 'inbox', state: CoroutineState.COMPLETED }),
    ]

    function position(container: HTMLElement, id: string) {
      const el = container.querySelector<HTMLElement>(`[data-testid="graph-node-${id}"]`)
      if (!el) throw new Error(`no node ${id}`)
      return { left: parseFloat(el.style.left), top: parseFloat(el.style.top) }
    }

    it('draws one connector per parent-child pair in a single SVG layer', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={nested} />)

      const svg = container.querySelector('svg[data-testid="graph-edges"]')
      expect(svg).not.toBeNull()
      const edges = svg!.querySelectorAll('[data-testid="graph-edge"]')
      expect([...edges].map((e) => e.getAttribute('data-child')).sort()).toEqual(['inbox', 'recent', 'tasks', 'unread'])
    })

    it('places children below their parent and keeps siblings with children apart', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={nested} />)

      expect(position(container, 'inbox').top).toBeGreaterThan(position(container, 'req').top)
      expect(position(container, 'unread').top).toBeGreaterThan(position(container, 'inbox').top)
      // the grandchildren sit under their own parent, not under its sibling
      expect(position(container, 'unread').left).toBeGreaterThan(position(container, 'tasks').left)
      expect(position(container, 'recent').left).toBeGreaterThan(position(container, 'unread').left)
    })

    it('labels each parent with its child count', () => {
      render(<CoroutineTreeGraph coroutines={nested} />)

      // the request and dashboard.notifications both have two children
      expect(screen.getAllByText('2 children')).toHaveLength(2)
    })
  })

  describe('export', () => {
    it('is exported as a picture of the page, not as one of its svgs', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={[makeCoroutine({ id: 'c-1', label: 'Worker-1' })]} />)

      expect(findSvgRoot(container)).toBeNull()
    })
  })

  describe('controls', () => {
    const coroutines = [makeCoroutine({ id: 'c-1', label: 'Worker-1' })]

    it('lets zoom be used without unlocking anything first', () => {
      render(<CoroutineTreeGraph coroutines={coroutines} />)

      expect(screen.getByRole('button', { name: 'Zoom in' })).toBeEnabled()
      expect(screen.getByRole('button', { name: 'Zoom out' })).toBeEnabled()
      expect(screen.queryByText('Pan/Zoom Locked')).not.toBeInTheDocument()
    })

    it('offers a fit-to-view button', () => {
      render(<CoroutineTreeGraph coroutines={coroutines} />)

      expect(screen.getByRole('button', { name: 'Fit to view' })).toBeEnabled()
    })

    it('opens full screen and closes it again with Escape', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={coroutines} />)
      const canvas = () => container.querySelector('[data-testid="graph-canvas"]')!

      fireEvent.click(screen.getByRole('button', { name: 'Full screen' }))
      expect(canvas()).toHaveAttribute('data-fullscreen', 'true')

      fireEvent.keyDown(document, { key: 'Escape' })
      expect(canvas()).toHaveAttribute('data-fullscreen', 'false')
    })
  })

  describe('fitting', () => {
    const one = [makeCoroutine({ id: 'c-1', label: 'Worker-1' })]
    const row = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => makeCoroutine({ id, label: id, state: CoroutineState.COMPLETED }))
    const lastScale = () => zoom.setTransform.mock.calls.at(-1)?.[2] as number | undefined

    it('fits the tree to the canvas once the canvas is measured', () => {
      render(<CoroutineTreeGraph coroutines={row} />)
      resizeCanvas(400, 600)

      expect(zoom.setTransform).toHaveBeenCalled()
      expect(lastScale()).toBeLessThan(1)
    })

    it('measures a canvas that appears only after the empty state', () => {
      const { rerender } = render(<CoroutineTreeGraph coroutines={[]} />)
      rerender(<CoroutineTreeGraph coroutines={row} />)
      resizeCanvas(400, 600)

      expect(zoom.setTransform).toHaveBeenCalled()
    })

    it('can fit a very broad tree, however small that makes it', () => {
      const broad = [
        makeCoroutine({ id: 'root', label: 'root' }),
        ...Array.from({ length: 1000 }, (_, i) => makeCoroutine({ id: `k${i}`, label: `k${i}`, parentId: 'root' })),
      ]
      render(<CoroutineTreeGraph coroutines={broad} />)
      resizeCanvas(1200, 800)

      // 1000 cards of 280 px plus 40 px gaps, scaled into 1200 px
      expect(lastScale()).toBeCloseTo(1200 / (1000 * 320 - 40 + 64), 6)
      // and zooming out stops at that whole-tree view
      expect(zoom.props?.minScale).toBeCloseTo(lastScale()!, 6)
    })

    it('keeps fitting after a click, and stops only once the view is actually panned', () => {
      const { rerender } = render(<CoroutineTreeGraph coroutines={one} />)
      resizeCanvas(800, 600)
      // a press, and a mouse move that does not move the view
      press()
      drag(0)

      zoom.setTransform.mockClear()
      rerender(<CoroutineTreeGraph coroutines={row} />)
      expect(zoom.setTransform).toHaveBeenCalled()

      drag(60)
      zoom.setTransform.mockClear()
      rerender(<CoroutineTreeGraph coroutines={[...row, makeCoroutine({ id: 'g', label: 'g' })]} />)
      expect(zoom.setTransform).not.toHaveBeenCalled()

      fireEvent.click(screen.getByRole('button', { name: 'Fit to view' }))
      expect(zoom.setTransform).toHaveBeenCalled()
    })

    it('fits again after leaving full screen with Escape', () => {
      render(<CoroutineTreeGraph coroutines={row} />)
      resizeCanvas(800, 600)
      fireEvent.click(screen.getByRole('button', { name: 'Full screen' }))
      press()
      drag(80, 40)

      zoom.setTransform.mockClear()
      fireEvent.keyDown(document, { key: 'Escape' })
      resizeCanvas(800, 600)
      expect(zoom.setTransform).toHaveBeenCalled()
    })
  })

  describe('zoom buttons', () => {
    const broad = (n: number) => [
      makeCoroutine({ id: 'root', label: 'root' }),
      ...Array.from({ length: n }, (_, i) => makeCoroutine({ id: `k${i}`, label: `k${i}`, parentId: 'root' })),
    ]
    const lastScale = () => zoom.setTransform.mock.calls.at(-1)?.[2] as number

    it('zooms in by one step even from a very small fitted scale', () => {
      render(<CoroutineTreeGraph coroutines={broad(200)} />)
      resizeCanvas(160, 600)
      const fitted = lastScale()

      fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
      expect(lastScale()).toBeCloseTo(fitted * 1.25, 9)
      fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
      expect(lastScale()).toBeCloseTo(fitted * 1.25 * 1.25, 9)
    })

    it('keeps following the tree when zooming out is already at the whole-tree view', () => {
      const { rerender } = render(<CoroutineTreeGraph coroutines={broad(50)} />)
      resizeCanvas(800, 600)

      zoom.setTransform.mockClear()
      fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
      expect(zoom.setTransform).not.toHaveBeenCalled()

      rerender(<CoroutineTreeGraph coroutines={broad(60)} />)
      expect(zoom.setTransform).toHaveBeenCalled()
    })

    it('applies each zoom at once, so quick clicks add up and a later fit is not overridden', () => {
      render(<CoroutineTreeGraph coroutines={broad(5)} />)
      resizeCanvas(800, 600)
      fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))

      // react-zoom-pan-pinch: an animated transform computes from where an earlier one had got to,
      // and a later zero-length transform does not cancel it
      expect(zoom.setTransform.mock.calls.at(-1)?.[3]).toBe(0)
      expect((zoom.props?.velocityAnimation as { disabled?: boolean } | undefined)?.disabled).toBe(true)
      // nor the alignment animation that follows a drag
      expect((zoom.props?.autoAlignment as { disabled?: boolean } | undefined)?.disabled).toBe(true)
    })

    it('lets an ordinary tree be zoomed out to a fifth of full size', () => {
      render(<CoroutineTreeGraph coroutines={[makeCoroutine({ id: 'c-1', label: 'Worker-1' })]} />)
      resizeCanvas(800, 600)

      expect(lastScale()).toBe(1)
      expect(zoom.props?.minScale).toBe(0.2)
    })

    it('stops following once zooming has changed the view', () => {
      const { rerender } = render(<CoroutineTreeGraph coroutines={broad(50)} />)
      resizeCanvas(800, 600)
      fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))

      zoom.setTransform.mockClear()
      rerender(<CoroutineTreeGraph coroutines={broad(60)} />)
      expect(zoom.setTransform).not.toHaveBeenCalled()
    })
  })

  describe('selection while panning', () => {
    it('does not select the card a drag ends on, but selects on the next plain click', () => {
      const onSelect = vi.fn()
      render(<CoroutineTreeGraph coroutines={[makeCoroutine({ id: 'c-1', label: 'Worker-1' })]} onSelect={onSelect} />)
      const card = screen.getByRole('button', { name: 'Open source for Worker-1' })

      press()
      drag(40)
      fireEvent.click(card)
      expect(onSelect).not.toHaveBeenCalled()

      press()
      fireEvent.click(card)
      expect(onSelect).toHaveBeenCalledWith('c-1')
    })
  })

  describe('wheel zoom', () => {
    const broad = (n: number) => [
      makeCoroutine({ id: 'root', label: 'root' }),
      ...Array.from({ length: n }, (_, i) => makeCoroutine({ id: `k${i}`, label: `k${i}`, parentId: 'root' })),
    ]
    const lastScale = () => zoom.setTransform.mock.calls.at(-1)?.[2] as number
    const wheel = (container: HTMLElement, init: ConstructorParameters<typeof WheelEvent>[1]) => {
      const event = new WheelEvent('wheel', { bubbles: true, cancelable: true, clientX: 400, clientY: 300, ...init })
      act(() => {
        container.querySelector('[data-testid="graph-canvas"]')!.dispatchEvent(event)
      })
      return event
    }

    it('zooms by a factor per Ctrl + wheel notch, even from a very small fitted scale', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={broad(1000)} />)
      resizeCanvas(1200, 800)
      const fitted = lastScale()

      const event = wheel(container, { deltaY: -100, ctrlKey: true })
      expect(event.defaultPrevented).toBe(true)
      expect(lastScale() / fitted).toBeGreaterThan(1.1)
      expect(lastScale() / fitted).toBeLessThan(1.5)
    })

    it('zooms as much for a wheel that counts in lines as for one that counts in pixels', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={broad(50)} />)
      resizeCanvas(800, 600)
      const fitted = lastScale()

      // a mouse notch in line mode (Firefox, some mice): three lines
      wheel(container, { deltaY: -3, deltaMode: WheelEvent.DOM_DELTA_LINE, ctrlKey: true })
      expect(lastScale() / fitted).toBeGreaterThan(1.1)
    })

    it('leaves a plain wheel to scroll the page', () => {
      const { container } = render(<CoroutineTreeGraph coroutines={broad(3)} />)
      resizeCanvas(800, 600)
      zoom.setTransform.mockClear()

      const event = wheel(container, { deltaY: 100 })
      expect(event.defaultPrevented).toBe(false)
      expect(zoom.setTransform).not.toHaveBeenCalled()
    })

    it('does not zoom out past the whole-tree view, and keeps following the tree', () => {
      const { container, rerender } = render(<CoroutineTreeGraph coroutines={broad(50)} />)
      resizeCanvas(800, 600)
      zoom.setTransform.mockClear()

      wheel(container, { deltaY: 100, metaKey: true })
      expect(zoom.setTransform).not.toHaveBeenCalled()

      rerender(<CoroutineTreeGraph coroutines={broad(60)} />)
      expect(zoom.setTransform).toHaveBeenCalled()
    })

    it('leaves wheel zoom to the graph, and keeps the library from zooming past its bounds', () => {
      render(<CoroutineTreeGraph coroutines={broad(3)} />)

      expect((zoom.props?.wheel as { disabled?: boolean }).disabled).toBe(true)
      expect(zoom.props?.disablePadding).toBe(true)
    })
  })

  describe('fitting tall trees', () => {
    const lastScale = () => zoom.setTransform.mock.calls.at(-1)?.[2] as number

    it('fits a chain that is taller than the canvas', () => {
      const chain = ['a', 'b', 'c', 'd'].map((id, i, all) =>
        makeCoroutine({ id, label: id, parentId: i > 0 ? all[i - 1]! : null }),
      )
      render(<CoroutineTreeGraph coroutines={chain} />)
      resizeCanvas(800, 600)

      expect(lastScale()).toBeLessThan(1)
    })

    it('does not shrink a long list of roots below half size to fit its height', () => {
      const many = Array.from({ length: 240 }, (_, i) => makeCoroutine({ id: `r${i}`, label: `r${i}`, state: CoroutineState.COMPLETED }))
      render(<CoroutineTreeGraph coroutines={many} />)
      resizeCanvas(800, 600)

      expect(lastScale()).toBeGreaterThanOrEqual(0.5)
    })
  })
})

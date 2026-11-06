import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { StrictMode, useRef, useState } from 'react'
import { LookProvider, forgetVisitLook, useLook, useLookSlot } from '../look-context'
import type { SessionOverview } from '../types'
import { OVERVIEW_HEIGHT_VAR, ROOM_BELOW_PX, RightNow } from './RightNow'
import { ROW_CAP } from './overview'
import type { StateCounts } from '@/lib/state-counts'
import { CoroutineState, type CoroutineNode } from '@/types/api'

const counts = (over: Partial<StateCounts> = {}): StateCounts => ({
  active: 0,
  waiting: 0,
  suspended: 0,
  completed: 0,
  cancelled: 0,
  failed: 0,
  total: 0,
  leaks: 0,
  ...over,
})
const node = (id: string, label: string): CoroutineNode => ({
  id,
  jobId: `job-${id}`,
  parentId: null,
  scopeId: 'scope-1',
  label,
  state: CoroutineState.SUSPENDED,
})

/** A busy moment: two holders, a waiter, and a bar. */
const busy: SessionOverview = {
  counts: counts({ active: 2, suspended: 1, total: 3 }),
  coroutines: [node('c-4', 'order-4'), node('c-5', 'order-5'), node('c-1', 'order-1')],
  activity: new Map([
    ['c-5', { text: 'waits for inventory-lock · 1st', waiting: true }],
    ['c-4', { text: 'holds inventory-lock', waiting: false }],
    ['c-1', { text: 'holds a payment-gateway permit', waiting: false }],
  ]),
}
const nothing: SessionOverview = { counts: counts(), coroutines: [], activity: new Map() }

/** jsdom has no ResizeObserver: a fake one that records what it watches and can be fired. */
class FakeResizeObserver {
  static all: FakeResizeObserver[] = []
  targets = new Set<Element>()
  disconnected = false
  constructor(private readonly callback: ConstructorParameters<typeof ResizeObserver>[0]) {
    FakeResizeObserver.all.push(this)
  }
  observe(el: Element) {
    this.targets.add(el)
  }
  unobserve(el: Element) {
    this.targets.delete(el)
  }
  disconnect() {
    this.disconnected = true
    this.targets.clear()
  }
  /** Every live observer reports a resize, as the browser would after a layout change. */
  static fire() {
    for (const o of FakeResizeObserver.all) {
      if (!o.disconnected && o.targets.size > 0) o.callback([], o as unknown as ResizeObserver)
    }
  }
  static live() {
    return FakeResizeObserver.all.filter(o => !o.disconnected)
  }
}

/** jsdom lays nothing out: give the body and the overview heights. */
let bodyHeight = 800
let overviewHeight = 96
beforeEach(() => {
  FakeResizeObserver.all = []
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  bodyHeight = 800
  overviewHeight = 96
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    const height = this.dataset.c === 'overview' ? overviewHeight : this.dataset.lookPart === 'body' ? bodyHeight : 0
    return { height, width: 0, top: 0, left: 0, right: 0, bottom: height, x: 0, y: 0, toJSON: () => ({}) } as DOMRect
  })
  localStorage.clear()
  forgetVisitLook()
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function Body({ overview }: { overview: SessionOverview }) {
  return (
    <div data-look-part="body">
      <p>main</p>
      <p>dock</p>
      <RightNow overview={overview} />
    </div>
  )
}
const body = (container: HTMLElement) => container.querySelector<HTMLElement>('[data-look-part="body"]')!

describe("C's Right now overview", () => {
  it('says, by name, what holds and what waits, holders first, and draws the state bar', () => {
    const { container } = render(<Body overview={busy} />)
    const region = screen.getByRole('region', { name: 'Right now' })
    expect(region).toHaveTextContent(
      'Right now' +
        'order-4 holds inventory-lock.' +
        'order-1 holds a payment-gateway permit.' +
        'order-5 waits for inventory-lock · 1st.'
    )
    expect(screen.getByRole('img', { name: 'Coroutine states: 2 active, 1 suspended' })).toBeInTheDocument()
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('96px')
  })

  it(`shows ${ROW_CAP} lines and counts the rest`, () => {
    const many: SessionOverview = {
      counts: counts({ suspended: 9, total: 9 }),
      coroutines: Array.from({ length: 9 }, (_, i) => node(`c-${i}`, `job-${i}`)),
      activity: new Map(Array.from({ length: 9 }, (_, i) => [`c-${i}`, { text: 'waits for a lock', waiting: true }])),
    }
    render(<Body overview={many} />)
    expect(screen.getAllByText(/waits for a lock/)).toHaveLength(ROW_CAP)
    expect(screen.getByText('And 3 more.')).toBeInTheDocument()
  })

  it('draws only the bar when nothing holds or waits, and nothing at all for an empty session', () => {
    const quiet: SessionOverview = { ...busy, activity: new Map() }
    const { container, rerender } = render(<Body overview={quiet} />)
    expect(screen.queryByRole('heading', { name: 'Right now' })).toBeNull()
    expect(screen.getByRole('img')).toBeInTheDocument()
    rerender(<Body overview={nothing} />)
    expect(screen.queryByRole('region')).toBeNull()
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('')
  })

  it('takes its padding away when it empties, and gives it back when it fills again', () => {
    const { container, rerender } = render(<Body overview={busy} />)
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('96px')
    rerender(<Body overview={nothing} />)
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('')
    overviewHeight = 40
    rerender(<Body overview={busy} />)
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('40px')
  })

  it('steps aside when the body has no room for it above the main view and the smallest dock', () => {
    bodyHeight = ROOM_BELOW_PX + 95
    const { container } = render(<Body overview={busy} />)
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('0px')
    expect(screen.getByRole('region', { hidden: true })).toHaveAttribute('data-no-room')
  })

  it('follows its own size and the body’s as they change, watching both', () => {
    const { container } = render(<Body overview={busy} />)
    const [observer] = FakeResizeObserver.live()
    expect([...observer!.targets]).toEqual([screen.getByRole('region'), body(container)])
    // The callout wraps onto another line.
    overviewHeight = 140
    act(() => FakeResizeObserver.fire())
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('140px')
    // The window gets short: it steps aside…
    bodyHeight = ROOM_BELOW_PX + 100
    act(() => FakeResizeObserver.fire())
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('0px')
    expect(screen.getByRole('region', { hidden: true })).toHaveAttribute('data-no-room')
    // …and comes back when it grows again.
    bodyHeight = 800
    act(() => FakeResizeObserver.fire())
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('140px')
    expect(screen.getByRole('region')).not.toHaveAttribute('data-no-room')
  })

  it('stops watching when it empties or unmounts', () => {
    const { rerender, unmount } = render(<Body overview={busy} />)
    expect(FakeResizeObserver.live()).toHaveLength(1)
    rerender(<Body overview={nothing} />)
    expect(FakeResizeObserver.live()).toHaveLength(0)
    rerender(<Body overview={busy} />)
    expect(FakeResizeObserver.live()).toHaveLength(1)
    unmount()
    expect(FakeResizeObserver.live()).toHaveLength(0)
  })

  it('fits exactly when the body keeps the minimums under it', () => {
    bodyHeight = ROOM_BELOW_PX + 96
    const { container } = render(<Body overview={busy} />)
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('96px')
    expect(screen.getByRole('region')).not.toHaveAttribute('data-no-room')
  })
})

/** Counts its mounts and keeps state, like the graph's pan and zoom. */
const mounts = { main: 0, dock: 0 }
function Stateful({ name }: { name: 'main' | 'dock' }) {
  const [clicks, setClicks] = useState(0)
  const counted = useRef(false)
  if (!counted.current) {
    counted.current = true
    mounts[name] += 1
  }
  return (
    <button type="button" onClick={() => setClicks(c => c + 1)}>
      {name} {clicks}
    </button>
  )
}
let setLook: ReturnType<typeof useLook>['setLook'] = null
function Page() {
  const look = useLook()
  setLook = look.setLook
  const sessionBody = useLookSlot('SessionBody')
  const ref = useRef<HTMLDivElement>(null)
  return sessionBody({ sharedRef: ref, main: <Stateful name="main" />, dock: <Stateful name="dock" />, overview: busy })
}

describe('switching to C and back', () => {
  it('keeps the main view and dock mounted, and leaves Classic’s body without C’s padding', () => {
    mounts.main = 0
    mounts.dock = 0
    const { container } = render(
      <StrictMode>
        <LookProvider>
          <Page />
        </LookProvider>
      </StrictMode>
    )
    act(() => setLook!('c'))
    expect(screen.getByRole('region', { name: 'Right now' })).toBeInTheDocument()
    expect(body(container).style.getPropertyValue(OVERVIEW_HEIGHT_VAR)).toBe('96px')
    act(() => setLook!('classic'))
    expect(screen.queryByRole('region')).toBeNull()
    expect(body(container).getAttribute('style') ?? '').toBe('')
    expect(FakeResizeObserver.live()).toHaveLength(0)
    act(() => setLook!('c'))
    expect(FakeResizeObserver.live()).toHaveLength(1)
    act(() => setLook!('classic'))
    expect(FakeResizeObserver.live()).toHaveLength(0)
    // A late resize after leaving C cannot write the variable back.
    act(() => FakeResizeObserver.fire())
    expect(body(container).getAttribute('style') ?? '').toBe('')
    expect(screen.getByRole('button', { name: 'main 0' })).toBeInTheDocument()
    // StrictMode mounts each twice on the first render only; never again on a switch.
    expect(mounts).toEqual({ main: 1, dock: 1 })
  })
})

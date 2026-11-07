import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TimelineView } from './TimelineView'
import { buildTimeline } from '@/lib/timeline/build-timeline'
import { Run } from '@/lib/timeline/test-events'
import type { CoroutineNode } from '@/types/api'

const S = 1_000_000_000
const node = (id: string, state: string): CoroutineNode =>
  ({ id, jobId: `job-${id}`, parentId: null, scopeId: 'io', label: id, state }) as CoroutineNode

/** order-1 holds the lock while suspended; order-2 waits for it, then completes. */
function run() {
  const r = new Run()
  r.created('order-1', 0) // 0
  r.created('order-2', 0) // 1
  r.life('started', 'order-1', {}, 1 * S) // 2
  r.lock('m', 'order-1', 1 * S) // 3
  r.life('suspended', 'order-1', {}, 2 * S) // 4
  r.life('started', 'order-2', {}, 2 * S) // 5
  r.life('suspended', 'order-2', { waitKind: 'MUTEX', resourceId: 'm' }, 3 * S) // 6
  r.life('resumed', 'order-1', {}, 5 * S) // 7
  r.unlock('m', 'order-1', 6 * S) // 8
  r.lock('m', 'order-2', 6 * S) // 9
  r.life('resumed', 'order-2', {}, 6 * S) // 10
  r.life('completed', 'order-2', {}, 8 * S) // 11
  r.semCreated('s', 2) // 12
  return r
}

function renderView(props: Partial<Parameters<typeof TimelineView>[0]> = {}) {
  const onSelect = vi.fn()
  const timeline = buildTimeline(run().events)
  render(
    <TimelineView
      timeline={timeline}
      rows={[node('order-1', 'ACTIVE'), node('order-2', 'COMPLETED')]}
      cursor={null}
      note={null}
      selectedId={null}
      onSelect={onSelect}
      {...props}
    />,
  )
  return { onSelect }
}

describe('TimelineView', () => {
  it('draws one row per coroutine, in the given order, each with its state in words', () => {
    renderView()
    const rows = screen.getAllByRole('button', { name: /^order-/ })
    expect(rows.map(r => r.getAttribute('aria-label'))).toEqual(['order-1, Active', 'order-2, Completed'])
    expect(screen.getByTestId('timeline-label-order-1')).toHaveTextContent('Active')
  })

  it('draws each state as a bar with its state, a hold band above it and a mark at a finished row’s end', () => {
    renderView()
    const row = screen.getByRole('button', { name: 'order-1, Active' })
    const bars = row.querySelectorAll('[data-look-part="timeline-bar"]')
    expect([...bars].map(b => b.getAttribute('data-state'))).toEqual(['CREATED', 'ACTIVE', 'SUSPENDED', 'ACTIVE'])
    // The lock is held while order-1 is suspended: the band spans the suspension.
    const hold = row.querySelector('[data-look-part="timeline-hold"]')!
    expect(hold.getAttribute('title')).toMatch(/holds m/)
    const done = screen.getByRole('button', { name: 'order-2, Completed' })
    const end = done.querySelector('[data-look-part="timeline-end"]')!
    expect(end).toHaveAttribute('data-state', 'COMPLETED')
    expect(end.getAttribute('title')).toBe('Completed at 8.0 s')
    expect(row.querySelector('[data-look-part="timeline-end"]')).toBeNull()
  })

  it('selects a coroutine when its bar is clicked', async () => {
    const { onSelect } = renderView()
    await userEvent.click(screen.getByRole('button', { name: 'order-2, Completed' }))
    expect(onSelect).toHaveBeenCalledWith('order-2')
  })

  it('is one tab stop: ↑ ↓ Home End move between rows, Enter selects', async () => {
    const { onSelect } = renderView()
    const [first, second] = screen.getAllByRole('button', { name: /^order-/ })
    expect(first).toHaveAttribute('tabindex', '0')
    expect(second).toHaveAttribute('tabindex', '-1')
    first!.focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(second).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(first).toHaveFocus()
    await userEvent.keyboard('{End}{Enter}')
    expect(onSelect).toHaveBeenCalledWith('order-2')
  })

  it('marks the selected row', () => {
    renderView({ selectedId: 'order-2' })
    expect(screen.getByRole('button', { name: 'order-2, Completed' })).toHaveAttribute('aria-current', 'true')
  })

  it('draws lock and permit lanes under the rows, the lock’s bands named by holder', () => {
    renderView()
    const lanes = screen.getByRole('group', { name: 'Locks and permits' })
    const lock = within(lanes).getByTestId('timeline-lane-m-0')
    expect([...lock.querySelectorAll('[data-look-part="timeline-lane-band"]')].map(b => b.textContent)).toEqual(['order-1', 'order-2'])
    expect(screen.getByText('s permits')).toBeInTheDocument()
  })

  it('names the colours in a legend', () => {
    renderView()
    const legend = screen.getByTestId('timeline-legend')
    for (const text of ['Active', 'Suspended', 'Waiting for children', 'Holds', 'May hold']) {
      expect(within(legend).getByText(text)).toBeInTheDocument()
    }
  })

  it('shows the note when intervals may be missing', () => {
    renderView({ note: 'Some intervals may be missing: the events start at event 40.' })
    expect(screen.getByText('Some intervals may be missing: the events start at event 40.')).toHaveAttribute(
      'data-look-part',
      'trimmed-note',
    )
  })

  it('in replay draws the cursor and leaves what comes after it hollow', () => {
    renderView({ cursor: 6 })
    expect(screen.getByTestId('timeline-cursor')).toHaveStyle({ left: '37.5%' })
    expect(screen.getByTestId('timeline-future')).toBeInTheDocument()
    const done = screen.getByRole('button', { name: 'order-2, Completed' })
    // order-2's end (event 11) is after the cursor (event 6): hollow, and its Suspended bar is split at the cursor.
    expect(done.querySelector('[data-look-part="timeline-end"]')).toHaveAttribute('data-after-cursor', 'true')
    const suspended = [...done.querySelectorAll('[data-look-part="timeline-bar"][data-state="SUSPENDED"]')]
    expect(suspended.map(b => b.getAttribute('data-after-cursor'))).toEqual([null, 'true'])
  })

  it('says a row created before the events has no drawn states', () => {
    const r = run()
    r.life('resumed', 'old')
    render(
      <TimelineView
        timeline={buildTimeline(r.events)}
        rows={[node('old', 'UNKNOWN')]}
        cursor={null}
        note={null}
        selectedId={null}
      />,
    )
    expect(screen.getByRole('button', { name: 'old, State unknown' })).toHaveTextContent('created before these events')
  })

  it('says a lock is still held after its holder ended only once the cursor has passed the end', () => {
    const r = new Run()
    r.created('a', 0) // 0
    r.lock('m', 'a', 2 * S) // 1
    r.life('completed', 'a', {}, 3 * S) // 2
    r.created('b', 4 * S) // 3
    const props = { timeline: buildTimeline(r.events), rows: [node('a', 'ACTIVE')], note: null, selectedId: null }
    const { unmount } = render(<TimelineView {...props} cursor={1} />)
    const filled = screen.getByRole('button', { name: 'a, Active' }).querySelector('[data-look-part="timeline-hold"]:not([data-after-cursor])')!
    expect(filled.getAttribute('title')).not.toMatch(/after it/)
    unmount()
    render(<TimelineView {...props} cursor={null} />)
    const live = screen.getByRole('button', { name: 'a, Active' }).querySelector('[data-look-part="timeline-hold"]')!
    expect(live.getAttribute('title')).toMatch(/still held after it completed/)
  })

  it('describes each row’s intervals for keyboard and screen-reader users', () => {
    renderView()
    const row = screen.getByRole('button', { name: 'order-2, Completed' })
    const description = document.getElementById(row.getAttribute('aria-describedby')!)!
    expect(description).toHaveTextContent('Suspended, waits for m, 3.0–6.0 s')
    expect(description).toHaveTextContent('holds m 6.0–8.0 s')
    expect(description).toHaveTextContent('Completed at 8.0 s')
  })

  it('describes each lane in words', () => {
    renderView()
    expect(screen.getByRole('img', { name: /^m: order-1 holds it 1\.0–6\.0 s; order-2 holds it 6\.0–/ })).toBeInTheDocument()
  })

  it('draws a long run with many permit holders without one element per operation', () => {
    const r = new Run()
    r.semCreated('s', 200)
    for (let i = 0; r.events.length < 2_000; i++) {
      r.acquire('s', `c-${i % 150}`, 0)
      const holders: Record<string, number> = {}
      for (let h = 0; h <= Math.min(i, 149); h++) holders[`c-${h}`] = 1
      r.snapshot('s', 200, 50, holders)
    }
    const rows = Array.from({ length: 150 }, (_, i) => node(`c-${i}`, 'ACTIVE'))
    const started = performance.now()
    const { container } = render(
      <TimelineView timeline={buildTimeline(r.events)} rows={rows} cursor={null} note={null} selectedId={null} />,
    )
    expect(performance.now() - started).toBeLessThan(5000)
    expect(container.querySelectorAll('[data-uncertain="true"]').length).toBeLessThan(400)
  })

  it('draws many permits given back by unknown holders as a bounded number of marks', () => {
    const r = new Run()
    r.semCreated('s', 200)
    const holders: Record<string, number> = {}
    for (let h = 0; h < 149; h++) holders[`c-${h}`] = 1
    r.snapshot('s', 200, 51, holders)
    for (let i = 0; r.events.length < 3_000; i++) {
      r.acquire('s', 'c-149', 50)
      r.release('s', 'x', 51, true)
    }
    const rows = Array.from({ length: 150 }, (_, i) => node(`c-${i}`, 'ACTIVE'))
    const { container } = render(
      <TimelineView timeline={buildTimeline(r.events)} rows={rows} cursor={null} note={null} selectedId={null} />,
    )
    expect(container.querySelectorAll('[data-look-part="timeline-unknown"]').length).toBeLessThan(8_000)
  })
})


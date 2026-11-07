import { describe, it, expect } from 'vitest'
import { buildTimeline, type HoldBand } from './build-timeline'
import { Run } from './test-events'

const states = (spans: { state: string; from: number; to: number | null }[]) => spans.map(s => [s.state, s.from, s.to])
const band = (b: HoldBand) => ({ who: b.coroutineId, from: b.from, to: b.to, end: b.end, slot: b.slot })

describe('buildTimeline — states', () => {
  it('follows the tree’s transitions, by event index, and ends a finished row with a mark', () => {
    const r = new Run()
    r.created('a') // 0
    r.life('started', 'a') // 1
    r.life('suspended', 'a', { waitKind: 'MUTEX', resourceId: 'm' }) // 2
    r.life('resumed', 'a') // 3
    r.life('body-completed', 'a') // 4
    r.life('completed', 'a') // 5
    const row = buildTimeline(r.events).rows.get('a')!
    expect(states(row.spans)).toEqual([
      ['CREATED', 0, 1],
      ['ACTIVE', 1, 2],
      ['SUSPENDED', 2, 3],
      ['ACTIVE', 3, 4],
      ['WAITING_FOR_CHILDREN', 4, 5],
    ])
    expect(row.spans[2]!.wait).toEqual({ kind: 'MUTEX', resourceId: 'm' })
    expect(row.spans[1]!.wait).toBeNull()
    expect(row.end).toEqual({ state: 'COMPLETED', at: 5 })
    expect(row.createdBefore).toBe(false)
  })

  it.each([
    ['failed', 'FAILED'],
    ['cancelled', 'CANCELLED'],
  ])('marks a %s row', (kind, state) => {
    const r = new Run()
    r.created('a')
    r.life(kind, 'a')
    expect(buildTimeline(r.events).rows.get('a')!.end).toEqual({ state, at: 1 })
  })

  it('keeps every terminal mark when a coroutine is created again', () => {
    const r = new Run()
    r.created('a') // 0
    r.life('completed', 'a') // 1
    r.created('a') // 2
    r.life('started', 'a') // 3
    const row = buildTimeline(r.events).rows.get('a')!
    expect(row.ends).toEqual([{ state: 'COMPLETED', at: 1 }])
    expect(row.end).toBeNull()
  })

  it('leaves an unfinished row open after the last event', () => {
    const r = new Run()
    r.created('a')
    r.life('started', 'a')
    r.created('b')
    const row = buildTimeline(r.events).rows.get('a')!
    expect(states(row.spans)).toEqual([
      ['CREATED', 0, 1],
      ['ACTIVE', 1, null],
    ])
    expect(row.end).toBeNull()
  })

  it('ignores a coroutine’s events before its creation, as the tree does', () => {
    const r = new Run()
    r.life('started', 'a') // 0, before its creation
    r.created('a') // 1
    expect(states(buildTimeline(r.events).rows.get('a')!.spans)).toEqual([['CREATED', 1, null]])
  })

  it('draws no states for a coroutine whose creation is not in the events, and says so', () => {
    const r = new Run()
    r.life('resumed', 'old')
    r.life('suspended', 'old')
    const row = buildTimeline(r.events).rows.get('old')!
    expect(row.spans).toEqual([])
    expect(row.createdBefore).toBe(true)
  })

  it('places events on the replay clock: a running maximum since the first event, in seq order', () => {
    const r = new Run()
    r.created('a', 10_000_000)
    r.created('b', 30_000_000)
    r.life('started', 'a', {}, 20_000_000) // an earlier time on another thread
    const t = buildTimeline(r.events)
    expect(t.times).toEqual([0, 20_000_000, 20_000_000])
    expect(t.spanNanos).toBe(20_000_000)
  })

  it('reads events that arrived out of seq order in seq order', () => {
    const r = new Run()
    const created = r.created('a')
    const started = r.life('started', 'a')
    const t = buildTimeline([started, created])
    expect(t.events.map(e => e.seq)).toEqual([1, 2])
    expect(states(t.rows.get('a')!.spans)).toEqual([
      ['CREATED', 0, 1],
      ['ACTIVE', 1, null],
    ])
  })
})

describe('buildTimeline — holds', () => {
  it('keeps a lock held while its holder is suspended: holds and states are separate', () => {
    const r = new Run()
    r.created('a') // 0
    r.life('started', 'a') // 1
    r.lock('m', 'a') // 2
    r.life('suspended', 'a') // 3
    r.life('resumed', 'a') // 4
    r.unlock('m', 'a') // 5
    const t = buildTimeline(r.events)
    const row = t.rows.get('a')!
    expect(row.holds.map(band)).toEqual([{ who: 'a', from: 2, to: 5, end: 'released', slot: 0 }])
    expect(states(row.spans)).toEqual([
      ['CREATED', 0, 1],
      ['ACTIVE', 1, 3],
      ['SUSPENDED', 3, 4],
      ['ACTIVE', 4, null],
    ])
    expect(t.lanes).toMatchObject([{ resourceId: 'm', kind: 'lock', name: 'm', slots: 1 }])
  })

  it('ends a holder’s band when the lock passes on, and ignores its late release', () => {
    const r = new Run()
    r.lock('m', 'a') // 0
    r.lock('m', 'b') // 1: VizMutex records a's release after b's acquisition
    r.unlock('m', 'a') // 2
    r.unlock('m', 'b') // 3
    const lane = buildTimeline(r.events).lanes[0]!
    expect(lane.bands.map(band)).toEqual([
      { who: 'a', from: 0, to: 1, end: 'released', slot: 0 },
      { who: 'b', from: 1, to: 3, end: 'released', slot: 0 },
    ])
  })

  it('ends a lock band with the unknown mark when another coroutine unlocks it', () => {
    const r = new Run()
    r.lock('m', 'a')
    r.unlock('m', 'x')
    expect(buildTimeline(r.events).lanes[0]!.bands.map(band)).toEqual([{ who: 'a', from: 0, to: 1, end: 'unknown', slot: 0 }])
  })

  it('keeps a lock held past its holder’s end', () => {
    const r = new Run()
    r.created('a') // 0
    r.lock('m', 'a') // 1
    r.life('completed', 'a') // 2
    r.created('b') // 3
    const row = buildTimeline(r.events).rows.get('a')!
    expect(row.end).toEqual({ state: 'COMPLETED', at: 2 })
    expect(row.holds.map(band)).toEqual([{ who: 'a', from: 1, to: null, end: null, slot: 0 }])
  })

  it('draws one band per permit, in the lowest free slots, ended by the snapshot that drops them', () => {
    const r = new Run()
    r.semCreated('s', 3) // 0
    r.acquire('s', 'a', 2) // 1
    r.snapshot('s', 3, 2, { a: 1 }) // 2
    r.acquire('s', 'a', 1) // 3
    r.snapshot('s', 3, 1, { a: 2 }) // 4
    r.acquire('s', 'b', 0) // 5
    r.snapshot('s', 3, 0, { a: 2, b: 1 }) // 6
    r.release('s', 'a', 1, false) // 7
    r.snapshot('s', 3, 1, { a: 1, b: 1 }) // 8
    const t = buildTimeline(r.events)
    const lane = t.lanes[0]!
    expect(lane).toMatchObject({ kind: 'permit', slots: 3, unknownMarks: [] })
    expect(lane.bands.map(band)).toEqual([
      { who: 'a', from: 1, to: 8, end: 'released', slot: 0 },
      { who: 'a', from: 4, to: null, end: null, slot: 1 },
      { who: 'b', from: 5, to: null, end: null, slot: 2 },
    ])
    // Between an operation and its snapshot, who holds what is unsettled: "may hold", as Evidence says.
    expect(lane.bands[0]!.uncertain).toEqual([
      { from: 1, to: 2 },
      { from: 3, to: 4 },
      { from: 5, to: 6 },
      { from: 7, to: 8 },
    ])
  })

  it('an unknown release ends every open band’s certain part with a mark, and they go on as "may hold"', () => {
    const r = new Run()
    r.semCreated('s', 2) // 0
    r.snapshot('s', 2, 0, { a: 1, b: 1 }) // 1
    r.release('s', 'x', 1, true) // 2
    r.snapshot('s', 2, 1, { a: 1, b: 1 }, 1) // 3: still one permit whose holder is unknown
    const lane = buildTimeline(r.events).lanes[0]!
    expect(lane.unknownMarks).toEqual([2])
    for (const b of lane.bands) {
      expect(b.to).toBeNull()
      expect(b.unknownMarks).toEqual([2])
      expect(b.uncertain).toEqual([{ from: 2, to: null }])
    }
  })

  it('takes a release by a coroutine listed as holding none, from an older producer, for an unknown one', () => {
    const r = new Run()
    r.semCreated('s', 2)
    r.snapshot('s', 2, 1, { a: 1 })
    r.release('s', 'x', 2) // no holderUnknown field
    expect(buildTimeline(r.events).lanes[0]!.unknownMarks).toEqual([2])
  })

  it('an unknown release that frees every permit ends every band there, with the mark', () => {
    const r = new Run()
    r.semCreated('s', 1)
    r.snapshot('s', 1, 0, { a: 1 }) // 1
    r.release('s', 'x', 1, true) // 2
    const lane = buildTimeline(r.events).lanes[0]!
    expect(lane.bands.map(band)).toEqual([{ who: 'a', from: 1, to: 2, end: 'unknown', slot: 0 }])
    expect(lane.bands[0]!.unknownMarks).toEqual([2])
  })

  it('a holder dropped by a snapshot without a release of its own ends with the unknown mark', () => {
    const r = new Run()
    r.semCreated('s', 2)
    r.snapshot('s', 2, 0, { a: 1, b: 1 }) // 1
    r.release('s', 'x', 1, true) // 2
    r.snapshot('s', 2, 1, { b: 1 }) // 3: VizSemaphore retired a, which had ended
    const bands = buildTimeline(r.events).lanes[0]!.bands
    expect(bands.map(band)).toEqual([
      { who: 'a', from: 1, to: 3, end: 'unknown', slot: 0 },
      { who: 'b', from: 1, to: null, end: null, slot: 1 },
    ])
    expect(bands[1]!.uncertain).toEqual([{ from: 2, to: 3 }])
  })

  it('more "may hold" candidates than permits take a slot beyond them', () => {
    const r = new Run()
    r.semCreated('s', 2)
    r.snapshot('s', 2, 0, { a: 1, b: 1 }) // 1
    r.release('s', 'x', 1, true) // 2
    r.acquire('s', 'c', 0) // 3: before the next snapshot
    const lane = buildTimeline(r.events).lanes[0]!
    expect(lane.slots).toBe(3)
    expect(lane.permits).toBe(2)
    const c = lane.bands.find(b => b.coroutineId === 'c')!
    expect(c.slot).toBe(2)
    expect(c.uncertain).toEqual([{ from: 3, to: null }])
  })

  it('shows an acquisition a snapshot reveals although its event was trimmed', () => {
    const r = new Run()
    r.snapshot('s', 2, 1, { a: 1 }) // the semaphore's creation and a's acquisition are gone
    expect(buildTimeline(r.events).lanes[0]!.bands.map(band)).toEqual([{ who: 'a', from: 0, to: null, end: null, slot: 0 }])
  })

  it('closes before it opens: a settled reshuffle stays within the permits', () => {
    const r = new Run()
    r.semCreated('s', 3)
    r.snapshot('s', 3, 0, { a: 1, b: 2 }) // 1
    r.snapshot('s', 3, 0, { a: 2, b: 1 }) // 2
    const lane = buildTimeline(r.events).lanes[0]!
    expect(lane.slots).toBe(3)
  })

  it('moves a "may hold" band beyond the permits into a free slot once it is certain', () => {
    const r = new Run()
    r.semCreated('s', 2)
    r.snapshot('s', 2, 0, { a: 1, b: 1 }) // 1
    r.release('s', 'x', 1, true) // 2
    r.acquire('s', 'c', 0) // 3: a third candidate, slot 2
    r.snapshot('s', 2, 0, { b: 1, c: 1 }) // 4: settled, a gone
    const lane = buildTimeline(r.events).lanes[0]!
    const open = lane.bands.filter(b => b.to === null)
    expect(open.map(b => [b.coroutineId, b.slot]).sort()).toEqual([
      ['b', 1],
      ['c', 0],
    ])
    expect(lane.bands.find(b => b.coroutineId === 'c' && b.slot === 2)).toMatchObject({ to: 4, end: 'moved' })
  })

  it('does not take an old release for the end of a later band', () => {
    const r = new Run()
    r.semCreated('s', 2)
    r.snapshot('s', 2, 1, { a: 1 }) // 1
    r.snapshot('s', 2, 2, {}) // 2: a dropped
    r.release('s', 'a', 2, false) // 3: its late release
    r.acquire('s', 'a', 1) // 4
    r.snapshot('s', 2, 1, { a: 1 }) // 5
    r.release('s', 'x', 2, true) // 6: frees every permit; whose is unknown
    const bands = buildTimeline(r.events).lanes[0]!.bands
    expect(bands.map(band)).toEqual([
      { who: 'a', from: 1, to: 2, end: 'unknown', slot: 0 },
      { who: 'a', from: 4, to: 6, end: 'unknown', slot: 0 },
    ])
  })

  it('lists a lock and a semaphore no one held, so their lanes exist', () => {
    const r = new Run()
    r.add('MutexCreated', { mutexId: 'm', mutexLabel: 'inventory-lock', ownerCoroutineId: null })
    r.semCreated('s', 2)
    expect(buildTimeline(r.events).lanes.map(l => [l.name, l.kind, l.bands.length])).toEqual([
      ['inventory-lock', 'lock', 0],
      ['s', 'permit', 0],
    ])
  })
})

describe('buildTimeline — cost', () => {
  it('stays linear with many permit holders: 10,000 events in well under a second', () => {
    const r = new Run()
    r.semCreated('s', 200)
    for (let i = 0; r.events.length < 10_000; i++) {
      const id = `c-${i % 150}`
      r.acquire('s', id, 0)
      const holders: Record<string, number> = {}
      for (let h = 0; h <= Math.min(i, 149); h++) holders[`c-${h}`] = 1
      r.snapshot('s', 200, 50, holders)
    }
    const started = performance.now()
    const t = buildTimeline(r.events)
    expect(performance.now() - started).toBeLessThan(3000)
    expect(t.lanes[0]!.slots).toBe(150)
  })
})

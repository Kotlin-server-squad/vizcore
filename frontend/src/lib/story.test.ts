import { describe, it, expect } from 'vitest'
import type { VizEvent } from '@/types/api'
import { momentFacts, resolveAnchor, runEnded, storyHeld, type HeldEvents } from './story'
import { LIVE_EVENTS_MAX } from './live-events'
import type { StoryAnchor } from '@/content/gallery/story-anchors'

let seq = 0
const ev = (kind: string, id: string, ms: number, extra: Record<string, unknown> = {}) =>
  ({ seq: ++seq, tsNanos: ms * 1e6, sessionId: 's', kind, coroutineId: id, jobId: `j-${id}`, parentCoroutineId: null, scopeId: 'sc', label: id, ...extra }) as unknown as VizEvent
const created = (id: string, ms = 0, parent: string | null = null) =>
  ev('coroutine.created', id, ms, { label: id, parentCoroutineId: parent })

const held = (events: VizEvent[], over: Partial<HeldEvents> = {}): HeldEvents => ({
  events,
  streaming: true,
  shedCount: 0,
  trimmed: false,
  sessionLabels: new Set(),
  ...over,
})

const anchor = (event: StoryAnchor['event'], coroutine: string, occurrence = 1): StoryAnchor => ({ event, coroutine, occurrence })

describe('resolveAnchor (plan P-7)', () => {
  it('finds the nth event of that kind for the labelled coroutine', () => {
    const events = [
      created('a'),
      ev('coroutine.started', 'a', 0),
      ev('coroutine.suspended', 'a', 10),
      ev('coroutine.resumed', 'a', 20),
      ev('coroutine.suspended', 'a', 30),
    ]
    expect(resolveAnchor(anchor('CoroutineSuspended', 'a', 2), held(events))).toEqual({
      kind: 'ready',
      seq: events[4]!.seq,
      coroutineId: 'a',
    })
  })

  it('waits for a moment that has not come yet while the stream is on', () => {
    const events = [created('a'), ev('coroutine.started', 'a', 0)]
    expect(resolveAnchor(anchor('CoroutineCompleted', 'a'), held(events)).kind).toBe('waiting')
    expect(resolveAnchor(anchor('CoroutineStarted', 'b'), held(events)).kind).toBe('waiting')
  })

  it('says the page holds no events that far while the view is paused', () => {
    const events = [created('a'), ev('coroutine.started', 'a', 0)]
    expect(resolveAnchor(anchor('CoroutineCompleted', 'a'), held(events, { streaming: false })).kind).toBe('not-held')
  })

  it('says the moment did not happen once the held events contain the end of the run', () => {
    const events = [created('a'), ev('coroutine.started', 'a', 0), ev('coroutine.completed', 'a', 5)]
    expect(resolveAnchor(anchor('CoroutineFailed', 'a'), held(events)).kind).toBe('missed')
    expect(resolveAnchor(anchor('CoroutineStarted', 'never'), held(events)).kind).toBe('missed')
  })

  it('says it was trimmed when the coroutine is in the session but its creation is not held', () => {
    const events = [ev('coroutine.suspended', 'a', 10)]
    expect(
      resolveAnchor(anchor('CoroutineSuspended', 'a'), held(events, { trimmed: true, sessionLabels: new Set(['a']) })).kind,
    ).toBe('trimmed')
  })

  it('counts a suspension only when the stream shed nothing (suspensions can be shed under load)', () => {
    const events = [created('a'), ev('coroutine.started', 'a', 0), ev('coroutine.suspended', 'a', 10)]
    expect(resolveAnchor(anchor('CoroutineSuspended', 'a'), held(events, { shedCount: 3 })).kind).toBe('incomplete')
    // Started and terminal events are never shed: still counted.
    expect(resolveAnchor(anchor('CoroutineStarted', 'a'), held(events, { shedCount: 3 })).kind).toBe('ready')
  })
})

describe('runEnded', () => {
  it('is true only when every coroutine created in the events has ended in them', () => {
    expect(runEnded([])).toBe(false)
    expect(runEnded([created('a'), created('b', 0, 'a'), ev('coroutine.completed', 'b', 1)])).toBe(false)
    expect(runEnded([created('a'), ev('coroutine.cancelled', 'a', 1)])).toBe(true)
  })
})

describe('momentFacts (plan P-9)', () => {
  it('reads the states at the anchor from the tree at that event, and says where they differ', () => {
    const events = [
      created('page', 0),
      ev('coroutine.started', 'page', 0),
      created('child', 0, 'page'),
      ev('coroutine.started', 'child', 0),
      ev('coroutine.suspended', 'child', 1000),
      ev('coroutine.resumed', 'child', 2500),
      ev('coroutine.completed', 'child', 2500),
    ]
    const facts = momentFacts(events, 4, { page: 'ACTIVE', child: 'COMPLETED', ghost: 'SUSPENDED' })
    expect(facts.atNanos).toBe(1000 * 1e6)
    expect(facts.checks).toEqual([
      { label: 'page', expected: 'ACTIVE', actual: 'ACTIVE' },
      { label: 'child', expected: 'COMPLETED', actual: 'SUSPENDED' },
      { label: 'ghost', expected: 'SUSPENDED', actual: null },
    ])
  })
})

describe('storyHeld (plan P-7)', () => {
  const base = { streaming: true, serverTrimmed: false, coroutines: [] }
  it('reads the source that reaches further, with its own shed count', () => {
    const a = created('a')
    const b = ev('coroutine.started', 'a', 1)
    const live = { events: [a], shedCount: 0 }
    const frozen = { events: [a, b], shedCount: 2 }
    expect(storyHeld({ ...base, live, frozen })).toMatchObject({ events: [a, b], shedCount: 2 })
    expect(storyHeld({ ...base, live: frozen, frozen: live })).toMatchObject({ events: [a, b], shedCount: 2 })
    expect(storyHeld({ ...base, live, frozen: null })).toMatchObject({ events: [a], shedCount: 0 })
  })

  it('is trimmed when the server says so or the live list is full, and knows the session\'s labels', () => {
    const held = storyHeld({
      ...base,
      serverTrimmed: true,
      live: { events: [], shedCount: 0 },
      frozen: null,
      coroutines: [{ id: 'x', jobId: 'j', parentId: null, scopeId: 's', label: 'fetch', state: 'ACTIVE' }] as never,
    })
    expect(held.trimmed).toBe(true)
    expect(held.sessionLabels.has('fetch')).toBe(true)
    const full = Array.from({ length: LIVE_EVENTS_MAX }, (_, i) => ({ seq: i + 1 }) as VizEvent)
    expect(storyHeld({ ...base, live: { events: full, shedCount: 0 }, frozen: null }).trimmed).toBe(true)
  })
})

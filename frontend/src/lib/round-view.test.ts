import { describe, it, expect } from 'vitest'
import { CoroutineState, type CoroutineNode, type RoundEntry, type RoundsPage } from '@/types/api'
import {
  asOfLabel,
  capNote,
  capRendered,
  withForced,
  defaultChoice,
  findResults,
  hasRoundFacts,
  poolFor,
  resolveChoice,
  revealPlan,
  roundOptionLabel,
  ungroupedCount,
  unknownRoundCount,
  vizcoreCount,
  type RoundChoice,
} from './round-view'

const node = (
  id: string,
  over: Partial<CoroutineNode> = {},
): CoroutineNode => ({
  id,
  jobId: `job-${id}`,
  parentId: null,
  scopeId: 'scope',
  label: id,
  state: CoroutineState.ACTIVE,
  roundId: `round:${id}`,
  origin: 'APP',
  ...over,
})

const entry = (anchorId: string, over: Partial<RoundEntry> = {}): RoundEntry => ({
  roundId: `round:${anchorId}`,
  anchorId,
  anchorName: anchorId,
  anchorOrigin: 'APP',
  anchorCreatedNanos: 0,
  anchorCreatedSeq: 1,
  inProgress: false,
  countsByState: {},
  memberCount: 1,
  problem: false,
  problemKinds: [],
  ...over,
})

const SEC = 1_000_000_000
const ALL: RoundChoice = { kind: 'all' }
const round = (anchorId: string): RoundChoice => ({ kind: 'round', roundId: `round:${anchorId}`, anchorId })

/** An app: a request round (r1 + two children), a second request, a vizcore root, an orphan. */
const app = [
  node('r1'),
  node('r1-a', { parentId: 'r1', roundId: 'round:r1' }),
  node('r1-b', { parentId: 'r1', roundId: 'round:r1', state: CoroutineState.COMPLETED }),
  node('r2'),
  node('cio', { origin: 'VIZCORE' }),
  node('orphan', { parentId: 'gone', roundId: null }),
]

describe('round facts', () => {
  it('are there when any node carries an origin', () => {
    expect(hasRoundFacts(app)).toBe(true)
    expect(hasRoundFacts(app.map(c => ({ ...c, origin: null, roundId: null })))).toBe(false)
    expect(hasRoundFacts([{ ...node('x'), origin: undefined, roundId: undefined }])).toBe(false)
    expect(hasRoundFacts([])).toBe(false)
  })
})

describe('poolFor', () => {
  const ids = (cs: CoroutineNode[]) => cs.map(c => c.id)

  it('All rounds leaves vizcore coroutines out unless the toggle is on', () => {
    expect(ids(poolFor(app, ALL, { includeVizcore: false }))).toEqual(['r1', 'r1-a', 'r1-b', 'r2', 'orphan'])
    expect(ids(poolFor(app, ALL, { includeVizcore: true }))).toEqual(ids(app))
  })

  it('one round is the nodes the server put in it', () => {
    expect(ids(poolFor(app, round('r1'), { includeVizcore: false }))).toEqual(['r1', 'r1-a', 'r1-b'])
  })

  it('a vizcore round shows its coroutines when chosen with the toggle on', () => {
    expect(ids(poolFor(app, round('cio'), { includeVizcore: true }))).toEqual(['cio'])
  })

  it('leaves a vizcore coroutine inside an app round out while the toggle is off', () => {
    const cs = [...app, node('sdk-helper', { parentId: 'r1', roundId: 'round:r1', origin: 'VIZCORE' })]
    expect(ids(poolFor(cs, round('r1'), { includeVizcore: false }))).toEqual(['r1', 'r1-a', 'r1-b'])
    expect(ids(poolFor(cs, round('r1'), { includeVizcore: true }))).toContain('sdk-helper')
  })

  it('Ungrouped is the nodes with an origin and no round', () => {
    const withUnsummarised = [...app, node('fresh', { roundId: null, origin: null })]
    expect(ids(poolFor(withUnsummarised, { kind: 'ungrouped' }, { includeVizcore: false }))).toEqual(['orphan'])
    expect(ungroupedCount(withUnsummarised, { includeVizcore: false })).toBe(1)
  })

  it('a vizcore orphan is ungrouped only with the toggle on', () => {
    const cs = [...app, node('viz-orphan', { roundId: null, origin: 'VIZCORE', parentId: 'gone' })]
    expect(ungroupedCount(cs, { includeVizcore: false })).toBe(1)
    expect(ungroupedCount(cs, { includeVizcore: true })).toBe(2)
  })

  it('"Round unknown" is the nodes without a summary, whose round the page never guesses', () => {
    const cs = [...app, node('fresh', { roundId: null, origin: null })]
    expect(ids(poolFor(cs, { kind: 'unknown' }, { includeVizcore: false }))).toEqual(['fresh'])
    expect(unknownRoundCount(cs)).toBe(1)
    expect(ids(poolFor(cs, ALL, { includeVizcore: false }))).toContain('fresh')
    expect(resolveChoice({ kind: 'unknown' }, app, ALL, { includeVizcore: false })).toEqual(ALL)
    expect(resolveChoice({ kind: 'unknown' }, cs, ALL, { includeVizcore: false })).toEqual({ kind: 'unknown' })
  })

  it('counts vizcore coroutines', () => {
    expect(vizcoreCount(app)).toBe(1)
    expect(vizcoreCount([node('a')])).toBe(0)
  })
})

describe('the default view', () => {
  it('opens an attached app on the first round the server lists', () => {
    expect(defaultChoice({ attached: true, firstRound: entry('r2') })).toEqual(round('r2'))
  })

  it('opens a demo on All rounds', () => {
    expect(defaultChoice({ attached: false, firstRound: entry('r2') })).toEqual(ALL)
  })

  it('opens an attached app with no round on All rounds', () => {
    expect(defaultChoice({ attached: true, firstRound: undefined })).toEqual(ALL)
  })
})

describe('resolveChoice', () => {
  const fallback: RoundChoice = round('r2')

  it('keeps a round that still has nodes', () => {
    expect(resolveChoice(round('r1'), app, fallback, { includeVizcore: false })).toEqual(round('r1'))
  })

  it('follows the anchor into the round it was regrouped into', () => {
    // r1 was a request round; its parent became a plain root again and absorbed it.
    const merged = app.map(c => (c.roundId === 'round:r1' ? { ...c, roundId: 'round:server' } : c))
    expect(resolveChoice(round('r1'), merged, fallback, { includeVizcore: false })).toEqual({
      kind: 'round',
      roundId: 'round:server',
      anchorId: 'r1',
    })
  })

  it('falls back when the round and its anchor are gone', () => {
    const gone = app.filter(c => c.roundId !== 'round:r1')
    expect(resolveChoice(round('r1'), gone, fallback, { includeVizcore: false })).toEqual(fallback)
  })

  it('falls back from a vizcore round once the toggle is off', () => {
    expect(resolveChoice(round('cio'), app, fallback, { includeVizcore: false })).toEqual(fallback)
    expect(resolveChoice(round('cio'), app, fallback, { includeVizcore: true })).toEqual(round('cio'))
  })

  it('falls back from Ungrouped once nothing is ungrouped', () => {
    const none = app.filter(c => c.id !== 'orphan')
    expect(resolveChoice({ kind: 'ungrouped' }, none, fallback, { includeVizcore: false })).toEqual(fallback)
    expect(resolveChoice({ kind: 'ungrouped' }, app, fallback, { includeVizcore: false })).toEqual({ kind: 'ungrouped' })
  })

  it('keeps All rounds', () => {
    expect(resolveChoice(ALL, app, fallback, { includeVizcore: false })).toEqual(ALL)
  })
})

describe('roundOptionLabel', () => {
  it('names the anchor, its time into the capture, the members and how it is doing', () => {
    expect(
      roundOptionLabel(entry('request-17', { anchorCreatedNanos: 64 * SEC, inProgress: true }), 6, 2 * SEC),
    ).toBe('request-17 · at 1 m 2 s · 6 coroutines · in progress')
  })

  it('says finished, one coroutine, and leaves the time out without a capture start', () => {
    expect(roundOptionLabel(entry('r1'), 1, null)).toBe('r1 · 1 coroutine · finished')
  })

  it('marks problems and vizcore rounds in words', () => {
    expect(roundOptionLabel(entry('r2', { problem: true, problemKinds: ['FAILED'] }), 2, 0)).toBe(
      'r2 · at 0 ms · 2 coroutines · failed',
    )
    expect(
      roundOptionLabel(entry('r3', { problem: true, problemKinds: ['FAILED', 'POSSIBLY_BLOCKED'], inProgress: true }), 2, null),
    ).toBe('r3 · 2 coroutines · failed, possibly blocked')
    expect(roundOptionLabel(entry('cio', { anchorOrigin: 'VIZCORE', inProgress: true }), 1, null)).toBe(
      'cio · 1 coroutine · in progress · vizcore connection',
    )
  })

  it('leaves the status words out of an option kept from an older read', () => {
    expect(
      roundOptionLabel(entry('r2', { problem: true, problemKinds: ['FAILED'], anchorCreatedNanos: 3 * SEC }), 2, 0, { status: false }),
    ).toBe('r2 · at 3.0 s · 2 coroutines')
  })

  it('names an anchor without a name by its id', () => {
    expect(roundOptionLabel(entry('dp-17', { anchorName: null }), 1, null)).toBe('dp-17 · 1 coroutine · finished')
  })
})

describe('revealPlan', () => {
  const base = { stateFilter: 'all' as const, includeVizcore: false, leakIds: new Set<string>() }

  it('switches to the match’s round and forces it and its ancestors past the cap', () => {
    const plan = revealPlan('r1-a', app, { ...base, anchorOf: () => 'r1' })!
    expect(plan.choice).toEqual(round('r1'))
    expect([...plan.forcedIds].sort()).toEqual(['r1', 'r1-a'])
    expect(plan.stateFilter).toBe('all')
    expect(plan.showCompleted).toBe(false)
    expect(plan.includeVizcore).toBe(false)
  })

  it('clears a state chip that would hide it and shows completed for a finished match', () => {
    const plan = revealPlan('r1-b', app, { ...base, stateFilter: 'active', anchorOf: () => 'r1' })!
    expect(plan.stateFilter).toBe('all')
    expect(plan.showCompleted).toBe(true)
  })

  it('keeps a chip that already shows it', () => {
    const plan = revealPlan('r1-b', app, { ...base, stateFilter: 'completed', anchorOf: () => 'r1' })!
    expect(plan.stateFilter).toBe('completed')
  })

  it('turns the vizcore toggle on for a vizcore match', () => {
    expect(revealPlan('cio', app, { ...base, anchorOf: () => 'cio' })!.includeVizcore).toBe(true)
  })

  it('opens Ungrouped for a match without a round', () => {
    const plan = revealPlan('orphan', app, { ...base, anchorOf: () => undefined })!
    expect(plan.choice).toEqual({ kind: 'ungrouped' })
    expect([...plan.forcedIds]).toEqual(['orphan'])
  })

  it('cannot reveal a coroutine the snapshot does not hold', () => {
    expect(revealPlan('trimmed', app, { ...base, anchorOf: () => 'r1' })).toBeNull()
  })
})

describe('capRendered', () => {
  const many = Array.from({ length: 250 }, (_, i) => node(`c${i}`, { roundId: 'round:big' }))

  it('renders the first ones up to the cap and counts the rest', () => {
    const r = capRendered(many, 200, new Set())
    expect(r.rendered).toHaveLength(200)
    expect(r).toMatchObject({ total: 250, hidden: 50, found: 0 })
  })

  it('adds a forced coroutine past the cap, in pool order', () => {
    // Ids sort as text: c0…c199 is not the first 200 (c1, c10, c100…); pick one surely past them.
    const past = many.map(c => c.id).sort().at(-1)!
    const r = capRendered(many, 200, new Set([past, 'c0']))
    expect(r.rendered).toHaveLength(201)
    expect(r.rendered.map(c => c.id)).toContain(past)
    expect(r).toMatchObject({ total: 250, hidden: 49, found: 1 })
  })

  it('picks the same coroutines whatever order the snapshot lists them in, and draws them in that order', () => {
    const reversed = [...many].reverse()
    const a = capRendered(many, 200, new Set()).rendered.map(c => c.id)
    const b = capRendered(reversed, 200, new Set()).rendered.map(c => c.id)
    expect(new Set(b)).toEqual(new Set(a))
    expect(b).toEqual(reversed.map(c => c.id).filter(id => a.includes(id)))
  })

  it('renders everything under the cap', () => {
    expect(capRendered(many.slice(0, 5), 200, new Set(['c1']))).toMatchObject({ total: 5, hidden: 0, found: 0 })
  })
})

describe('asOfLabel', () => {
  it('says the time into the capture, else the event number', () => {
    expect(asOfLabel({ asOfAppNanos: 66 * SEC, asOfSeq: 1234 }, 2 * SEC)).toBe('as of 1 m 4 s')
    expect(asOfLabel({ asOfAppNanos: null, asOfSeq: 1234 }, 2 * SEC)).toBe('as of event 1,234')
    expect(asOfLabel({ asOfAppNanos: 5 * SEC, asOfSeq: 9 }, null)).toBe('as of event 9')
  })
})

describe('findResults', () => {
  const found = (over: Partial<RoundsPage> = {}): RoundsPage => ({
    asOfSeq: 40,
    total: 2,
    items: [
      entry('r1', { anchorCreatedNanos: 12 * SEC, matches: { items: ['r1-b', 'r1-a'], total: 2 } }),
      entry('r2', { anchorCreatedNanos: 14 * SEC, matches: { items: ['r2'], total: 300 } }),
    ],
    nextCursor: null,
    unknownAtSeq: 0,
    ungroupedMatches: { items: ['orphan', 'trimmed'], total: 2 },
    asOfAppNanos: 20 * SEC,
    ...over,
  })
  const ctx = { captureStartAppNanos: 0, includeVizcore: false, attached: true }

  it('groups the matches by round, ungrouped last, with names and states from the snapshot', () => {
    const view = findResults(found(), app, ctx)
    expect(view.summary).toBe('Found 304 (302 in 2 rounds, 2 ungrouped) · as of 20.0 s')
    expect(view.groups.map(g => g.title)).toEqual(['r1 · at 12.0 s', 'r2 · at 14.0 s', 'Ungrouped'])
    expect(view.groups[0]!.total).toBe(2)
    expect(view.groups[0]!.matches).toEqual([
      { id: 'r1-b', name: 'r1-b', state: 'Completed', selectable: true },
      { id: 'r1-a', name: 'r1-a', state: 'Active', selectable: true },
    ])
    expect(view.groups[2]!.matches[1]).toEqual({ id: 'trimmed', name: 'trimmed', state: null, selectable: false })
  })

  it('says how much of a round it lists', () => {
    const view = findResults(found(), app, ctx)
    expect(view.groups[1]!.partial).toBe(
      'The first 1 of 300 matches in r2 are listed. Coroutines with the same name and creation site cannot be told apart here: refine the search, or open the round.',
    )
    expect(view.groups[1]!.entry?.roundId).toBe('round:r2')
    expect(view.groups[0]!.partial).toBeNull()
  })

  it('says when more rounds matched than were read', () => {
    expect(findResults(found({ total: 260 }), app, ctx).moreRounds).toBe('258 more rounds matched and are not listed: refine the search. Matches that share a name and creation site cannot be told apart by a search.')
    expect(findResults(found(), app, ctx).moreRounds).toBeNull()
  })

  it('leaves vizcore matches out while the toggle is off', () => {
    const page = found({ items: [entry('r1', { matches: { items: ['cio', 'r1-a'], total: 2 } })], ungroupedMatches: null })
    const view = findResults(page, app, ctx)
    expect(view.groups[0]!.matches.map(m => m.id)).toEqual(['r1-a'])
    expect(view.summary).toBe('Found 1 in 1 round · as of 20.0 s')
    expect(findResults(page, app, { ...ctx, includeVizcore: true }).groups[0]!.matches).toHaveLength(2)
  })

  it('says coroutines without a summary were not searched', () => {
    expect(findResults(found(), app, { ...ctx, unsearched: 3 }).unsearched).toBe(
      '3 coroutines without a summary were not searched.',
    )
    expect(findResults(found(), app, ctx).unsearched).toBeNull()
  })

  it('says nothing matched in the captured coroutines, with the sampling sentence for an app', () => {
    const none = found({ total: 0, items: [], ungroupedMatches: { items: [], total: 0 } })
    expect(findResults(none, app, ctx)).toMatchObject({
      summary: 'Nothing matched in the captured coroutines · as of 20.0 s',
      note: 'vizcore samples the app on a poll, so a coroutine that started and ended between two polls was not captured.',
      groups: [],
    })
    expect(findResults(none, app, { ...ctx, attached: false }).note).toBeNull()
  })
})

describe('capNote', () => {
  it('says nothing when everything is drawn', () => {
    expect(capNote({ total: 5, rendered: 5, found: 0, cap: 200, scope: 'round', canFind: true })).toBeNull()
  })

  it('counts what the cap leaves out, and points at Find where there is one', () => {
    expect(capNote({ total: 1234, rendered: 200, found: 0, cap: 200, scope: 'round', canFind: true })).toBe(
      'Showing 200 of 1,234 coroutines in this round. Find reaches one by its name or creation site.',
    )
    expect(capNote({ total: 1234, rendered: 200, found: 0, cap: 200, scope: 'view', canFind: false })).toBe(
      'Showing 200 of 1,234 coroutines.',
    )
    expect(capNote({ total: 300, rendered: 200, found: 0, cap: 200, scope: 'ungrouped', canFind: true })).toBe(
      'Showing 200 of 300 coroutines without a round. Find reaches one by its name or creation site.',
    )
    expect(capNote({ total: 300, rendered: 200, found: 0, cap: 200, scope: 'all', canFind: true })).toBe(
      'Showing 200 of 300 coroutines in all rounds. Find reaches one by its name or creation site.',
    )
  })

  it('names the cards a found coroutine took past the cap', () => {
    expect(capNote({ total: 1234, rendered: 201, found: 1, cap: 200, scope: 'round', canFind: true })).toBe(
      'Showing 201 of 1,234 coroutines in this round: the first 200, and the one you found.',
    )
    expect(capNote({ total: 1234, rendered: 203, found: 3, cap: 200, scope: 'round', canFind: true })).toBe(
      'Showing 203 of 1,234 coroutines in this round: the first 200, and 3 more to show the one you found.',
    )
  })
})

describe('withForced', () => {
  it('keeps a found coroutine’s ancestors that the state chip would hide, in view order', () => {
    const view = [node('p', { state: CoroutineState.WAITING_FOR_CHILDREN }), node('c', { parentId: 'p' }), node('d')]
    const pool = view.filter(c => c.state === CoroutineState.ACTIVE)
    expect(withForced(pool, view, new Set(['p', 'c'])).map(c => c.id)).toEqual(['p', 'c', 'd'])
    expect(withForced(pool, view, new Set())).toBe(pool)
  })
})

describe('capNote in Round unknown', () => {
  it('does not offer Find, which cannot search coroutines without a summary', () => {
    expect(capNote({ total: 300, rendered: 200, found: 0, cap: 200, scope: 'unknown', canFind: false })).toBe(
      'Showing 200 of 300 coroutines without a summary. Find cannot search them.',
    )
  })
})

describe('capNote with groups (cards, not coroutines)', () => {
  it('counts cards and names the coroutines they stand for', () => {
    expect(capNote({ total: 350, rendered: 200, found: 0, cap: 200, scope: 'round', canFind: true, coroutines: 1234 })).toBe(
      'Showing 200 of 350 cards (1,234 coroutines in this round). Find reaches one by its name or creation site.',
    )
  })
})

describe('capNote unit', () => {
  it('counts cards whenever groups are drawn, even when the totals happen to be equal', () => {
    expect(capNote({ total: 205, rendered: 200, found: 0, cap: 200, scope: 'round', canFind: true, coroutines: 205 })).toBe(
      'Showing 200 of 205 cards (205 coroutines in this round). Find reaches one by its name or creation site.',
    )
  })
})

describe('capRendered keeps ancestry', () => {
  it('draws the parents of every coroutine it keeps, even when their ids sort past the cap', () => {
    const kids = Array.from({ length: 300 }, (_, i) => node(`f${String(i).padStart(3, '0')}`, { parentId: 'zz-group' }))
    const pool = [node('zz-root'), node('zz-group', { parentId: 'zz-root' }), ...kids]
    const r = capRendered(pool, 200, new Set())
    expect(r.rendered.map(c => c.id)).toEqual(expect.arrayContaining(['zz-root', 'zz-group']))
    expect(r).toMatchObject({ total: 302, found: 0 })
    expect(r.rendered).toHaveLength(200)
  })
})

describe('capRendered on a deep chain', () => {
  it('stays fast when the deepest coroutines sort first', () => {
    // c00000 is the deepest: every candidate's path runs up the whole chain.
    const n = 20_000
    const chain = Array.from({ length: n }, (_, i) =>
      node(`c${String(i).padStart(5, '0')}`, { parentId: i === n - 1 ? null : `c${String(i + 1).padStart(5, '0')}` }),
    )
    const started = performance.now()
    const r = capRendered(chain, 200, new Set())
    expect(performance.now() - started).toBeLessThan(500)
    expect(r.rendered).toHaveLength(200)
    // What it keeps is connected: the top 200 of the chain.
    expect(r.rendered.every(c => c.parentId === null || r.rendered.some(p => p.id === c.parentId))).toBe(true)
  })
})

describe('revealPlan on a deep path', () => {
  it('forces the match and its nearest 50 ancestors, and says how deep the path really is', () => {
    const chain = Array.from({ length: 120 }, (_, i) => node(`d${i}`, { parentId: i === 0 ? null : `d${i - 1}`, roundId: 'round:d0' }))
    const plan = revealPlan('d119', chain, { stateFilter: 'all', includeVizcore: false, leakIds: new Set(), anchorOf: () => 'd0' })!
    expect(plan.pathDepth).toBe(119)
    expect(plan.forcedIds.size).toBe(51)
    expect(plan.forcedIds.has('d69')).toBe(true)
    expect(plan.forcedIds.has('d68')).toBe(false)
  })

  it('stops at a cycle in the reported parents', () => {
    const cyc = [node('p', { parentId: 'q', roundId: 'round:x' }), node('q', { parentId: 'p', roundId: 'round:x' })]
    expect(revealPlan('p', cyc, { stateFilter: 'all', includeVizcore: false, leakIds: new Set(), anchorOf: () => 'p' })!.pathDepth).toBe(1)
  })
})

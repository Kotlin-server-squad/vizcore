/**
 * Which coroutines the main view shows in a big app (dock spec S-12, triage T-4): one round at a time, All rounds,
 * or the coroutines that belong to no round, with vizcore's own coroutines left out unless asked for.
 *
 * Presentation only (D-8): the round of each coroutine and its origin are the server's (`roundId`, `origin` on the
 * session snapshot); the round list, its order and its problem marks come from `GET …/rounds`. Nothing here decides
 * what a round is, where it is anchored or whether it has a problem.
 */
import { CoroutineState, type CoroutineNode, type RoundEntry, type RoundsPage } from '@/types/api'
import { stateLabel } from './coroutine-state-colors'
import { formatDuration } from './format-duration'
import { selectCoroutines, type StateFilter } from './state-counts'

/** What the main view shows: every round, one round (kept with its anchor), or the coroutines in no round. */
export type RoundChoice =
  | { kind: 'all' }
  | { kind: 'round'; roundId: string; anchorId: string }
  | { kind: 'ungrouped' }
  /** Coroutines without a summary (not folded yet, or past the summary capacity): their round is not known. */
  | { kind: 'unknown' }

interface VizcoreRule {
  /** The "vizcore connection (n)" toggle is on: vizcore's own coroutines are shown. */
  includeVizcore: boolean
}

const TERMINAL = new Set<string>([CoroutineState.COMPLETED, CoroutineState.CANCELLED, CoroutineState.FAILED])

/** The snapshot carries the server's round facts (a node with an origin); older servers and replay do not. */
export function hasRoundFacts(coroutines: readonly CoroutineNode[]): boolean {
  return coroutines.some(c => c.origin != null)
}

const isVizcore = (c: CoroutineNode) => c.origin === 'VIZCORE'

/** In no round: it has a summary (an origin) but its ancestry reaches a parent that was never captured. */
const isUngrouped = (c: CoroutineNode) => c.origin != null && c.roundId == null

/** The coroutines the choice shows, before the state filter and the cap. */
export function poolFor(
  coroutines: readonly CoroutineNode[],
  choice: RoundChoice,
  { includeVizcore }: VizcoreRule,
): CoroutineNode[] {
  switch (choice.kind) {
    case 'round':
      // The server filters rounds by their anchor's origin only: a vizcore coroutine inside an app round is left out
      // here, by its own origin (the server's), while the toggle is off.
      return coroutines.filter(c => c.roundId === choice.roundId && (includeVizcore || !isVizcore(c)))
    case 'ungrouped':
      return coroutines.filter(c => isUngrouped(c) && (includeVizcore || !isVizcore(c)))
    case 'unknown':
      return coroutines.filter(c => c.origin == null)
    case 'all':
      return includeVizcore ? [...coroutines] : coroutines.filter(c => !isVizcore(c))
  }
}

export function vizcoreCount(coroutines: readonly CoroutineNode[]): number {
  return coroutines.filter(isVizcore).length
}

/** Coroutines whose round the server could not say: no summary (origin null). */
export function unknownRoundCount(coroutines: readonly CoroutineNode[]): number {
  return coroutines.filter(c => c.origin == null).length
}

export function ungroupedCount(coroutines: readonly CoroutineNode[], rule: VizcoreRule): number {
  return poolFor(coroutines, { kind: 'ungrouped' }, rule).length
}

/** An attached app opens on the first round the server lists (problem rounds first, then newest); a demo on All. */
export function defaultChoice({
  attached,
  firstRound,
}: {
  attached: boolean
  firstRound: RoundEntry | undefined
}): RoundChoice {
  if (!attached || !firstRound) return { kind: 'all' }
  return { kind: 'round', roundId: firstRound.roundId, anchorId: firstRound.anchorId }
}

/**
 * The choice as it can be shown now. A round no coroutine carries any more was regrouped (a root qualified as a
 * container, or its promotion was cleared): the view follows its anchor into its current round. When the anchor is
 * gone too, or the round is vizcore's and the toggle is off, or nothing is ungrouped any more, the fallback.
 */
export function resolveChoice(
  choice: RoundChoice,
  coroutines: readonly CoroutineNode[],
  fallback: RoundChoice,
  rule: VizcoreRule,
): RoundChoice {
  if (choice.kind === 'all') return choice
  if (choice.kind === 'ungrouped') return ungroupedCount(coroutines, rule) > 0 ? choice : fallback
  if (choice.kind === 'unknown') return unknownRoundCount(coroutines) > 0 ? choice : fallback
  let current: RoundChoice = choice
  let members = coroutines.filter(c => c.roundId === choice.roundId)
  if (members.length === 0) {
    const anchorRound = coroutines.find(c => c.id === choice.anchorId)?.roundId
    if (!anchorRound) return fallback
    current = { kind: 'round', roundId: anchorRound, anchorId: choice.anchorId }
    members = coroutines.filter(c => c.roundId === anchorRound)
  }
  if (!rule.includeVizcore && members.every(isVizcore)) return fallback
  return current
}

const count = (n: number) => n.toLocaleString('en-US')

/** A health read's moment (H-2): its time into the capture, else its event number. */
export function asOfLabel(
  page: { asOfAppNanos?: number | null; asOfSeq: number },
  captureStartAppNanos: number | null | undefined,
): string {
  if (page.asOfAppNanos != null && captureStartAppNanos != null) {
    return `as of ${formatDuration(page.asOfAppNanos - captureStartAppNanos)}`
  }
  return `as of event ${count(page.asOfSeq)}`
}

/** A round's anchor and its time into the capture: how find results and options name it. */
export function roundTitle(entry: RoundEntry, captureStartAppNanos: number | null | undefined): string {
  const name = entry.anchorName ?? entry.anchorId
  return captureStartAppNanos != null ? `${name} · at ${formatDuration(entry.anchorCreatedNanos - captureStartAppNanos)}` : name
}

export interface FindMatch {
  id: string
  name: string
  /** Its state in words, from the snapshot; null when the snapshot does not hold it. */
  state: string | null
  /** The page holds it, so it can be drawn and selected. */
  selectable: boolean
}

export interface FindGroup {
  key: string
  title: string
  /** The round's entry, so choosing a match can keep the round's anchor; null for Ungrouped. */
  entry: RoundEntry | null
  matches: FindMatch[]
  /** Its matches in all, listed or not. */
  total: number
  /** What the list leaves out of this round, in words; null when it lists every match. */
  partial: string | null
}

export interface FindResults {
  summary: string
  groups: FindGroup[]
  /** More rounds matched than one find reads; null otherwise. */
  moreRounds: string | null
  /** The sampling sentence (H-4) when nothing matched in an app's session; null otherwise. */
  note: string | null
  /** Find searches summaries: coroutines without one were not searched; null when there are none. */
  unsearched: string | null
}

/**
 * "Find in all rounds" as the page lists it (S-12): grouped by round in the server's order, ungrouped matches last,
 * and explicitly partial — the server lists the first 50 matches of each round and reads at most 200 rounds, and the
 * list says so. Vizcore's own coroutines are left out while the toggle is off, by their origin (the server filters
 * rounds by their anchor only).
 */
export function findResults(
  page: RoundsPage,
  coroutines: readonly CoroutineNode[],
  { captureStartAppNanos, includeVizcore, attached, unsearched = 0 }: {
    captureStartAppNanos: number | null | undefined
    includeVizcore: boolean
    attached: boolean
    /** Coroutines without a summary, which the server's find cannot search. */
    unsearched?: number
  },
): FindResults {
  const byId = new Map(coroutines.map(c => [c.id, c]))
  const shown = (id: string) => includeVizcore || byId.get(id)?.origin !== 'VIZCORE'
  const toMatch = (id: string): FindMatch => {
    const c = byId.get(id)
    return { id, name: c ? (c.label ?? c.id) : id, state: c ? stateLabel(c.state) : null, selectable: !!c }
  }
  const group = (
    key: string,
    title: string,
    entry: RoundEntry | null,
    capped: { items: string[]; total: number },
  ): FindGroup | null => {
    const items = capped.items.filter(shown)
    const total = capped.total - (capped.items.length - items.length)
    if (total <= 0) return null
    const where = entry ? ` in ${entry.anchorName ?? entry.anchorId}` : ' without a round'
    return {
      key,
      title,
      entry,
      matches: items.map(toMatch),
      total,
      partial:
        total > items.length
          ? `The first ${count(items.length)} of ${count(total)} matches${where} are listed. Coroutines with the same name and creation site cannot be told apart here: refine the search${entry ? ', or open the round' : ''}.`
          : null,
    }
  }
  const rounds = page.items
    .map(e => (e.matches ? group(e.roundId, roundTitle(e, captureStartAppNanos), e, e.matches) : null))
    .filter((g): g is FindGroup => g !== null)
  const ungrouped = page.ungroupedMatches ? group('ungrouped', 'Ungrouped', null, page.ungroupedMatches) : null
  const inRounds = rounds.reduce((n, g) => n + g.total, 0)
  const outside = ungrouped?.total ?? 0
  const asOf = asOfLabel(page, captureStartAppNanos)
  const roundWord = rounds.length === 1 ? '1 round' : `${count(rounds.length)} rounds`
  const summary =
    inRounds + outside === 0
      ? `Nothing matched in the captured coroutines · ${asOf}`
      : outside === 0
        ? `Found ${count(inRounds)} in ${roundWord} · ${asOf}`
        : inRounds === 0
          ? `Found ${count(outside)} ungrouped · ${asOf}`
          : `Found ${count(inRounds + outside)} (${count(inRounds)} in ${roundWord}, ${count(outside)} ungrouped) · ${asOf}`
  const unread = page.total - page.items.length
  return {
    summary,
    groups: ungrouped ? [...rounds, ungrouped] : rounds,
    moreRounds: unread > 0 ? `${count(unread)} more rounds matched and are not listed: refine the search. Matches that share a name and creation site cannot be told apart by a search.` : null,
    unsearched:
      unsearched > 0
        ? `${unsearched === 1 ? '1 coroutine' : `${count(unsearched)} coroutines`} without a summary ${unsearched === 1 ? 'was' : 'were'} not searched.`
        : null,
    note:
      inRounds + outside === 0 && attached
        ? 'vizcore samples the app on a poll, so a coroutine that started and ended between two polls was not captured.'
        : null,
  }
}

/**
 * A round as the picker lists it: its anchor, the anchor's time into the capture, how many coroutines the page holds
 * in it, and how it is doing — in words, never colour alone.
 */
export function roundOptionLabel(
  entry: RoundEntry,
  members: number,
  captureStartAppNanos: number | null | undefined,
  { status = true }: { status?: boolean } = {},
): string {
  const parts = [entry.anchorName ?? entry.anchorId]
  if (captureStartAppNanos != null) parts.push(`at ${formatDuration(entry.anchorCreatedNanos - captureStartAppNanos)}`)
  parts.push(members === 1 ? '1 coroutine' : `${members} coroutines`)
  // An entry kept from an older read: its problem marks are not of the list's moment, so none are claimed (H-2).
  if (!status) return parts.join(' · ')
  const problems = [
    ...(entry.problemKinds.includes('FAILED') ? ['failed'] : []),
    ...(entry.problemKinds.includes('POSSIBLY_BLOCKED') ? ['possibly blocked'] : []),
  ]
  parts.push(problems.length > 0 ? problems.join(', ') : entry.inProgress ? 'in progress' : 'finished')
  if (entry.anchorOrigin === 'VIZCORE') parts.push('vizcore connection')
  return parts.join(' · ')
}

/** What showing a found coroutine takes (S-12): its view, the filters that would hide it cleared, it and its ancestors. */
export interface RevealPlan {
  choice: RoundChoice
  stateFilter: StateFilter
  showCompleted: boolean
  includeVizcore: boolean
  /** Rendered even past the cap: the match and its ancestors in the same view. */
  forcedIds: ReadonlySet<string>
}

export function revealPlan(
  id: string,
  coroutines: readonly CoroutineNode[],
  context: {
    stateFilter: StateFilter
    includeVizcore: boolean
    leakIds: ReadonlySet<string>
    /** The anchor of the match's round, from the find result (`RoundEntry.anchorId`). */
    anchorOf: (roundId: string) => string | undefined
  },
): RevealPlan | null {
  const byId = new Map(coroutines.map(c => [c.id, c]))
  const match = byId.get(id)
  if (!match) return null
  const choice: RoundChoice = match.roundId
    ? { kind: 'round', roundId: match.roundId, anchorId: context.anchorOf(match.roundId) ?? match.id }
    : { kind: 'ungrouped' }
  const sameView = (c: CoroutineNode) => (match.roundId ? c.roundId === match.roundId : isUngrouped(c))
  const forced = new Set<string>([match.id])
  for (let parent = match.parentId ? byId.get(match.parentId) : undefined; parent && sameView(parent) && !forced.has(parent.id); ) {
    forced.add(parent.id)
    parent = parent.parentId ? byId.get(parent.parentId) : undefined
  }
  const shownByChip = selectCoroutines([match], context.stateFilter, context.leakIds).length > 0
  return {
    choice,
    stateFilter: shownByChip ? context.stateFilter : 'all',
    showCompleted: TERMINAL.has(match.state),
    includeVizcore: context.includeVizcore || isVizcore(match),
    forcedIds: forced,
  }
}

/**
 * The pool cut at the cap, plus the forced coroutines past it, drawn in pool order; and the figures for the cap note.
 *
 * Which coroutines make the cut is decided by id, not by the snapshot's order: the server lists its nodes in a hash
 * map's order, which reshuffles as a busy app adds coroutines, and the drawn set must not churn with every refresh.
 * A pool within the cap is drawn whole, in its own order, as before.
 */
export function capRendered(
  pool: readonly CoroutineNode[],
  cap: number,
  forcedIds: ReadonlySet<string>,
): { rendered: CoroutineNode[]; total: number; hidden: number; found: number } {
  if (pool.length <= cap) return { rendered: [...pool], total: pool.length, hidden: 0, found: 0 }
  // The cap spends its cards on whole paths: each card kept brings the ancestors it is missing (within the cap), so no
  // kept card is drawn without its parents, and an expanded group keeps its card and its parent.
  const byId = new Map(pool.map(c => [c.id, c]))
  const kept = new Set<string>()
  for (const id of pool.map(c => c.id).sort()) {
    if (kept.has(id)) continue
    const path: string[] = []
    for (let c = byId.get(id); c && !kept.has(c.id); c = c.parentId ? byId.get(c.parentId) : undefined) path.push(c.id)
    if (kept.size + path.length > cap) continue
    for (const p of path) kept.add(p)
    if (kept.size === cap) break
  }
  const rendered = pool.filter(c => kept.has(c.id) || forcedIds.has(c.id))
  const found = rendered.length - kept.size
  return { rendered, total: pool.length, hidden: pool.length - rendered.length, found }
}

/**
 * The filtered pool plus the forced coroutines (a found one and its ancestors) that the filter left out, in the view's
 * order: a found coroutine's parents stay drawn whatever the state chip shows (S-12).
 */
export function withForced(
  pool: readonly CoroutineNode[],
  view: readonly CoroutineNode[],
  forcedIds: ReadonlySet<string>,
): readonly CoroutineNode[] {
  if (forcedIds.size === 0) return pool
  const inPool = new Set(pool.map(c => c.id))
  if ([...forcedIds].every(id => inPool.has(id) || !view.some(c => c.id === id))) return pool
  return view.filter(c => inPool.has(c.id) || forcedIds.has(c.id))
}

/** Where the canvas's coroutines come from, for the cap note's wording. */
export type CapScope = 'round' | 'ungrouped' | 'unknown' | 'all' | 'view'

const SCOPE_WORDS: Record<CapScope, string> = {
  round: ' in this round',
  ungrouped: ' without a round',
  unknown: ' without a summary',
  all: ' in all rounds',
  view: '',
}

/**
 * What the canvas says when the node cap trims what it draws (S-12): how many it draws of how many, the cards a
 * found coroutine took past the cap, and Find where the page has it. Null when everything is drawn.
 */
export function capNote({
  total,
  rendered,
  found,
  cap,
  scope,
  canFind,
  coroutines,
}: {
  total: number
  rendered: number
  found: number
  cap: number
  scope: CapScope
  canFind: boolean
  /** Given when groups are drawn: `total` counts cards, and this is the coroutines they stand for. */
  coroutines?: number
}): string | null {
  if (rendered >= total) return null
  const head =
    coroutines !== undefined
      ? `Showing ${count(rendered)} of ${count(total)} cards (${count(coroutines)} coroutines${SCOPE_WORDS[scope]})`
      : `Showing ${count(rendered)} of ${count(total)} coroutines${SCOPE_WORDS[scope]}`
  if (found > 0) {
    return `${head}: the first ${count(cap)}, and ${found === 1 ? 'the one you found' : `${count(found)} more to show the one you found`}.`
  }
  if (scope === 'unknown') return `${head}. Find cannot search them.`
  return canFind ? `${head}. Find reaches one by its name or creation site.` : `${head}.`
}

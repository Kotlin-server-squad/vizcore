/**
 * "Group by name" (dock spec S-12): siblings only — coroutines with the same parent, in the same round, whose names
 * differ only in a trailing number (`order-17`, `order-18` → `order-*`) — fold into one group in their place, so
 * ancestry is never hidden. Presentation only: the round is the server's (`roundId`), nothing here decides one (D-8).
 *
 * A group is a synthetic node between the parent and its members. Collapsed (the default) it is a leaf, and its
 * members with everything below them are left out; expanded, its members hang under it with their subtrees.
 */
import { CoroutineState, type CoroutineNode } from '@/types/api'
import { stateLabel } from './coroutine-state-colors'

/** The id prefix of a group's synthetic node. */
export const GROUP_PREFIX = 'group:'

const TRAILING_NUMBER = /^(.*\D)\d+$/

/** The name without its trailing number, or null when it has none (or there is no name). */
export function nameStem(label: string | null | undefined): string | null {
  return label ? (TRAILING_NUMBER.exec(label)?.[1] ?? null) : null
}

/**
 * The id of the group a coroutine would be in (whether or not it has siblings to group with), or null when its name
 * has no trailing number. The same for every member and stable across refreshes, so expansion survives them.
 */
export function groupIdOf(c: CoroutineNode): string | null {
  const stem = nameStem(c.label)
  return stem === null ? null : `${GROUP_PREFIX}${c.parentId ?? ''}␟${c.roundId ?? ''}␟${stem}`
}

export function isGroupId(id: string): boolean {
  return id.startsWith(GROUP_PREFIX)
}

export interface NameGroup {
  id: string
  /** "order-*". */
  label: string
  parentId: string | null
  roundId: string | null | undefined
  /** In the view's order. */
  memberIds: string[]
  /** The members' states. */
  counts: Partial<Record<CoroutineState, number>>
  /** Coroutines below the members (left out while the group is collapsed). */
  below: number
  expanded: boolean
}

/** The state a group's synthetic node carries: the one that most needs attention among its members. */
const ATTENTION: CoroutineState[] = [
  CoroutineState.FAILED,
  CoroutineState.ACTIVE,
  CoroutineState.CREATED,
  CoroutineState.SUSPENDED,
  CoroutineState.WAITING_FOR_CHILDREN,
  CoroutineState.CANCELLED,
  CoroutineState.COMPLETED,
]

/** A group's states in words, the ones that most need attention first: "1 Failed · 2 Completed". */
export function groupMix(counts: Partial<Record<CoroutineState, number>>): string {
  return ATTENTION.filter(s => counts[s])
    .map(s => `${counts[s]!.toLocaleString('en-US')} ${stateLabel(s)}`)
    .join(' · ')
}

/**
 * Groups [coroutines] (the whole view, every state) and lists the nodes to draw. With [shown] (the filtered pool), only
 * shown coroutines are drawn, and a group's card while any of its members is shown: a member's state change never
 * dissolves its group, and the group's counts are always over all its members.
 */
export function groupSiblings(
  coroutines: readonly CoroutineNode[],
  expanded: ReadonlySet<string>,
  shown?: ReadonlySet<string>,
): { nodes: CoroutineNode[]; groups: Map<string, NameGroup> } {
  // Every pass below is linear: big apps have tens of thousands of siblings, and groups nest under groups.
  const byGroup = new Map<string, CoroutineNode[]>()
  for (const c of coroutines) {
    const id = groupIdOf(c)
    if (id === null) continue
    const list = byGroup.get(id)
    if (list) list.push(c)
    else byGroup.set(id, [c])
  }
  const groupOf = new Map<string, string>()
  for (const [id, members] of byGroup) if (members.length >= 2) for (const m of members) groupOf.set(m.id, id)

  const byId = new Map(coroutines.map(c => [c.id, c]))
  const known = new Set(byId.keys())
  const childrenOf = new Map<string, string[]>()
  const roots: string[] = []
  for (const c of coroutines) {
    if (!c.parentId || !known.has(c.parentId)) {
      roots.push(c.id)
      continue
    }
    const list = childrenOf.get(c.parentId)
    if (list) list.push(c.id)
    else childrenOf.set(c.parentId, [c.id])
  }
  // Top-down order (each node after its parent), once; a node reached twice (a cycle) is visited once.
  const order: string[] = []
  const seen = new Set<string>()
  const walk = (start: string) => {
    const stack = [start]
    while (stack.length > 0) {
      const id = stack.pop()!
      if (seen.has(id)) continue
      seen.add(id)
      order.push(id)
      const kids = childrenOf.get(id)
      if (kids) for (let i = kids.length - 1; i >= 0; i--) stack.push(kids[i]!)
    }
  }
  for (const r of roots) walk(r)
  // A cycle in the reported parents has no root: its coroutines are walked from where they first appear.
  for (const c of coroutines) if (!seen.has(c.id)) walk(c.id)
  // Bottom-up: subtree sizes, and whether a node or anything below it is shown.
  const size = new Map<string, number>()
  const shownBelow = new Set<string>()
  for (let i = order.length - 1; i >= 0; i--) {
    const id = order[i]!
    let n = 1
    let anyShown = !shown || shown.has(id)
    for (const k of childrenOf.get(id) ?? []) {
      n += size.get(k) ?? 1
      if (shownBelow.has(k)) anyShown = true
    }
    size.set(id, n)
    if (anyShown) shownBelow.add(id)
  }

  const groups = new Map<string, NameGroup>()
  const visible = new Set<string>()
  for (const [id, members] of byGroup) {
    if (members.length < 2) continue
    const first = members[0]!
    const counts: Partial<Record<CoroutineState, number>> = {}
    let below = 0
    for (const m of members) {
      counts[m.state] = (counts[m.state] ?? 0) + 1
      below += (size.get(m.id) ?? 1) - 1
    }
    groups.set(id, {
      id,
      label: `${nameStem(first.label)}*`,
      parentId: first.parentId,
      roundId: first.roundId,
      memberIds: members.map(m => m.id),
      counts,
      below,
      expanded: expanded.has(id),
    })
    // A card is drawn while a member, or anything below the members, is shown.
    if (members.some(m => shownBelow.has(m.id))) visible.add(id)
  }

  // Top-down: what a collapsed group leaves out — its members (its card stands for them) and everything below them.
  const hidden = new Set<string>()
  const hiddenBelow = new Set<string>()
  const parentOf = (id: string): string | undefined => {
    const p = byId.get(id)?.parentId
    return p && known.has(p) ? p : undefined
  }
  for (const id of order) {
    const parent = parentOf(id)
    if (parent !== undefined && hidden.has(parent)) {
      hidden.add(id)
      hiddenBelow.add(id)
      continue
    }
    const gid = groupOf.get(id)
    if (gid && !expanded.has(gid)) hidden.add(id)
  }

  const nodes: CoroutineNode[] = []
  const placed = new Set<string>()
  for (const c of coroutines) {
    const gid = groupOf.get(c.id)
    if (gid && !placed.has(gid) && !hiddenBelow.has(c.id) && visible.has(gid)) {
      placed.add(gid)
      const g = groups.get(gid)!
      nodes.push({
        id: gid,
        jobId: '',
        parentId: g.parentId,
        scopeId: c.scopeId,
        label: g.label,
        state: ATTENTION.find(st => g.counts[st]) ?? c.state,
        roundId: g.roundId,
        origin: c.origin,
      })
    }
    // A member of a collapsed group, or anything below one, is left out; so is what the chip does not show.
    if (hidden.has(c.id) || (shown && !shown.has(c.id))) continue
    nodes.push(gid ? { ...c, parentId: gid } : c)
  }
  return { nodes, groups }
}

/** Where a coroutine was created, as the hierarchy reports it (`creationPoint`). */
export interface CreationSite {
  function: string
  fileName?: string | null
  lineNumber?: number | null
}

function siteKey(site: CreationSite | null | undefined): string | null {
  if (!site?.fileName) return null
  return `${site.fileName}${site.lineNumber != null ? `:${site.lineNumber}` : ''} (${site.function})`
}

/**
 * Where a group's members were created (S-12, "the shared creation site if there is one"). "All created at …" only
 * when every current member's site is known and they are all the same; otherwise the known sites with their counts,
 * and the rest as unavailable (H-3).
 */
export function creationSiteText(memberIds: readonly string[], sites: ReadonlyMap<string, CreationSite | null>): string {
  const counts = new Map<string, number>()
  let unknown = 0
  for (const id of memberIds) {
    const key = siteKey(sites.get(id))
    if (key === null) unknown++
    else counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  if (counts.size === 0) return 'Creation site unavailable'
  if (counts.size === 1 && unknown === 0) return `All created at ${[...counts.keys()][0]}`
  const known = [...counts]
    .sort((a, b) => b[1] - a[1])
    .map(([key, n]) => `${key} for ${n.toLocaleString('en-US')}`)
    .join(', ')
  return unknown > 0 ? `At ${known}; unavailable for ${unknown.toLocaleString('en-US')}` : `At ${known}`
}

import { useMemo, useState } from 'react'
import { apiClient } from '@/lib/api-client'
import { mergeSummaryNodes } from '@/lib/cursor-coroutines'
import { hierarchyKey, readAt, type MomentRead } from '@/lib/moment-read'
import { hierarchyChangesBetween, type ReplayIndex } from '@/lib/replay-index'
import { useMomentQuery } from './use-moment-query'
import type { CoroutineNode } from '@/types/api'

export interface CursorCoroutinesArgs {
  /** The replay's read (session or shared), or null outside replay. */
  read: MomentRead | null
  /** The coroutines the page projects from the replayed events up to the cursor. */
  projected: CoroutineNode[]
  cursorSeq: number
  index: ReplayIndex
  /** The replay does not start at the session's beginning (M-8): only then is the server asked. */
  incomplete: boolean
  settleMs: number
  hold: boolean
}

/**
 * The replay cursor's coroutines (Part 8, M-9). When the replay does not start at the beginning, the coroutines it
 * never saw created come from the server's summaries at the cursor (`…/hierarchy?atSeq=`); otherwise, and while that
 * read is not valid at the cursor, the projection alone. `status` says which: `off`, `ready`, `updating`, `error`.
 */
export function useCursorCoroutines({
  read,
  projected,
  cursorSeq,
  index,
  incomplete,
  settleMs,
  hold,
}: CursorCoroutinesArgs): { coroutines: CoroutineNode[]; status: 'off' | 'ready' | 'updating' | 'error' } {
  const enabled = incomplete && !!read
  const query = useMomentQuery({
    enabled,
    identity: 'hierarchy',
    keyAt: seq => (read ? hierarchyKey(readAt(read, seq)) : ['no-moment']),
    fetchAt: seq => apiClient.getHierarchyAt(readAt(read!, seq)),
    cursorSeq,
    changesBetween: (a, b) => hierarchyChangesBetween(index, a, b),
    settleMs,
    hold,
  })
  const coroutines = useMemo(
    () => (enabled && query.data ? mergeSummaryNodes(projected, query.data) : projected),
    [enabled, query.data, projected],
  )
  return { coroutines, status: enabled ? query.status : 'off' }
}

/**
 * Every coroutine the server's summaries have given the tree since [resetKey] changed (a new replay), by id with its
 * name (Part 8, M-5): while a later read is updating, a selected one is still known, not "gone".
 */
export function useKnownSummaryNodes(coroutines: readonly CoroutineNode[], resetKey: unknown): ReadonlyMap<string, string> {
  const [known, setKnown] = useState<{ key: unknown; nodes: Map<string, string> }>(() => ({ key: resetKey, nodes: new Map() }))
  let current = known
  if (current.key !== resetKey) current = { key: resetKey, nodes: new Map() }
  const unseen = coroutines.filter(c => c.fromSummary && !current.nodes.has(c.id))
  if (unseen.length > 0) {
    const nodes = new Map(current.nodes)
    for (const c of unseen) nodes.set(c.id, c.label ?? c.id)
    current = { key: resetKey, nodes }
  }
  // Adjusting state while rendering, from props (React's documented pattern): no effect, no extra commit.
  if (current !== known) setKnown(current)
  return current.nodes
}

import type { VizEvent } from '@/types/api'

/**
 * What a replay's frozen events say about which reads can change between two seqs (Part 8, M-4). A read made at
 * one seq is still true at another only when the replay holds every seq in between (the live stream may have shed
 * events the server kept) and none of them concerns what was read.
 */
export interface ReplayIndex {
  /** Every replayed seq, ascending. */
  seqs: number[]
  /** Each coroutine's event seqs, ascending. */
  byCoroutine: Map<string, number[]>
}

export function indexReplay(events: readonly VizEvent[]): ReplayIndex {
  const seqs: number[] = []
  const byCoroutine = new Map<string, number[]>()
  for (const e of events) {
    seqs.push(e.seq)
    const id = (e as { coroutineId?: string }).coroutineId
    if (!id) continue
    const list = byCoroutine.get(id)
    if (list) list.push(e.seq)
    else byCoroutine.set(id, [e.seq])
  }
  return { seqs, byCoroutine }
}

/** The index of the first element greater than [value] (ascending [sorted]). */
function upperBound(sorted: readonly number[], value: number): number {
  let lo = 0
  let hi = sorted.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (sorted[mid]! <= value) lo = mid + 1
    else hi = mid
  }
  return lo
}

/** True when [sorted] holds a value in (lo, hi]. */
function anyIn(sorted: readonly number[] | undefined, lo: number, hi: number): boolean {
  if (!sorted || sorted.length === 0) return false
  const i = upperBound(sorted, lo)
  return i < sorted.length && sorted[i]! <= hi
}

/** True when the replay is missing a seq in (lo, hi]: the seqs it holds there are fewer than the range is wide. */
function gapIn(index: ReplayIndex, lo: number, hi: number): boolean {
  const held = upperBound(index.seqs, hi) - upperBound(index.seqs, lo)
  return held !== hi - lo
}

/** Whether coroutine [id]'s timeline could differ between seqs [a] and [b]. */
export function coroutineChangesBetween(index: ReplayIndex, id: string, a: number, b: number): boolean {
  const lo = Math.min(a, b)
  const hi = Math.max(a, b)
  if (lo === hi) return false
  return gapIn(index, lo, hi) || anyIn(index.byCoroutine.get(id), lo, hi)
}

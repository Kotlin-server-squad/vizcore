import { useEffect, useState } from 'react'
import { keepPreviousData, useQuery } from '@tanstack/react-query'

export interface MomentQueryArgs<T> {
  enabled: boolean
  /** What is read (a coroutine's timeline, the hierarchy): a read of one identity never stands for another's. */
  identity: string
  /** The query key of the read at [seq]. */
  keyAt: (seq: number) => readonly unknown[]
  fetchAt: (seq: number) => Promise<T>
  /** The seq the page shows. */
  cursorSeq: number
  /**
   * True when the read could differ between seqs [a] and [b]: a replayed event that concerns it has a seq in
   * (min, max]. Where it is false, the read at one seq is the read at the other.
   */
  changesBetween: (a: number, b: number) => boolean
  /** How long the cursor must stay at a seq before it is read. */
  settleMs: number
  /** Start no read (a shared replay while it plays: the shared bucket is small). */
  hold?: boolean
}

export interface MomentQueryResult<T> {
  /** The read, valid at the cursor; undefined while there is none. */
  data: T | undefined
  /** The seq [data] was read at (it may differ from the cursor only where nothing about it changed). */
  dataSeq: number | null
  status: 'ready' | 'updating' | 'error'
  error: unknown
  retry: () => void
}

interface Stamped<T> {
  /** The key family the read belongs to (its key without the seq): two users of one key share its reads. */
  family: string
  seq: number
  value: T
}

/**
 * A read bound to the page's moment (Part 8, M-4). It is shown at the cursor only when nothing about it changed between
 * the seq it was read at and the cursor, so it never shows another moment; moving across unrelated events costs no
 * request. Otherwise it says "updating" and reads the cursor once it has stayed there [settleMs] (and is not held).
 */
export function useMomentQuery<T>({
  enabled,
  identity,
  keyAt,
  fetchAt,
  cursorSeq,
  changesBetween,
  settleMs,
  hold = false,
}: MomentQueryArgs<T>): MomentQueryResult<T> {
  const [target, setTarget] = useState({ identity, seq: cursorSeq })
  // A new identity is read at the cursor at once: nothing of the old one may stand for it. While disabled the target
  // follows the cursor, so a read enabled later starts at the cursor, never at an old seq.
  if (target.identity !== identity || (!enabled && target.seq !== cursorSeq)) setTarget({ identity, seq: cursorSeq })

  // What the read is, independent of its seq: a kept-previous read of another coroutine never counts as this one.
  const family = JSON.stringify(keyAt(-1))
  const query = useQuery({
    queryKey: keyAt(target.seq),
    queryFn: async (): Promise<Stamped<T>> => ({ family, seq: target.seq, value: await fetchAt(target.seq) }),
    // Held (a shared replay that plays): no request at all, not even a first one.
    enabled: enabled && !hold && target.identity === identity,
    // A read at a seq describes that moment, which does not change.
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  })

  const stamped = query.data
  const valid =
    !!stamped &&
    stamped.family === family &&
    (stamped.seq === cursorSeq || !changesBetween(stamped.seq, cursorSeq))

  useEffect(() => {
    if (!enabled || hold || valid || target.seq === cursorSeq) return
    const timer = setTimeout(() => setTarget({ identity, seq: cursorSeq }), settleMs)
    return () => clearTimeout(timer)
  }, [enabled, hold, valid, target.seq, cursorSeq, identity, settleMs])

  const failed = query.isError && !query.isFetching && target.seq === cursorSeq
  return {
    data: valid ? stamped!.value : undefined,
    dataSeq: valid ? stamped!.seq : null,
    status: valid ? 'ready' : failed ? 'error' : 'updating',
    error: failed ? query.error : null,
    retry: () => void query.refetch(),
  }
}

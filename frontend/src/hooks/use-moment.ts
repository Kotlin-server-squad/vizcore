import { createContext, useContext } from 'react'
import type { MomentRead } from '@/lib/moment-read'
import type { Moment } from '@/components/workspace/MomentNote'

/**
 * The moment the Inspector's reads are bound to (Part 8, M-3, M-4), outside the live view. The live view provides none
 * and every read behaves exactly as before.
 */
export interface MomentView {
  /** How reads are bound: the replay cursor, or a shared link's loaded cut. */
  read: MomentRead
  /** The seq the page shows. */
  cursorSeq: number
  /** Whether coroutine [id]'s timeline could differ between seqs [a] and [b] (`replay-index`). */
  changesBetween: (id: string, a: number, b: number) => boolean
  /** How long the cursor must stay put before a read: 300 ms for session reads, 2 s for shared reads. */
  settleMs: number
  /** Start no read now: a shared replay while it plays. */
  hold: boolean
  /** What the "at this moment" marker says. */
  moment: Moment
}

export const MomentContext = createContext<MomentView | null>(null)

/** The moment the reads below are bound to, or null in the live view. */
export function useMomentView(): MomentView | null {
  return useContext(MomentContext)
}

/**
 * "Some intervals may be missing" (S-10; plan T-9): when events were lost or trimmed, the timeline says so instead of
 * drawing gaps it cannot locate.
 */
export interface MissingFacts {
  /** Events the server shed under load, as the stream reported them (`droppedCount`). */
  droppedCount: number
  /** The first event's seq: after 1, the history before it is gone (trimmed, the live buffer, Clear events). */
  firstSeq: number | null
  /** Rows whose creation is not in the events. */
  createdBefore: number
}

const plural = (n: number, one: string, many: string) => (n === 1 ? `1 ${one}` : `${n.toLocaleString('en-US')} ${many}`)

export function missingNote({ droppedCount, firstSeq, createdBefore }: MissingFacts): string | null {
  const reasons: string[] = []
  if (droppedCount > 0) reasons.push(`${plural(droppedCount, 'event was', 'events were')} dropped under load`)
  const trimmed = firstSeq !== null && firstSeq > 1
  if (trimmed) reasons.push(`the events start at event ${firstSeq.toLocaleString('en-US')}`)
  if (createdBefore > 0) {
    reasons.push(`${plural(createdBefore, 'coroutine was', 'coroutines were')} created before ${trimmed ? 'them' : 'the events'}`)
  }
  return reasons.length > 0 ? `Some intervals may be missing: ${reasons.join('; ')}.` : null
}

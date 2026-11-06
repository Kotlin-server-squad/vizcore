/**
 * Look B's sentence under the session title (sketch K-B-Calm: "Running for 6.0 s. 3 orders are waiting: 2 for
 * the stock lock, 1 for a payment slot."), worded only from what the page already knows. Where it knows less,
 * it says less. The waits are the ones the cards' second lines show (the same projection over the same
 * events), so the sentence never disagrees with the tree. Also: an attached app has no run outcome, a suspension without a wrapper wait is not claimed as a
 * wait, and a resource without a name is "a lock", never its id.
 */
import { formatDuration } from '@/lib/format-duration'
import type { RunOutcomeFacts } from '@/lib/run-outcome'
import type { WaitKind } from '@/types/api'

export interface SummaryFacts {
  /** A demo run's outcome; null for an attached app. */
  outcome: RunOutcomeFacts | null
  /** From the first to the last event the page holds. */
  runSpanNanos: number | null
  /** Events were dropped, so the span is a floor. */
  spanIsFloor: boolean
  replayActive: boolean
  /** Where the replay cursor is, from the first event. */
  replayAtNanos: number | null
  /** The waits the lock, permit and channel wrappers recorded (projectWaits), in the order they started. */
  waits: Iterable<{ waitKind: WaitKind; resourceId: string | null }>
  /** Resource id -> its name, as the events give it. */
  names: ReadonlyMap<string, string>
}

/** How many groups of waits are named before the rest become "other things". */
const NAMED_GROUPS = 3

export function summarise(facts: SummaryFacts): string | null {
  // A run that has ended waits for nothing: a wait left over from a lost resume is not repeated here.
  const ended = !facts.replayActive && facts.outcome?.ended === true
  const sentences = [howItGoes(facts), ended ? null : whatWaits(facts)].filter((s): s is string => s !== null)
  return sentences.length > 0 ? sentences.join(' ') : null
}

const coroutines = (n: number) => `${n} ${n === 1 ? 'coroutine' : 'coroutines'}`

function howItGoes({ outcome, runSpanNanos, spanIsFloor, replayActive, replayAtNanos }: SummaryFacts): string | null {
  // In replay the page shows the cursor's moment; the live outcome would contradict it.
  if (replayActive) return replayAtNanos !== null ? `Replay at ${formatDuration(replayAtNanos)}.` : 'Replay.'
  if (outcome === null) return null
  const span = runSpanNanos !== null && runSpanNanos > 0 ? formatDuration(runSpanNanos, { atLeast: spanIsFloor }) : null
  switch (outcome.outcome) {
    case 'notStarted':
      return 'Not run yet.'
    case 'running':
      // While it runs, the first-to-last-event span is only a floor: the run goes on between events.
      return span !== null ? `Running for at least ${formatDuration(runSpanNanos!)}.` : 'Running.'
    // Once it has ended, the span is of the events the page holds (a bounded store may have trimmed the
    // first ones), so it is named as such rather than as the run's length.
    case 'finished':
      return span !== null ? `Finished. Its recorded events span ${span}.` : 'Finished.'
    case 'failed': {
      const failed = `${coroutines(outcome.failedCount)} failed`
      if (!outcome.ended) return `${failed}, and the run is still going.`
      return span !== null ? `${failed}. The run has ended; its recorded events span ${span}.` : `${failed}. The run has ended.`
    }
  }
}

/** "a payment-gateway permit", "an inventory permit". */
const aPermit = (name: string) => `${/^[aeiou]/i.test(name) ? 'an' : 'a'} ${name} permit`

/** What one wait is for, with the resource's name or a generic word — never its id. */
function waitsFor(kind: WaitKind, name: string | null): string {
  switch (kind) {
    case 'MUTEX':
      return name !== null ? `for ${name}` : 'for a lock'
    case 'SEMAPHORE':
      return name !== null ? `for ${aPermit(name)}` : 'for a permit'
    case 'CHANNEL_SEND':
      return name !== null ? `to send to ${name}` : 'to send'
    case 'CHANNEL_RECEIVE':
      return name !== null ? `to receive from ${name}` : 'to receive'
    default:
      return 'for something'
  }
}

function whatWaits({ waits, names }: SummaryFacts): string | null {
  // Grouped by the words, so two unnamed locks are one group; a Map keeps the order they started in.
  const groups = new Map<string, number>()
  let total = 0
  for (const w of waits) {
    const name = w.resourceId !== null ? (names.get(w.resourceId) ?? null) : null
    const key = waitsFor(w.waitKind, name)
    groups.set(key, (groups.get(key) ?? 0) + 1)
    total += 1
  }
  if (total === 0) return null
  // Largest first; a stable sort keeps the starting order between equals.
  const sorted = [...groups].sort((a, b) => b[1] - a[1])
  if (sorted.length === 1) return `${coroutines(total)} ${total === 1 ? 'is' : 'are'} waiting ${sorted[0]![0]}.`
  const named = sorted.slice(0, NAMED_GROUPS).map(([words, n]) => `${n} ${words}`)
  const rest = sorted.slice(NAMED_GROUPS).reduce((sum, [, n]) => sum + n, 0)
  const parts = rest > 0 ? `${named.join(', ')}, and ${rest} for other things` : named.join(', ')
  return `${coroutines(total)} are waiting: ${parts}.`
}

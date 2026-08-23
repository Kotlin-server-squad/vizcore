import { CoroutineState, type CoroutineNode } from '@/types/api'

/**
 * What the state bar can filter by. `all` is the resting state — the workspace
 * behaves exactly as it did before the bar existed.
 */
export type StateFilter = 'all' | 'running' | 'suspended' | 'completed' | 'cancelled' | 'failed'

export interface StateCounts {
  running: number
  suspended: number
  completed: number
  cancelled: number
  failed: number
  /** Every coroutine in the snapshot. The five buckets always sum to this. */
  total: number
}

/**
 * Which bucket each coroutine state belongs to.
 *
 * Two calls worth stating out loud:
 *
 * `WAITING_FOR_CHILDREN` is **running**. Structurally the parent is still live
 * work — it has finished its own body but its scope is not done. Filing it
 * anywhere else makes a healthy structured-concurrency tree look half-dead.
 *
 * `CANCELLED` gets its **own** bucket. Folding it into completed would report a
 * cancellation as success; folding it into failed would report it as an error.
 * It is neither.
 */
const STATE_BUCKET = {
  [CoroutineState.CREATED]: 'running',
  [CoroutineState.ACTIVE]: 'running',
  [CoroutineState.WAITING_FOR_CHILDREN]: 'running',
  [CoroutineState.SUSPENDED]: 'suspended',
  [CoroutineState.COMPLETED]: 'completed',
  [CoroutineState.CANCELLED]: 'cancelled',
  [CoroutineState.FAILED]: 'failed',
} as const satisfies Record<CoroutineState, Exclude<StateFilter, 'all'>>

/** Pure reducer over a coroutine snapshot. Safe inside a useMemo. */
export function deriveStateCounts(coroutines: readonly CoroutineNode[]): StateCounts {
  const counts: StateCounts = {
    running: 0,
    suspended: 0,
    completed: 0,
    cancelled: 0,
    failed: 0,
    total: 0,
  }
  for (const c of coroutines) {
    const bucket = STATE_BUCKET[c.state as CoroutineState]
    // An unrecognised state must not vanish from the bar; count it as running
    // so the buckets still sum to the total.
    counts[bucket ?? 'running'] += 1
    counts.total += 1
  }
  return counts
}

/**
 * The single definition of what a chip selects, shared by the bar and the
 * canvas so the two cannot disagree about what "Running" means.
 */
export function matchesFilter(state: CoroutineState, filter: StateFilter): boolean {
  if (filter === 'all') return true
  return (STATE_BUCKET[state] ?? 'running') === filter
}

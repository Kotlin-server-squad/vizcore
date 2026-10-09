import { CoroutineState, type CoroutineNode } from '@/types/api'

/**
 * What the state bar can filter by. `all` is the resting state — the workspace
 * behaves exactly as it did before the bar existed.
 */
export type StateFilter =
  | 'all'
  | 'running'
  | 'suspended'
  | 'completed'
  | 'cancelled'
  | 'failed'
  /** Cross-cutting: a still-active coroutine alive past the leak threshold. */
  | 'leaks'

export interface StateCounts {
  running: number
  suspended: number
  completed: number
  cancelled: number
  failed: number
  /** Every coroutine in the snapshot. The five state buckets always sum to this. */
  total: number
  /**
   * Potential leaks. NOT one of the five buckets and not part of `total` — a
   * leak is a heuristic flag on a still-active coroutine, so a leaked coroutine
   * is also counted under `running`.
   */
  leaks: number
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
export function deriveStateCounts(
  coroutines: readonly CoroutineNode[],
  leakIds?: ReadonlySet<string>,
): StateCounts {
  const counts: StateCounts = {
    running: 0,
    suspended: 0,
    completed: 0,
    cancelled: 0,
    failed: 0,
    total: 0,
    leaks: leakIds?.size ?? 0,
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
  // A leak is a property of the coroutine, not of its state — `selectCoroutines`
  // resolves it against the leak id set instead.
  if (filter === 'leaks') return false
  return (STATE_BUCKET[state] ?? 'running') === filter
}

/**
 * What the canvas shows for a given chip.
 *
 * The single place the two kinds of filter meet: lifecycle states resolve
 * through `matchesFilter`, and the cross-cutting leak flag resolves against the
 * leak id set. Keeping both here stops the bar and the canvas disagreeing about
 * what a chip means.
 */
export function selectCoroutines(
  coroutines: readonly CoroutineNode[],
  filter: StateFilter,
  leakIds?: ReadonlySet<string>,
): CoroutineNode[] {
  if (filter === 'all') return [...coroutines]
  if (filter === 'leaks') {
    return leakIds ? coroutines.filter(c => leakIds.has(c.id)) : []
  }
  return coroutines.filter(c => matchesFilter(c.state as CoroutineState, filter))
}

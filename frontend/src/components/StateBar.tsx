import type { StateCounts, StateFilter } from '@/lib/state-counts'

/**
 * The workspace state bar (D-4).
 *
 * One row that says what the session is doing, and doubles as the filter for
 * the canvas below it. Deliberately a *state* bar rather than a problems strip:
 * a problems-only strip opens on a row of zeroes for a demo session, and demo
 * is the rung the SPA uniquely serves. Problems are not a separate surface —
 * they are the chips that carry an alarming colour when non-zero.
 *
 * Presentational only: it fetches nothing and derives nothing.
 */

interface Chip {
  filter: Exclude<StateFilter, 'all'>
  label: string
  count: number
  /** Token-layer semantic colour. */
  color: string
  /** Shown even at zero, so the bar never renders empty. */
  always?: boolean
}

export function StateBar({
  counts,
  filter,
  onFilterChange,
}: {
  counts: StateCounts
  filter: StateFilter
  onFilterChange: (next: StateFilter) => void
}) {
  const chips: Chip[] = [
    { filter: 'running', label: 'Running', count: counts.running, color: 'var(--primary)', always: true },
    { filter: 'suspended', label: 'Suspended', count: counts.suspended, color: 'var(--warning)' },
    { filter: 'completed', label: 'Completed', count: counts.completed, color: 'var(--success)' },
    { filter: 'cancelled', label: 'Cancelled', count: counts.cancelled, color: 'var(--warning)' },
    { filter: 'failed', label: 'Failed', count: counts.failed, color: 'var(--danger)' },
  ]

  // A potential leak is a heuristic finding, not a lifecycle fact, so it sits
  // after the state chips and is amber — never danger-red, which is reserved
  // for coroutines that actually failed.
  const leakChip: Chip[] = [
    {
      filter: 'leaks',
      label: 'Potential leaks',
      count: counts.leaks,
      color: 'var(--warning)',
    },
  ]

  return (
    <div
      className="flex flex-wrap items-center gap-2 rounded-medium border border-default-200 bg-content1 px-3 py-2"
      role="group"
      aria-label="Coroutine state"
    >
      {[...chips, ...leakChip]
        .filter((chip) => chip.always || chip.count > 0)
        .map((chip) => {
          const active = filter === chip.filter
          return (
            <button
              key={chip.filter}
              type="button"
              aria-pressed={active}
              // Explicit: the adjacent label/count spans would otherwise compute
              // an accessible name of "Running47".
              aria-label={`${chip.label} ${chip.count}`}
              // Clicking the active chip clears the filter, so the bar is a
              // toggle rather than a one-way trip that needs a separate reset.
              onClick={() => onFilterChange(active ? 'all' : chip.filter)}
              className="flex items-center gap-2 rounded-full border px-3 py-1 text-sm transition-colors"
              style={{
                borderColor: active
                  ? `color-mix(in srgb, ${chip.color} 45%, transparent)`
                  : 'var(--border-2)',
                backgroundColor: active
                  ? `color-mix(in srgb, ${chip.color} 16%, transparent)`
                  : 'transparent',
                color: active ? chip.color : 'var(--text-muted)',
              }}
            >
              <span
                aria-hidden="true"
                className="h-2 w-2 shrink-0 rounded-full"
                style={{ backgroundColor: chip.color }}
              />
              <span>{chip.label}</span>
              <span className="font-mono tabular-nums font-semibold">{chip.count}</span>
            </button>
          )
        })}
    </div>
  )
}

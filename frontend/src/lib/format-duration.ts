/**
 * Lifetimes in words (triage: "2.5 s", "12 h 46 m", "so far", "at least").
 *
 * Two units at most, rounded once at the smallest unit shown, so 59.96 s
 * reads "1 m 0 s" and never "60.0 s". Under a minute the seconds keep one
 * decimal, because the demos' steps are a few hundred milliseconds apart.
 * A value that cannot be known reads "unavailable" (H-3), never "0".
 */
export interface DurationOptions {
  /** Still running: the figure grows ("2.5 s so far"). */
  soFar?: boolean
  /** Events were lost or trimmed: the true figure may be larger. */
  atLeast?: boolean
}

const NANOS_PER_MS = 1_000_000
const MS_PER_S = 1_000

export function formatDuration(nanos: number, options: DurationOptions = {}): string {
  if (!Number.isFinite(nanos)) return 'unavailable'
  const text = words(Math.max(0, nanos) / NANOS_PER_MS)
  const withFloor = options.atLeast ? `at least ${text}` : text
  return options.soFar ? `${withFloor} so far` : withFloor
}

export function formatDurationMs(ms: number, options: DurationOptions = {}): string {
  return formatDuration(ms * NANOS_PER_MS, options)
}

function words(ms: number): string {
  if (ms === 0) return '0 ms'
  if (ms < 0.1) return 'under 0.1 ms'
  if (ms < 10) return `${ms.toFixed(1)} ms`
  if (Math.round(ms) < MS_PER_S) return `${Math.round(ms)} ms`

  const tenths = Math.round(ms / 100)
  if (tenths < 600) return `${(tenths / 10).toFixed(1)} s`

  const seconds = Math.round(ms / MS_PER_S)
  if (seconds < 3_600) return `${Math.floor(seconds / 60)} m ${seconds % 60} s`

  const minutes = Math.round(ms / 60_000)
  if (minutes < 1_440) return `${Math.floor(minutes / 60)} h ${minutes % 60} m`

  const hours = Math.round(ms / 3_600_000)
  return `${Math.floor(hours / 24)} d ${hours % 24} h`
}

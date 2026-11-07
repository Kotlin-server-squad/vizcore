import { LOOKS, type LookId } from '../registry'
import type { Entry } from './assign'
import { EXPERIMENT_STORAGE_KEY } from './constants'

type PerLook<T> = Partial<Record<LookId, T>>

/** Everything the experiment remembers in the browser. Nothing here identifies the person. */
export interface ExperimentState {
  /** A random UUID this browser made for itself: the only id the server sees. */
  anonId: string
  /** How this browser joined (sent once). */
  entry?: Entry
  /** The look the experiment gave it (not a choice: shown only while the experiment runs). */
  assigned?: LookId
  hintDismissed?: boolean
  /** The rating this browser gave each look. */
  voted: PerLook<number>
  /** Looks whose vote card was closed (it no longer offers itself there; the menu still opens it). */
  dismissedVote: PerLook<true>
  /** Page loads per look, for "a later visit". */
  visits: PerLook<number>
  /** Visible time per look, summed over visits, for the vote card's timing. */
  visibleMs: PerLook<number>
}

let cached: ExperimentState | null = null
/** Storage refused a read or a write: the state then lives in memory for this visit. */
let inMemoryOnly = false

/** Test hook: read storage again. */
export function forgetExperimentState() {
  cached = null
  inMemoryOnly = false
}

const isLook = (value: unknown): value is LookId => LOOKS.some(l => l.id === value)
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function newAnonId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  // Not a secure context (plain http on a LAN address): build a v4 UUID by hand.
  const b = crypto.getRandomValues(new Uint8Array(16))
  b[6] = (b[6]! & 0x0f) | 0x40
  b[8] = (b[8]! & 0x3f) | 0x80
  const hex = Array.from(b, x => x.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function perLook<T>(raw: unknown, valid: (v: unknown) => v is T): PerLook<T> {
  const out: PerLook<T> = {}
  if (raw && typeof raw === 'object') {
    for (const [k, v] of Object.entries(raw)) if (isLook(k) && valid(v)) out[k] = v
  }
  return out
}

const isRating = (v: unknown): v is number =>
  Number.isInteger(v) && (v as number) >= 1 && (v as number) <= 5
const isCount = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && v >= 0
const isTrue = (v: unknown): v is true => v === true

function parse(raw: string | null, knownId?: string): ExperimentState {
  let value: Record<string, unknown> = {}
  try {
    const parsed: unknown = raw ? JSON.parse(raw) : {}
    if (parsed && typeof parsed === 'object') value = parsed as Record<string, unknown>
  } catch {
    // Garbage: start afresh.
  }
  const entry = value.entry as Partial<Entry> | undefined
  return {
    anonId:
      typeof value.anonId === 'string' && UUID.test(value.anonId)
        ? value.anonId
        : (knownId ?? newAnonId()),
    entry:
      entry && isLook(entry.look) && (entry.kind === 'assigned' || entry.kind === 'chose')
        ? { look: entry.look, kind: entry.kind }
        : undefined,
    assigned: isLook(value.assigned) ? value.assigned : undefined,
    hintDismissed: value.hintDismissed === true ? true : undefined,
    voted: perLook(value.voted, isRating),
    dismissedVote: perLook(value.dismissedVote, isTrue),
    visits: perLook(value.visits, isCount),
    visibleMs: perLook(value.visibleMs, isCount),
  }
}

function write(state: ExperimentState) {
  try {
    localStorage.setItem(EXPERIMENT_STORAGE_KEY, JSON.stringify(state))
  } catch {
    // Storage blocked or full: the state still holds for this visit.
    inMemoryOnly = true
  }
}

/**
 * The browser's state, made (with a new anonymous id) the first time. Read
 * afresh from storage each time, so a tab never writes back a stale copy over
 * what another tab saved (a vote, a closed hint).
 */
export function readExperimentState(): ExperimentState {
  if (inMemoryOnly && cached) return cached
  let raw: string | null = null
  try {
    raw = localStorage.getItem(EXPERIMENT_STORAGE_KEY)
  } catch {
    // Storage blocked: keep the state in memory for this visit.
    inMemoryOnly = true
    if (cached) return cached
  }
  cached = parse(raw, cached?.anonId)
  // Keep a newly made id at once, so every request of this browser carries the same one.
  if (raw === null || !raw.includes(cached.anonId)) write(cached)
  return cached
}

export function updateExperimentState(
  change: (state: ExperimentState) => ExperimentState
): ExperimentState {
  cached = change(readExperimentState())
  write(cached)
  return cached
}

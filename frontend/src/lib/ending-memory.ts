/**
 * Which ending a two-ending demo session plays (demo-variants), remembered per
 * browser: the session list links to a session without `?variant=`, and the
 * server does not keep the ending. Reopened there, the header still names the
 * ending and Run again plays the same one.
 */
export const ENDINGS_KEY = 'vizcore.endings'
const KEEP = 50
/** Stored for the usual ending, which has no variant id. */
const USUAL = ''

function read(): Array<[string, string]> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(ENDINGS_KEY) ?? '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (e): e is [string, string] => Array.isArray(e) && typeof e[0] === 'string' && typeof e[1] === 'string',
    )
  } catch {
    return []
  }
}

/** The variant id the session plays, null for the usual ending, undefined when this browser does not know. */
export function rememberedEnding(sessionId: string): string | null | undefined {
  const found = read().find(([id]) => id === sessionId)
  return found === undefined ? undefined : found[1] === USUAL ? null : found[1]
}

/** Most recent first; the oldest sessions fall off after [KEEP]. */
export function rememberEnding(sessionId: string, variantId: string | null): void {
  const next = [[sessionId, variantId ?? USUAL], ...read().filter(([id]) => id !== sessionId)].slice(0, KEEP)
  try {
    localStorage.setItem(ENDINGS_KEY, JSON.stringify(next))
  } catch {
    // Private mode or storage full: the address still carries the ending for this visit.
  }
}

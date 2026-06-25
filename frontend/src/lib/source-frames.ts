/**
 * Frame classifier — user code vs library code.
 *
 * Used by the Delta S1 jump-to-code affordance (Phase 08.1): user frames render
 * bold + accent and are clickable jump targets; library frames render dimmed and
 * inert. The classification is a plain literal-prefix match (no regex / template
 * construction) over a frame's fully-qualified function string.
 *
 * Library = the coroutine runtime + Kotlin/JDK stdlib, which are noise in a stack
 * trace and never a jump target. Everything else is user code.
 */

const LIBRARY_PREFIXES = [
  'kotlinx.coroutines',
  'kotlin.coroutines',
  'kotlin.',
  'java.',
  'jdk.',
  'sun.',
] as const

/**
 * True when `fn` belongs to the coroutine runtime or the Kotlin/JDK stdlib.
 * Null/empty/undefined is treated as library so it is never a jump target.
 */
export function isLibraryFrame(fn: string | null | undefined): boolean {
  if (!fn) return true
  return LIBRARY_PREFIXES.some((prefix) => fn.startsWith(prefix))
}

/** Strict negation of {@link isLibraryFrame}: true only for user-code frames. */
export function isUserFrame(fn: string | null | undefined): boolean {
  return !isLibraryFrame(fn)
}

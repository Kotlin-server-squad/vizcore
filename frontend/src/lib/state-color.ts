import { CoroutineState } from '@/types/api'
import { palette } from '@/styles/palette'

/**
 * The only sanctioned mapping from coroutine state to colour.
 *
 * `WAITING_FOR_CHILDREN` is primary rather than a neutral: structurally the
 * parent is still live work, and dimming it makes a healthy structured-
 * concurrency tree look half-dead.
 */
const STATE_TO_KEY = {
  [CoroutineState.CREATED]: 'created',
  [CoroutineState.ACTIVE]: 'primary',
  [CoroutineState.SUSPENDED]: 'warning',
  [CoroutineState.WAITING_FOR_CHILDREN]: 'primary',
  [CoroutineState.COMPLETED]: 'success',
  [CoroutineState.CANCELLED]: 'warning',
  [CoroutineState.FAILED]: 'danger',
} as const satisfies Record<CoroutineState, keyof typeof palette>

/** Resolved hex, for canvas, SVG and inline styles. */
export function stateColor(state: CoroutineState): string {
  return palette[STATE_TO_KEY[state]]
}

/** CSS variable reference, for stylesheets and the `--c` badge pattern. */
export function stateToken(state: CoroutineState): string {
  return `var(--${STATE_TO_KEY[state]})`
}

/**
 * A *potential* leak is a heuristic finding, not a failure. It is always
 * warning/amber — reserving danger/red for coroutines that actually failed.
 */
export function leakColor(): string {
  return palette.warning
}

/**
 * The single definition of what a coroutine state looks like.
 *
 * Colour assignments, and why each one:
 *   CREATED              → neutral  (not yet started)
 *   ACTIVE               → primary  (running)
 *   WAITING_FOR_CHILDREN → primary  (also running — the body has finished but
 *                                    the scope has not, so this is still live
 *                                    work; told apart from ACTIVE by its clock
 *                                    icon and its own animation, not by hue)
 *   SUSPENDED            → warning  (paused, waiting on something external)
 *   CANCELLED            → warning  (stopped deliberately — neither success
 *                                    nor error; dimmed, but not neutral)
 *   COMPLETED            → success  (finished)
 *   FAILED               → danger   (error, attention needed)
 *
 * This map must agree with `STATE_BUCKET` in `state-counts.ts`, which decides
 * which chip a coroutine is counted under. When they disagreed, clicking the
 * amber "Cancelled" chip filtered the canvas to coroutines the canvas then drew
 * grey. `coroutine-state-colors.test.ts` asserts the pairing so it cannot
 * regress.
 *
 * Every component that needs state-dependent visuals should import from here
 * instead of maintaining its own switch/case mapping — and `stateColor()` is
 * the accessor for canvas/SVG, which need resolved hex rather than classes.
 */
import type { ComponentType } from 'react'
import { palette } from '@/styles/palette'
import {
  FiCircle,
  FiPlay,
  FiPause,
  FiClock,
  FiCheckCircle,
  FiXCircle,
  FiAlertCircle,
} from 'react-icons/fi'

// ---- Types ----------------------------------------------------------------

export type StateAnimation =
  | 'none'
  | 'pulse-fast'
  | 'pulse-slow'
  | 'pulse-medium'
  | 'fade-once'
  | 'dim'
  | 'shake'

export interface StateColorConfig {
  /** HeroUI semantic chip color */
  chipColor: 'default' | 'primary' | 'secondary' | 'success' | 'warning' | 'danger'
  /**
   * Resolved palette hex for this state. Canvas and SVG cannot consume Tailwind
   * class names, and this lives in the same record as the classes so the two
   * cannot drift apart.
   */
  hue: string
  /** Tailwind text-* class */
  text: string
  /** Tailwind bg-* class for icon circle backgrounds */
  iconBg: string
  /** Tailwind border-* class for card outlines */
  border: string
  /** Tailwind bg-* class for tree connector lines */
  line: string
  /** Background tint overlay class (e.g. bg-primary/5) */
  bgTint: string
  /** Which animation style to apply */
  animation: StateAnimation
  /** Default icon component for this state */
  Icon: ComponentType<{ className?: string }>
}

// ---- Color map ------------------------------------------------------------

const stateColorMap: Record<string, StateColorConfig> = {
  CREATED: {
    chipColor: 'default',
    hue: palette.created,
    text: 'text-default-400',
    iconBg: 'bg-default-100',
    border: 'border-default-300',
    line: 'bg-default-300',
    bgTint: 'bg-default-50',
    animation: 'none',
    Icon: FiCircle,
  },
  ACTIVE: {
    chipColor: 'primary',
    hue: palette.primary,
    text: 'text-primary',
    iconBg: 'bg-primary/10',
    border: 'border-primary',
    line: 'bg-primary/40',
    bgTint: 'bg-primary/5',
    animation: 'pulse-fast',
    Icon: FiPlay,
  },
  SUSPENDED: {
    chipColor: 'warning',
    hue: palette.warning,
    text: 'text-warning',
    iconBg: 'bg-warning/10',
    border: 'border-warning',
    line: 'bg-warning/40',
    bgTint: 'bg-warning/5',
    animation: 'pulse-slow',
    Icon: FiPause,
  },
  // Running, like ACTIVE — but with its own icon and a slower pulse, so the
  // state stays readable without needing a hue the palette does not define.
  WAITING_FOR_CHILDREN: {
    chipColor: 'primary',
    hue: palette.primary,
    text: 'text-primary',
    iconBg: 'bg-primary/10',
    border: 'border-primary/60',
    line: 'bg-primary/30',
    bgTint: 'bg-primary/5',
    animation: 'pulse-medium',
    Icon: FiClock,
  },
  COMPLETED: {
    chipColor: 'success',
    hue: palette.success,
    text: 'text-success',
    iconBg: 'bg-success/10',
    border: 'border-success',
    line: 'bg-success/40',
    bgTint: 'bg-success/5',
    animation: 'fade-once',
    Icon: FiCheckCircle,
  },
  // Amber, not grey: a cancellation is neither success nor error, and the state
  // bar has drawn it amber since the bar existed. Still dimmed — deliberately
  // stopped work should not compete with work that is running.
  CANCELLED: {
    chipColor: 'warning',
    hue: palette.warning,
    text: 'text-warning',
    iconBg: 'bg-warning/10',
    border: 'border-warning/60',
    line: 'bg-warning/30',
    bgTint: 'bg-warning/5',
    animation: 'dim',
    Icon: FiXCircle,
  },
  FAILED: {
    chipColor: 'danger',
    hue: palette.danger,
    text: 'text-danger',
    iconBg: 'bg-danger/10',
    border: 'border-danger',
    line: 'bg-danger/40',
    bgTint: 'bg-danger/5',
    animation: 'shake',
    Icon: FiAlertCircle,
  },
}

const defaultColors: StateColorConfig = stateColorMap.CREATED!

// ---- Public API -----------------------------------------------------------

/** Get the full color config for a coroutine state string. */
export function getStateColors(state: string): StateColorConfig {
  return stateColorMap[state] ?? defaultColors
}

/**
 * The resolved palette hex for a state — what canvas and SVG need, since
 * neither can consume a Tailwind class name.
 *
 * Falls back to the neutral CREATED hue for an unrecognised state: this is
 * called per node during render, so a state the backend adds later must
 * degrade to a colour, never to a crash.
 */
export function stateColor(state: string): string {
  return getStateColors(state).hue
}

/** True for states where the coroutine's Job is still active. */
export function isActiveState(state: string): boolean {
  return state === 'ACTIVE' || state === 'WAITING_FOR_CHILDREN'
}


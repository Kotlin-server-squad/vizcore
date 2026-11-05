/**
 * The single source of truth for vizcore colour.
 *
 * Values are taken verbatim from the validated sketch theme at
 * `.planning/sketches/themes/default.css`. Three consumers read this module:
 * `tokens.css` (CSS custom properties), `tailwind.config.ts` (HeroUI theme),
 * and `stateColor()` (per-coroutine-state colouring). `tokens.test.ts` asserts
 * the CSS layer never drifts from these values.
 *
 * Semantic meaning is fixed by the design direction: a *potential* leak is
 * warning/amber, never danger/red.
 */
export const palette = {
  // surfaces
  bg: '#0a0a0b',
  surface: '#131316',
  'surface-2': '#18181b',
  'surface-3': '#1f1f23',
  border: '#27272a',
  'border-2': '#323237',

  // text
  text: '#fafafa',
  'text-muted': '#a1a1aa',
  'text-faint': '#71717a',

  // semantic (HeroUI names)
  primary: '#006fee', // running / active / accent
  success: '#17c964', // completed
  warning: '#f5a524', // suspended / cancelled / potential leak
  danger: '#f31260', // failed
  created: '#8b8b94', // created, not yet started
} as const

/**
 * The light theme's surfaces and text. Semantic colours (primary, success,
 * warning, danger, created) are shared with the dark theme, so a state reads
 * the same in both. Applied by the `html.light` block in `tokens.css`.
 */
export const lightPalette = {
  bg: '#fafafa',
  surface: '#ffffff',
  'surface-2': '#f4f4f5',
  'surface-3': '#e4e4e7',
  border: '#e4e4e7',
  'border-2': '#d4d4d8',
  text: '#18181b',
  'text-muted': '#52525b',
  'text-faint': '#71717a',
} as const

/**
 * Fixed colours that carry meaning inside a visualization, the same in both
 * themes. They are not CSS variables, so `tokens.test.ts` does not look for them.
 */
export const vizColors = {
  // flow operators (FlowParticlePath)
  operatorTransform: '#6366f1',
  operatorFilter: '#f59e0b',
  operatorTerminal: '#10b981',
  operatorCombine: '#a855f7',
  operatorSource: '#94a3b8',
  particleFiltered: '#f59e0b',
  particleDefault: '#6366f1',
  // deadlock graph (DeadlockVisualization)
  deadlockEdge: '#ef4444',
  deadlockCoroutineFill: '#fecaca',
  deadlockCoroutineStroke: '#ef4444',
  deadlockLockFill: '#fde68a',
  deadlockLockStroke: '#f59e0b',
} as const

export type PaletteKey = keyof typeof palette

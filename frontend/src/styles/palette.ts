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

export type PaletteKey = keyof typeof palette

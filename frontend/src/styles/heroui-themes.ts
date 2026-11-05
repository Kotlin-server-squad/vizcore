import { palette, lightPalette } from './palette'

/** Semantic colours, shared by both themes so a state reads the same in each. */
const semantic = {
  primary: { DEFAULT: palette.primary, foreground: '#ffffff' },
  // NOTE: `secondary` is deliberately left at the HeroUI default. vizcore's
  // palette has no secondary hue, but `secondary` is load-bearing across ~15
  // components (79 references) and `coroutine-state-colors` maps
  // WAITING_FOR_CHILDREN onto it — neutralising it here silently flattens that
  // state into the same grey as CREATED and CANCELLED. Retiring `secondary`
  // is a component-level decision, not a token-level one; see sub-project 3.
  success: { DEFAULT: palette.success, foreground: '#00110a' },
  warning: { DEFAULT: palette.warning, foreground: '#1a1200' },
  danger: { DEFAULT: palette.danger, foreground: '#ffffff' },
  focus: palette.primary,
}

export const darkThemeColors = {
  ...semantic,
  background: palette.bg,
  foreground: palette.text,
  content1: palette.surface,
  content2: palette['surface-2'],
  content3: palette['surface-3'],
  divider: palette.border,
}

/**
 * Text-safe semantic colours for the light theme. The dark palette's green and
 * amber are about 2:1 on white, so `text-success` and `text-warning` would be
 * unreadable; these darker shades (HeroUI's 700 / 600 steps) reach WCAG AA
 * (4.5:1) on the light surfaces. Coroutine-state colours in the visualizations
 * come from `palette` via `stateColor()` and stay the same in both themes.
 */
const lightSemantic = {
  success: { DEFAULT: '#0e793c', foreground: '#ffffff' },
  warning: { DEFAULT: '#936316', foreground: '#ffffff' },
  danger: { DEFAULT: '#c20e4d', foreground: '#ffffff' },
}

export const lightThemeColors = {
  ...semantic,
  ...lightSemantic,
  background: lightPalette.bg,
  foreground: lightPalette.text,
  content1: lightPalette.surface,
  content2: lightPalette['surface-2'],
  content3: lightPalette['surface-3'],
  divider: lightPalette.border,
}

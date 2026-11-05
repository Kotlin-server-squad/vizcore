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

export const lightThemeColors = {
  ...semantic,
  background: lightPalette.bg,
  foreground: lightPalette.text,
  content1: lightPalette.surface,
  content2: lightPalette['surface-2'],
  content3: lightPalette['surface-3'],
  divider: lightPalette.border,
}

import { heroui } from '@heroui/react'
import typography from '@tailwindcss/typography'
import type { Config } from 'tailwindcss'
import { palette } from './src/styles/palette'

/**
 * The HeroUI dark theme is the real theme — vizcore is dark-first by design
 * decision. The light theme is kept in sync so a future toggle is cheap, but
 * nothing switches to it today (see the `dark` class on <html>).
 */
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

export default {
  content: [
    './index.html',
    './src/**/*.{js,ts,jsx,tsx}',
    './node_modules/@heroui/theme/dist/**/*.{js,ts,jsx,tsx}',
  ],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      borderRadius: {
        viz: '12px',
        'viz-sm': '8px',
      },
    },
  },
  darkMode: 'class',
  plugins: [
    heroui({
      themes: {
        light: { colors: { ...semantic } },
        dark: {
          colors: {
            ...semantic,
            background: palette.bg,
            foreground: palette.text,
            content1: palette.surface,
            content2: palette['surface-2'],
            content3: palette['surface-3'],
            divider: palette.border,
          },
        },
      },
    }),
    typography,
  ],
} satisfies Config

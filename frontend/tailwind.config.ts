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
  // vizcore's palette has no secondary hue. HeroUI's default is purple, which
  // reads as off-brand, so `color="secondary"` maps to the neutral, bordered
  // treatment the sketch theme uses for secondary actions (`.btn.ghost`).
  // A bare hex (not a {DEFAULT,foreground} object) lets HeroUI generate the
  // full 50-900 scale, so derived shades — which `variant="flat"` uses for its
  // text — stay neutral too.
  secondary: palette['text-muted'],
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

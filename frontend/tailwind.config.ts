import { heroui } from '@heroui/react'
import typography from '@tailwindcss/typography'
import type { Config } from 'tailwindcss'
import { darkThemeColors, lightThemeColors } from './src/styles/heroui-themes'

/**
 * Both HeroUI themes are real: dark is the default (the `dark` class on
 * <html>), light is chosen with the switch in the top bar (`useTheme`).
 * Colours come from `src/styles/heroui-themes.ts`.
 */
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
        light: { colors: lightThemeColors },
        dark: { colors: darkThemeColors },
      },
    }),
    typography,
  ],
} satisfies Config

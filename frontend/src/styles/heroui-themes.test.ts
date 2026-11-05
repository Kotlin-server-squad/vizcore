import { describe, it, expect } from 'vitest'
import { darkThemeColors, lightThemeColors } from './heroui-themes'
import { palette, lightPalette } from './palette'

describe('HeroUI themes', () => {
  it('builds the light theme from the light palette', () => {
    expect(lightThemeColors).toMatchObject({
      background: lightPalette.bg,
      foreground: lightPalette.text,
      content1: lightPalette.surface,
      content2: lightPalette['surface-2'],
      content3: lightPalette['surface-3'],
      divider: lightPalette.border,
    })
  })

  it('keeps the dark theme as it was', () => {
    expect(darkThemeColors).toMatchObject({
      background: palette.bg,
      foreground: palette.text,
      content1: palette.surface,
      content2: palette['surface-2'],
      content3: palette['surface-3'],
      divider: palette.border,
    })
  })

  it('shares the semantic colours between the themes', () => {
    for (const key of ['primary', 'success', 'warning', 'danger', 'focus'] as const) {
      expect(lightThemeColors[key]).toEqual(darkThemeColors[key])
    }
    expect(lightThemeColors.primary).toEqual({ DEFAULT: palette.primary, foreground: '#ffffff' })
  })
})

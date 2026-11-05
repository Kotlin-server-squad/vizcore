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

  it('shares the accent between the themes', () => {
    for (const key of ['primary', 'focus'] as const) {
      expect(lightThemeColors[key]).toEqual(darkThemeColors[key])
    }
    expect(lightThemeColors.primary).toEqual({ DEFAULT: palette.primary, foreground: '#ffffff' })
  })

  // WCAG AA for normal text is 4.5:1. The dark palette's green and amber are about 2:1 on white.
  it.each(['success', 'warning', 'danger'] as const)('keeps %s text readable on light surfaces', (key) => {
    const { DEFAULT, foreground } = lightThemeColors[key]
    expect(contrast(DEFAULT, lightPalette.bg)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(DEFAULT, lightPalette.surface)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(foreground, DEFAULT)).toBeGreaterThanOrEqual(4.5)
  })

  it('keeps primary text readable on light cards', () => {
    expect(contrast(lightThemeColors.primary.DEFAULT, lightPalette.surface)).toBeGreaterThanOrEqual(4.5)
  })
})

/** WCAG contrast ratio of two #rrggbb colours. */
function contrast(a: string, b: string): number {
  const luminance = (hex: string) => {
    const [r, g, bl] = [1, 3, 5].map((i) => {
      const c = parseInt(hex.slice(i, i + 2), 16) / 255
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
    }) as [number, number, number]
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl
  }
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number]
  return (hi + 0.05) / (lo + 0.05)
}

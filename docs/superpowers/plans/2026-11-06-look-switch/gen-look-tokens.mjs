#!/usr/bin/env node
/**
 * Writes the colour block of one look's tokens.css (PR L0, the Look switch).
 *
 *   node docs/superpowers/plans/2026-11-06-look-switch/gen-look-tokens.mjs frontend/src/looks/a-compact
 *
 * Reads the look's palette.json (base colours for its dark and light theme,
 * read from the sketches in ./sketches; each sketch shows one theme and the
 * other is derived in the same spirit) and replaces the block between the
 * "generated" markers in the look's tokens.css. HeroUI wants its colours as
 * "H S% L%" triplets with a 50–900 ramp per colour, which CSS cannot derive from
 * a hex value; hence this script. Hand edits to the output are fine too.
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const rgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))
const toHex = (c) => '#' + c.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')
/** `t` of the way from `a` to `b`. */
const mix = (a, b, t) => toHex(rgb(a).map((v, i) => v + (rgb(b)[i] - v) * t))

function hsl(hex) {
  const [r, g, b] = rgb(hex).map((v) => v / 255)
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return `0 0% ${(l * 100).toFixed(2)}%`
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h = max === r ? (g - b) / d + (g < b ? 6 : 0) : max === g ? (b - r) / d + 2 : (r - g) / d + 4
  h *= 60
  return `${h.toFixed(2)} ${(s * 100).toFixed(2)}% ${(l * 100).toFixed(2)}%`
}

/** WCAG relative luminance and contrast ratio. */
function luminance(hex) {
  const [r, g, b] = rgb(hex).map((v) => {
    const c = v / 255
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}
/** White or near-black text on a fill, whichever reads better. */
const onFill = (fill) => (contrast(fill, '#ffffff') >= contrast(fill, '#0b0b0d') ? '#ffffff' : '#0b0b0d')

function rgba(hex, a) {
  const [r, g, b] = rgb(hex)
  return `rgba(${r}, ${g}, ${b}, ${a})`
}

/** HeroUI's ramp: dark themes run 50 = darkest, like HeroUI's own dark theme. */
function ramp(c, bg, dark) {
  if (dark) {
    return {
      50: mix(bg, c, 0.18), 100: mix(bg, c, 0.35), 200: mix(bg, c, 0.55), 300: mix(bg, c, 0.75),
      400: c, 500: mix(c, '#ffffff', 0.2), 600: mix(c, '#ffffff', 0.4), 700: mix(c, '#ffffff', 0.6),
      800: mix(c, '#ffffff', 0.8), 900: mix(c, '#ffffff', 0.9),
    }
  }
  return {
    50: mix('#ffffff', c, 0.08), 100: mix('#ffffff', c, 0.18), 200: mix('#ffffff', c, 0.35),
    300: mix('#ffffff', c, 0.55), 400: mix('#ffffff', c, 0.78), 500: c, 600: mix(c, '#000000', 0.2),
    700: mix(c, '#000000', 0.4), 800: mix(c, '#000000', 0.6), 900: mix(c, '#000000', 0.75),
  }
}

function neutralRamp(p, dark) {
  const { text } = p
  if (dark) {
    return {
      50: p['surface-2'], 100: p.border, 200: p['border-2'], 300: mix(p['border-2'], text, 0.15),
      400: p['text-faint'], 500: p['text-muted'], 600: mix(p['text-muted'], text, 0.5),
      700: mix(p['text-muted'], text, 0.7), 800: mix(p['text-muted'], text, 0.85), 900: text,
    }
  }
  return {
    50: p['surface-2'], 100: p['surface-3'], 200: p['border-2'], 300: mix(p['border-2'], p['text-faint'], 0.35),
    400: mix(p['border-2'], p['text-faint'], 0.7), 500: p['text-faint'], 600: p['text-muted'],
    700: mix(p['text-muted'], text, 0.4), 800: mix(p['text-muted'], text, 0.7), 900: text,
  }
}

function block(id, theme, p) {
  const dark = theme === 'dark'
  const out = []
  const v = (name, value, note) => out.push(`  --${name}: ${value};${note ? ` /* ${note} */` : ''}`)
  out.push(`html.${theme}[data-look='${id}'] {`)
  out.push('  /* vizcore tokens (tokens.css) */')
  for (const k of ['bg', 'surface', 'surface-2', 'surface-3', 'border', 'border-2', 'text', 'text-muted',
    'text-faint', 'primary', 'success', 'warning', 'danger', 'created']) v(k, p[k])
  for (const k of ['primary', 'success', 'warning', 'danger']) v(`${k}-soft`, rgba(p[k], 0.16))
  v('state-chip-text', 'var(--text)')
  out.push('  /* HeroUI theme, as "H S% L%" */')
  v('heroui-background', hsl(p.bg), p.bg)
  v('heroui-foreground', hsl(p.text), p.text)
  v('heroui-divider', hsl(p.border), p.border)
  v('heroui-focus', hsl(p.primary), p.primary)
  v('heroui-content1', hsl(p.surface), p.surface)
  v('heroui-content2', hsl(p['surface-2']), p['surface-2'])
  v('heroui-content3', hsl(p['surface-3']), p['surface-3'])
  v('heroui-content4', hsl(p['border-2']), p['border-2'])
  v('heroui-content4-foreground', hsl(p.text), p.text)
  const n = neutralRamp(p, dark)
  for (const [step, c] of Object.entries(n)) v(`heroui-default-${step}`, hsl(c), c)
  v('heroui-default', hsl(n[dark ? 200 : 300]))
  v('heroui-default-foreground', hsl(dark ? '#ffffff' : p.text))
  for (const role of ['primary', 'secondary', 'success', 'warning', 'danger']) {
    const r = ramp(p[role], p.bg, dark)
    for (const [step, c] of Object.entries(r)) v(`heroui-${role}-${step}`, hsl(c), c)
    v(`heroui-${role}`, hsl(p[role]), p[role])
    const fg = onFill(p[role])
    v(`heroui-${role}-foreground`, hsl(fg), fg)
  }
  out.push('}')
  return out.join('\n')
}

const BEGIN = '/* --- generated by gen-look-tokens.mjs from palette.json (edit palette.json and re-run, or hand-edit) --- */'
const END = '/* --- end of generated block --- */'

const dir = process.argv[2]
if (!dir) {
  console.error('usage: gen-look-tokens.mjs <frontend/src/looks/<look-folder>>')
  process.exit(1)
}
const look = JSON.parse(readFileSync(join(dir, 'palette.json'), 'utf8'))
const generated = [BEGIN, block(look.id, 'dark', look.dark), '', block(look.id, 'light', look.light), END].join('\n')
const cssPath = join(dir, 'tokens.css')
const css = readFileSync(cssPath, 'utf8')
const from = css.indexOf(BEGIN)
const to = css.indexOf(END)
const next = from >= 0 && to > from
  ? css.slice(0, from) + generated + css.slice(to + END.length)
  : `${css.trimEnd()}\n\n${generated}\n`
writeFileSync(cssPath, next)
console.log(`wrote ${cssPath}`)

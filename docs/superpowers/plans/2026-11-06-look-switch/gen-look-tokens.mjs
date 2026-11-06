#!/usr/bin/env node
/**
 * Prints the colour part of one look's tokens.css (PR L0, the Look switch).
 *
 *   node docs/superpowers/plans/2026-11-06-look-switch/gen-look-tokens.mjs a
 *
 * The base colours below were read from the sketches in ./sketches (each sketch
 * shows one theme; the other theme of each look is derived in the same spirit).
 * HeroUI wants its colours as "H S% L%" triplets and a 50–900 ramp per colour,
 * which CSS cannot derive from a hex value, so this script writes them. Edit the
 * table, re-run, and paste the output over the generated block of
 * frontend/src/looks/<look>/tokens.css. Hand edits to the triplets are fine too.
 */

const LOOKS = {
  a: {
    name: 'A · Compact',
    dark: {
      // K-A-Compact: JetBrains-style dark.
      bg: '#1e1f22', surface: '#2b2d30', 'surface-2': '#313337', 'surface-3': '#393b40',
      border: '#393b40', 'border-2': '#4e5157',
      text: '#dfe1e5', 'text-muted': '#9da0a8', 'text-faint': '#6f737a',
      primary: '#548af7', success: '#5fb865', warning: '#e5a50a', danger: '#e5534b',
      secondary: '#a571e6', created: '#868a91',
    },
    light: {
      bg: '#f7f8fa', surface: '#ffffff', 'surface-2': '#f2f3f5', 'surface-3': '#ebecf0',
      border: '#ebecf0', 'border-2': '#c9ccd6',
      text: '#1e1f22', 'text-muted': '#5a5d6b', 'text-faint': '#767a89',
      primary: '#3574f0', success: '#1f7a35', warning: '#946100', danger: '#c7303f',
      secondary: '#834dd1', created: '#767a89',
    },
  },
  b: {
    name: 'B · Calm',
    light: {
      // K-B-Calm: soft light, zinc neutrals.
      bg: '#f7f7f8', surface: '#ffffff', 'surface-2': '#f4f4f5', 'surface-3': '#e4e4e7',
      border: '#e4e4e7', 'border-2': '#d4d4d8',
      text: '#18181b', 'text-muted': '#52525b', 'text-faint': '#71717a',
      primary: '#2563eb', success: '#15803d', warning: '#b45309', danger: '#dc2626',
      secondary: '#9333ea', created: '#71717a',
    },
    dark: {
      bg: '#18181b', surface: '#202024', 'surface-2': '#27272a', 'surface-3': '#303036',
      border: '#2f2f35', 'border-2': '#3f3f46',
      text: '#f4f4f5', 'text-muted': '#a1a1aa', 'text-faint': '#71717a',
      primary: '#60a5fa', success: '#4ade80', warning: '#fbbf24', danger: '#f87171',
      secondary: '#c084fc', created: '#8b8b94',
    },
  },
  c: {
    name: 'C · Bold',
    dark: {
      // K-C-Bold: near-black, saturated fills.
      bg: '#0b0d12', surface: '#18181b', 'surface-2': '#1f1f23', 'surface-3': '#27272a',
      border: '#27272a', 'border-2': '#3f3f46',
      text: '#f4f4f5', 'text-muted': '#a1a1aa', 'text-faint': '#71717a',
      primary: '#3b82f6', success: '#22c55e', warning: '#f59e0b', danger: '#f43f5e',
      secondary: '#a78bfa', created: '#8b8b94',
    },
    light: {
      bg: '#fafaf9', surface: '#ffffff', 'surface-2': '#f4f4f5', 'surface-3': '#e4e4e7',
      border: '#e4e4e7', 'border-2': '#a1a1aa',
      text: '#0b0d12', 'text-muted': '#3f3f46', 'text-faint': '#71717a',
      primary: '#1d4ed8', success: '#15803d', warning: '#b45309', danger: '#be123c',
      secondary: '#6d28d9', created: '#71717a',
    },
  },
}

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
  const onDark = mix(p.bg, '#000000', 0.5)
  for (const role of ['primary', 'secondary', 'success', 'warning', 'danger']) {
    const r = ramp(p[role], p.bg, dark)
    for (const [step, c] of Object.entries(r)) v(`heroui-${role}-${step}`, hsl(c), c)
    v(`heroui-${role}`, hsl(p[role]), p[role])
    const fg = dark && (role === 'success' || role === 'warning') ? onDark : '#ffffff'
    v(`heroui-${role}-foreground`, hsl(fg), fg)
  }
  out.push('}')
  return out.join('\n')
}

const id = process.argv[2]
const look = LOOKS[id]
if (!look) {
  console.error(`usage: gen-look-tokens.mjs <${Object.keys(LOOKS).join('|')}>`)
  process.exit(1)
}
console.log(`/* --- generated by gen-look-tokens.mjs ${id}: ${look.name} --- */`)
console.log(block(id, 'dark', look.dark))
console.log()
console.log(block(id, 'light', look.light))
console.log(`/* --- end of generated block --- */`)

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { createElement } from 'react'
import { bold } from '.'
import { boldSessionBody } from './SessionBody'
import {
  ROW_CAP,
  UNNAMED,
  rightNowLines,
  segmentsLabel,
  stateSegments,
} from './overview'
import type { ActivityLine } from '@/lib/activity'
import type { StateCounts } from '@/lib/state-counts'
import { CoroutineState, type CoroutineNode } from '@/types/api'

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'tokens.css'), 'utf8')

const node = (id: string, label: string | null, state = CoroutineState.ACTIVE): CoroutineNode => ({
  id,
  jobId: `job-${id}`,
  parentId: null,
  scopeId: 'scope-1',
  label,
  state,
})
const holds = (text: string): ActivityLine => ({ text, waiting: false })
const waits = (text: string): ActivityLine => ({ text, waiting: true })

const counts = (over: Partial<StateCounts>): StateCounts => ({
  active: 0,
  waiting: 0,
  suspended: 0,
  completed: 0,
  cancelled: 0,
  failed: 0,
  total: 0,
  leaks: 0,
  ...over,
})

describe('C · Bold', () => {
  it('is the trial look "c", labelled as the ⋯ menu shows it, with its own SessionBody', () => {
    expect(bold).toEqual({
      id: 'c',
      label: 'C · Bold',
      trial: true,
      slots: { SessionBody: boldSessionBody },
    })
  })
})

describe("C's SessionBody", () => {
  it('keeps the slot shape: one div with the body hook and the ref, main view then dock, then the overview', () => {
    const ref = () => {}
    const main = createElement('p', null, 'main')
    const dock = createElement('p', null, 'dock')
    const body = boldSessionBody({ sharedRef: ref, main, dock })
    expect(body.type).toBe('div')
    const props = body.props as { ref?: unknown; 'data-look-part': string; className: string; children: unknown[] }
    expect(props['data-look-part']).toBe('body')
    // The dock measures this element: the shared ref must reach it.
    expect(props.ref).toBe(ref)
    // Classic's column with its 12 px gap (`gap-3`): the dock's size rules assume it.
    expect(props.className).toContain('gap-3')
    expect(props.children[0]).toBe(main)
    expect(props.children[1]).toBe(dock)
    expect(props.children).toHaveLength(3)
  })
})

describe('rightNowLines', () => {
  it('lists, by name, every live coroutine that holds or waits: holders first, page order within each', () => {
    const coroutines = [
      node('c-3', 'order-3', CoroutineState.SUSPENDED),
      node('c-4', 'order-4'),
      node('c-5', 'order-5', CoroutineState.SUSPENDED),
      node('c-9', 'order-9'),
    ]
    const activity = new Map([
      ['c-3', waits('waits for a payment-gateway permit · 1st')],
      ['c-4', holds('holds inventory-lock')],
      ['c-5', waits('waits for inventory-lock · 1st')],
    ])
    expect(rightNowLines(coroutines, activity)).toEqual([
      { id: 'c-4', name: 'order-4', text: 'holds inventory-lock', waiting: false },
      { id: 'c-3', name: 'order-3', text: 'waits for a payment-gateway permit · 1st', waiting: true },
      { id: 'c-5', name: 'order-5', text: 'waits for inventory-lock · 1st', waiting: true },
    ])
  })

  it('leaves out finished coroutines, and never shows an id for an unnamed one', () => {
    const coroutines = [
      node('c-1', 'done', CoroutineState.COMPLETED),
      node('c-2', 'stopped', CoroutineState.CANCELLED),
      node('c-3', 'broke', CoroutineState.FAILED),
      node('coroutine-77', null),
      node('coroutine-78', ''),
    ]
    const activity = new Map(coroutines.map(c => [c.id, holds('holds a lock')]))
    const lines = rightNowLines(coroutines, activity)
    expect(lines.map(l => l.name)).toEqual([UNNAMED, UNNAMED])
    expect(lines.map(l => l.name).join()).not.toContain('coroutine-')
  })

  it('caps what is shown at ROW_CAP lines (the rest are counted by the overview)', () => {
    expect(ROW_CAP).toBe(6)
  })
})

describe('stateSegments', () => {
  it('keeps the non-zero buckets in the state chips’ order, with their colours', () => {
    const segments = stateSegments(counts({ completed: 1, suspended: 3, active: 3, waiting: 1, total: 8 }))
    expect(segments.map(s => [s.key, s.count])).toEqual([
      ['active', 3],
      ['waiting', 1],
      ['suspended', 3],
      ['completed', 1],
    ])
    expect(segments[0]!.color).toBe('var(--primary)')
    expect(segments[2]!.color).toBe('var(--warning)')
    expect(segments[3]!.color).toBe('var(--success)')
  })

  it('is empty for an empty session, and the leak flag is not a bucket', () => {
    expect(stateSegments(counts({ leaks: 2 }))).toEqual([])
  })

  it('says what the bar shows, for its text alternative', () => {
    const segments = stateSegments(counts({ active: 3, suspended: 3, waiting: 1, failed: 2 }))
    expect(segmentsLabel(segments)).toBe('3 active, 1 waiting for children, 3 suspended, 2 failed')
  })
})

/** "#rrggbb" or "H S% L%" → relative luminance (WCAG). */
function luminance(colour: string): number {
  let rgb: number[]
  if (colour.startsWith('#')) {
    rgb = [1, 3, 5].map(i => parseInt(colour.slice(i, i + 2), 16) / 255)
  } else {
    const [h = 0, s = 0, l = 0] = colour.split(/\s+/).map(part => parseFloat(part))
    const a = (s / 100) * Math.min(l / 100, 1 - l / 100)
    rgb = [0, 8, 4].map(n => {
      const k = (n + h / 30) % 12
      return l / 100 - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    })
  }
  const [r = 0, g = 0, b = 0] = rgb.map(c => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4))
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}
const contrast = (a: string, b: string) => {
  const [la, lb] = [luminance(a), luminance(b)]
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}
/** `--name: value;` declared in the `selector { … }` block. */
function token(selector: string, name: string): string {
  const start = css.indexOf(`${selector} {`)
  expect(start, selector).toBeGreaterThanOrEqual(0)
  const body = css.slice(start, css.indexOf('}', start))
  const value = new RegExp(`${name}:\\s*([^;]+);`).exec(body)?.[1]?.trim()
  expect(value, `${selector} ${name}`).toBeDefined()
  return value!.replace(/\s*\/\*.*$/, '')
}

describe("C's own colour pairs (WCAG AA in both themes)", () => {
  it.each(['dark', 'light'])('%s: the callout text, its names and its heading on the callout', theme => {
    const overview = `html.${theme}[data-look='c'] [data-c='overview']`
    const page = `html.${theme}[data-look='c']`
    const bg = token(overview, '--c-callout-bg')
    expect(contrast(token(page, '--text'), bg)).toBeGreaterThanOrEqual(4.5)
    // Waiting names are in the heading colour, at body size.
    expect(contrast(token(overview, '--c-callout-heading'), bg)).toBeGreaterThanOrEqual(4.5)
  })

  it.each(['dark', 'light'])('%s: the dock tabs, selected and not', theme => {
    const page = `html.${theme}[data-look='c']`
    const fg = token(page, '--heroui-foreground')
    const bg = token(page, '--heroui-background')
    expect(contrast(bg, fg)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(token(page, '--heroui-default-700'), token(page, '--heroui-default-100'))).toBeGreaterThanOrEqual(4.5)
  })
})

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { calm } from '.'
import { calmCard } from './card'
import { summarise, type SummaryFacts } from './summary'
import type { WaitKind } from '@/types/api'

describe('B · Calm', () => {
  it('is the trial look "b", labelled as the ⋯ menu shows it, with its own line under the title', () => {
    expect(calm).toMatchObject({ id: 'b', label: 'B · Calm', trial: true })
    expect(Object.keys(calm.slots)).toEqual(['HeaderSummary'])
  })
})

describe('B’s CSS', () => {
  const here = dirname(fileURLToPath(import.meta.url))
  const css = readFileSync(join(here, 'tokens.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  /** Every .tsx under src/components and src/routes, as one string. */
  const sources = (dir: string): string[] =>
    readdirSync(dir).flatMap(name => {
      const path = join(dir, name)
      if (statSync(path).isDirectory()) return sources(path)
      return path.endsWith('.tsx') && !path.endsWith('.test.tsx') ? [readFileSync(path, 'utf8')] : []
    })
  const src = resolve(here, '../..')
  const code = [...sources(join(src, 'components')), ...sources(join(src, 'routes'))].join('\n')

  it('styles only page parts that exist: every hook and label it targets is in the components', () => {
    const parts = new Set([...css.matchAll(/data-look-part='([\w-]+)'/g)].map(m => m[1]!))
    expect([...parts].sort()).toEqual([
      'dock',
      'dock-tabs',
      'header',
      'header-actions',
      'main',
      'main-body',
      'moment',
      'page',
      'replay-bar',
      'replay-time',
      'round-bar',
      'round-find',
      'round-find-match',
      'round-find-results',
      'round-picker',
      'run-again',
      'trimmed-note',
      'workspace',
    ])
    for (const part of parts) expect(code, part).toContain(`data-look-part="${part}"`)
    for (const label of [...css.matchAll(/aria-label='([^']+)'/g)].map(m => m[1]!)) {
      expect(code, label).toContain(`aria-label="${label}"`)
    }
    expect(code).toContain('data-export-raster="true"')
    expect(code).toContain("data-fullscreen={isFullscreen ? 'true' : 'false'}")
    expect(code).toContain('id="dock-body"')
  })

  it('leaves the main view and the dock where they are: no gap, size or position change on the body', () => {
    expect(css).not.toMatch(/data-look-part='body'/)
    expect(css).not.toMatch(/data-look-part='dock'\][^{]*\{[^}]*\b(height|width|position|margin)\b/)
  })
})

describe('B’s graph card', () => {
  const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'card.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
  /** The declarations of the rules whose selector ends on the part itself, as name → value. */
  const rule = (part: string) =>
    new Map(
      [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)]
        .filter(m => (m[1] ?? '').split(',').some(sel => sel.trim().endsWith(`[data-look-part='${part}']`)))
        .flatMap(m => [...(m[2] ?? '').matchAll(/([\w-]+):\s*([^;]+);/g)].map(d => [d[1]!, d[2]!.trim()] as const)),
    )
  const px = (value: string | undefined) => Number.parseFloat(value ?? 'NaN')
  /** "8px 12px" → [top, bottom]. */
  const vertical = (padding: string | undefined): [number, number] => {
    const [top = Number.NaN, , bottom = top] = (padding ?? '').split(/\s+/).map(px)
    return [top, bottom]
  }

  it('declares the size of every text it shows, and each is at least 12 px on screen at the fit floor', () => {
    const floor = calmCard.fitMinZoom!
    expect(floor).toBeGreaterThanOrEqual(0.9)
    for (const part of ['node-name', 'node-state', 'node-activity', 'node-children']) {
      const size = px(rule(part).get('font-size'))
      expect(size, part).toBeGreaterThan(0)
      expect(size * floor, part).toBeGreaterThanOrEqual(12)
    }
  })

  it('holds the name, the pill and one line of the second line inside its box, as card.css draws them', () => {
    const node = rule('node')
    const [padTop, padBottom] = vertical(node.get('padding'))
    const border = 2 * px(node.get('border-width'))
    const gap = px(rule('node-text').get('gap'))
    const pill = rule('node-state')
    const [pillTop, pillBottom] = vertical(pill.get('padding'))
    const contentHeight =
      border +
      padTop +
      padBottom +
      px(rule('node-name').get('line-height')) +
      gap +
      (pillTop + px(pill.get('line-height')) + pillBottom) +
      gap +
      px(rule('node-activity').get('line-height'))
    expect(contentHeight).toBeLessThanOrEqual(calmCard.height)
    // The name and the second line keep to one line each, so nothing grows past that.
    expect(rule('node-name').get('-webkit-line-clamp')).toBe('1')
    expect(rule('node-activity').get('white-space')).toBe('nowrap')
  })

  it('is short enough for the root and its first row of children above the default dock at 1440 × 900', () => {
    // The graph canvas there is 287 px tall in B (measured); the fit starts 40 px down (the zoom buttons), and the
    // layout puts 32 px of padding above the root and 64 px between the rows (CoroutineTreeGraph).
    const canvas = 287
    const bottomOfChildren = 40 + (32 + calmCard.height + 64 + calmCard.height) * calmCard.fitMinZoom!
    expect(bottomOfChildren).toBeLessThanOrEqual(canvas)
    // Six children (the E-commerce demo) side by side fit the canvas's 1,374 px width at the floor.
    expect((6 * calmCard.width + 5 * 40 + 64) * calmCard.fitMinZoom!).toBeLessThanOrEqual(1374)
  })
})

const wait = (waitKind: WaitKind, resourceId: string | null = null) => ({ waitKind, resourceId })

const NAMES = new Map([
  ['mutex-1', 'inventory-lock'],
  ['sem-1', 'payment-gateway'],
  ['ch-1', 'orders'],
])

function facts(over: Partial<SummaryFacts> = {}): SummaryFacts {
  return {
    outcome: null,
    runSpanNanos: null,
    spanIsFloor: false,
    replayActive: false,
    replayAtNanos: null,
    waits: [],
    names: NAMES,
    ...over,
  }
}

const running = { outcome: 'running', failedCount: 0, ended: false } as const
const finished = { outcome: 'finished', failedCount: 0, ended: true } as const

describe('the sentence under the title (summarise)', () => {
  describe('how the run is going', () => {
    it('says nothing for an attached app with no waits: it has no run outcome', () => {
      expect(summarise(facts())).toBeNull()
    })

    it('a demo not run yet', () => {
      expect(summarise(facts({ outcome: { outcome: 'notStarted', failedCount: 0, ended: false } }))).toBe(
        'Not run yet.',
      )
    })

    it('a running demo, for at least the span of the events the page holds', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: 6e9 }))).toBe('Running for at least 6.0 s.')
    })

    it('a running demo with no span yet', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: null }))).toBe('Running.')
      expect(summarise(facts({ outcome: running, runSpanNanos: 0 }))).toBe('Running.')
    })

    it('a finished demo, and the span of its recorded events (not claimed as the run’s length)', () => {
      expect(summarise(facts({ outcome: finished, runSpanNanos: 0.6e9 }))).toBe('Finished. Its recorded events span 600 ms.')
    })

    it('a finished demo whose events were partly dropped: at least that span', () => {
      expect(summarise(facts({ outcome: finished, runSpanNanos: 0.6e9, spanIsFloor: true }))).toBe(
        'Finished. Its recorded events span at least 600 ms.',
      )
    })

    it('a finished demo with no span', () => {
      expect(summarise(facts({ outcome: finished }))).toBe('Finished.')
    })

    it('a failed run that has ended', () => {
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 1, ended: true }, runSpanNanos: 3e9 }))).toBe(
        '1 coroutine failed. The run has ended; its recorded events span 3.0 s.',
      )
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 1, ended: true }, runSpanNanos: null }))).toBe(
        '1 coroutine failed. The run has ended.',
      )
    })

    it('a failed run that is still going', () => {
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 2, ended: false }, runSpanNanos: 3e9 }))).toBe(
        '2 coroutines failed, and the run is still going.',
      )
    })

    it('in replay, where the cursor is, never the live outcome', () => {
      expect(summarise(facts({ outcome: finished, runSpanNanos: 6e9, replayActive: true, replayAtNanos: 2.3e9 }))).toBe(
        'Replay at 2.3 s.',
      )
      expect(summarise(facts({ outcome: finished, replayActive: true, replayAtNanos: null }))).toBe('Replay.')
    })
  })

  describe('what is waiting', () => {
    it('one coroutine waiting for a named lock', () => {
      expect(summarise(facts({ waits: [wait('MUTEX', 'mutex-1')] }))).toBe('1 coroutine is waiting for inventory-lock.')
    })

    it('several waiting for the same thing', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: 6e9, waits: [wait('MUTEX', 'mutex-1'), wait('MUTEX', 'mutex-1')] }))).toBe(
        'Running for at least 6.0 s. 2 coroutines are waiting for inventory-lock.',
      )
    })

    it('groups by what they wait for, largest group first (the sketch’s sentence)', () => {
      const waits = [wait('SEMAPHORE', 'sem-1'), wait('MUTEX', 'mutex-1'), wait('MUTEX', 'mutex-1')]
      expect(summarise(facts({ outcome: running, runSpanNanos: 6e9, waits }))).toBe(
        'Running for at least 6.0 s. 3 coroutines are waiting: 2 for inventory-lock, 1 for a payment-gateway permit.',
      )
    })

    it('keeps the order they started waiting in for groups of the same size', () => {
      const waits = [wait('SEMAPHORE', 'sem-1'), wait('MUTEX', 'mutex-1')]
      expect(summarise(facts({ waits }))).toBe(
        '2 coroutines are waiting: 1 for a payment-gateway permit, 1 for inventory-lock.',
      )
    })

    it('words channels as sending and receiving', () => {
      const waits = [wait('CHANNEL_SEND', 'ch-1'), wait('CHANNEL_RECEIVE', 'ch-1'), wait('CHANNEL_RECEIVE', 'ch-1')]
      expect(summarise(facts({ waits }))).toBe('3 coroutines are waiting: 2 to receive from orders, 1 to send to orders.')
    })

    it('never shows an internal id: an unnamed resource reads generically, and unnamed locks are one group', () => {
      const waits = [wait('MUTEX', 'mutex-9'), wait('MUTEX', 'mutex-8'), wait('SEMAPHORE', 'sem-9'), wait('CHANNEL_SEND', null), wait('CHANNEL_RECEIVE', 'ch-9')]
      const sentence = summarise(facts({ waits }))!
      expect(sentence).toBe('5 coroutines are waiting: 2 for a lock, 1 for a permit, 1 to send, and 1 for other things.')
      expect(sentence).not.toMatch(/mutex-|sem-|ch-/)
    })

    it('names at most three groups; the rest are "other things"', () => {
      const names = new Map([...NAMES, ['mutex-2', 'audit-lock'], ['mutex-3', 'cache-lock']])
      const waits = [wait('MUTEX', 'mutex-1'), wait('MUTEX', 'mutex-2'), wait('MUTEX', 'mutex-3'), wait('SEMAPHORE', 'sem-1'), wait('CHANNEL_SEND', 'ch-1')]
      expect(summarise(facts({ waits, names }))).toBe(
        '5 coroutines are waiting: 1 for inventory-lock, 1 for audit-lock, 1 for cache-lock, and 2 for other things.',
      )
    })

    it('an "an" before a vowel', () => {
      const names = new Map([['sem-2', 'inventory']])
      expect(summarise(facts({ waits: [wait('SEMAPHORE', 'sem-2')], names }))).toBe('1 coroutine is waiting for an inventory permit.')
    })

    it('says nothing about waits it has no record of', () => {
      expect(summarise(facts({ outcome: running, runSpanNanos: 1e9, waits: [] }))).toBe('Running for at least 1.0 s.')
    })

    it('a run that has ended waits for nothing, even if a lost resume left a wait behind', () => {
      const waits = [wait('MUTEX', 'mutex-1')]
      expect(summarise(facts({ outcome: finished, waits }))).toBe('Finished.')
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 1, ended: true }, waits }))).toBe(
        '1 coroutine failed. The run has ended.',
      )
      // A failed run that still goes on can still wait.
      expect(summarise(facts({ outcome: { outcome: 'failed', failedCount: 1, ended: false }, waits }))).toBe(
        '1 coroutine failed, and the run is still going. 1 coroutine is waiting for inventory-lock.',
      )
    })

    it('in replay, the waits at the cursor', () => {
      expect(summarise(facts({ replayActive: true, replayAtNanos: 2e9, waits: [wait('MUTEX', 'mutex-1')] }))).toBe(
        'Replay at 2.0 s. 1 coroutine is waiting for inventory-lock.',
      )
    })
  })
})

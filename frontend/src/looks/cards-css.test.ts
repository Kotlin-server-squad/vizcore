import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { CARDS, LOOKS } from './registry'

// Each trial look's default graph card (card.css next to its card.ts).
const looksDir = dirname(fileURLToPath(import.meta.url))
const folders = readdirSync(looksDir)
  .filter(name => existsSync(join(looksDir, name, 'palette.json')))
  .map(name => {
    const { id } = JSON.parse(readFileSync(join(looksDir, name, 'palette.json'), 'utf8')) as {
      id: string
    }
    return { name, id, css: readFileSync(join(looksDir, name, 'card.css'), 'utf8') }
  })

/** The declarations of every rule with a selector that ends on `part` itself (not a child or ::before of it). */
function rulesFor(css: string, part: string): string {
  return [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^}]*)\}/g)]
    .filter(m =>
      (m[1] ?? '')
        .split(',')
        .some(selector => selector.trim().endsWith(`[data-look-part='${part}']`))
    )
    .map(m => m[2])
    .join(';')
}

describe('the looks’ graph cards', () => {
  it('gives every trial look a card.css, and Classic none', () => {
    expect(folders.map(f => f.id).sort()).toEqual(
      LOOKS.filter(l => l.trial)
        .map(l => l.id)
        .sort()
    )
    expect(existsSync(join(looksDir, 'classic', 'card.css'))).toBe(false)
  })

  // Keyed on the graph's own data-card-look, not on <html>'s data-look: an export
  // sets the page's look aside for the capture, but the cards keep their size.
  it.each(folders)('$name scopes every card rule to its own look’s cards', ({ id, css }) => {
    const selectors = css
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .split('}')
      .map(rule => (rule.split('{')[0] ?? '').trim())
      .filter(Boolean)
      .flatMap(s => s.split(',').map(part => part.trim()))
    expect(selectors.length).toBeGreaterThan(5)
    for (const selector of selectors) {
      expect(selector, selector).toMatch(new RegExp(`^\\[data-card-look='${id}'\\]`))
    }
  })

  it.each(folders)(
    '$name shows the name, a state pill and the second line, and hides ID / Job / Scope',
    ({ css }) => {
      for (const hidden of ['node-details', 'node-icon', 'node-progress', 'node-tint']) {
        expect(rulesFor(css, hidden), hidden).toMatch(/display:\s*none/)
      }
      for (const shown of ['node-name', 'node-state', 'node-activity']) {
        expect(rulesFor(css, shown), shown).not.toMatch(/display:\s*none/)
      }
      expect(rulesFor(css, 'node-state')).toMatch(/border-radius/)
      expect(rulesFor(css, 'node-state')).toMatch(/background/)
    }
  )

  // The card's height (card.ts) holds a class line and the name on a fixed number of lines: a rule that let the
  // name grow freely, or cut it to one line again, would break one or the other.
  it.each(folders)(
    '$name shows a "Class.method" name as a class line and the method on two lines at most',
    ({ css }) => {
      expect(rulesFor(css, 'node-name-owner')).toMatch(/line-height:\s*\d+px/)
      expect(rulesFor(css, 'node-name-owner')).not.toMatch(/display:\s*none/)
      const clamps = [...rulesFor(css, 'node-name-method').matchAll(/-webkit-line-clamp:\s*(\d+)/g)].map(m => Number(m[1]))
      // Tailwind's line-clamp-2 on the shared markup is the default; a look may only restate it or, for a name with
      // no class line, use that line too.
      for (const lines of clamps) expect([2, 3]).toContain(lines)
      expect(rulesFor(css, 'node-name')).not.toMatch(/-webkit-line-clamp|white-space:\s*nowrap/)
    }
  )

  it.each(folders)(
    '$name keeps the selection ring: no rule overrides the card’s box-shadow',
    ({ css }) => {
      expect(rulesFor(css, 'node')).not.toMatch(/(^|[;\s])box-shadow\s*:/)
    }
  )

  it.each(folders)(
    '$name: its card is smaller than Classic’s in both directions, so the tree fits more',
    ({ id }) => {
      const card = CARDS[id as keyof typeof CARDS]
      expect(card.width).toBeLessThan(CARDS.classic.width)
      expect(card.height).toBeLessThan(CARDS.classic.height)
    }
  )
})

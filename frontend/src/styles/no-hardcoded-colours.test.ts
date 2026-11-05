import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'

const src = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** Every .tsx file under src/, excluding tests and src/styles/. */
function componentFiles(dir = src): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return path.endsWith('/styles') ? [] : componentFiles(path)
    return path.endsWith('.tsx') && !path.includes('.test.') ? [path] : []
  })
}

/** Drops // and /* *\/ comments so issue references like #139 are never read as colours. */
function withoutComments(code: string): string {
  return code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`])\/\/.*$/gm, '$1')
}

const GRAY_CLASS = /\b(?:[a-z]+:)*(?:bg|text|border|from|via|to|ring|fill|stroke|divide)-(?:gray|zinc|slate|neutral|stone)-\d{2,3}\b/g
const HEX_COLOUR = /(?<=['"`:=]\s*)#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b/g

function offenders(pattern: RegExp): string[] {
  return componentFiles().flatMap((file) => {
    const found = withoutComments(readFileSync(file, 'utf8')).match(pattern) ?? []
    return found.map((hit) => `${relative(src, file)}: ${hit}`)
  })
}

describe('colours come from the theme', () => {
  it('uses no fixed gray Tailwind classes; they do not follow the theme', () => {
    expect(offenders(GRAY_CLASS)).toEqual([])
  })

  it('uses no raw hex colours outside src/styles/', () => {
    expect(offenders(HEX_COLOUR)).toEqual([])
  })

  it('does not mistake an issue reference for a colour', () => {
    expect(withoutComments('// see: #139\nconst a = 1').match(HEX_COLOUR)).toBeNull()
    expect("fill='#ef4444'".match(HEX_COLOUR)).toEqual(['#ef4444'])
  })
})

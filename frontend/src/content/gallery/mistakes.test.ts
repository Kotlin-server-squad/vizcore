import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it, expect } from 'vitest'
import { MISTAKES } from './mistakes'
import { GUIDES } from './guides'
import type { MistakeId } from './types'

// Every AntiPatternType value. `satisfies` makes a new kind a type error here until it is listed,
// and the list then fails the first test until mistakes.ts has its entry.
const KINDS = {
  GLOBAL_SCOPE_USAGE: true,
  BLOCKING_ON_MAIN: true,
  LEAKED_COROUTINE: true,
  UNNECESSARY_ASYNC_AWAIT: true,
  RUN_BLOCKING_MISUSE: true,
  COROUTINE_EXPLOSION: true,
  UNHANDLED_EXCEPTION: true,
  MISSING_CANCELLATION_CHECK: true,
  SHARED_MUTABLE_STATE: true,
  WRONG_DISPATCHER: true,
} as const satisfies Record<MistakeId, true>
const ALL = Object.keys(KINDS) as MistakeId[]

const here = dirname(fileURLToPath(import.meta.url))
const detector = readFileSync(
  resolve(
    here,
    '../../../../backend/coroutine-viz-core/src/main/kotlin/com/jh/proj/coroutineviz/checksystem/AntiPatternDetector.kt',
  ),
  'utf8',
)

/** The function `name` declared in `source`, up to the next declaration in the class. */
function body(source: string, name: string): string {
  const start = source.indexOf(`fun ${name}(`)
  if (start < 0) throw new Error(`AntiPatternDetector has no fun ${name}`)
  const next = source.slice(start + 1).search(/\n {4}(?:\/\*\*|fun |private |internal )/)
  return next < 0 ? source.slice(start) : source.slice(start, start + 1 + next)
}

/** The kinds reported by the rules `detectAll()` actually runs, not every kind the file mentions. */
function reportedByDetectAll(source: string): string[] {
  const rules = [...body(source, 'detectAll').matchAll(/\b(detect[A-Za-z]+)\(\)/g)].map((m) => m[1]!)
  const kinds = rules.flatMap((rule) => [...body(source, rule).matchAll(/AntiPatternType\.([A-Z_]+)/g)].map((m) => m[1]!))
  return [...new Set(kinds)].sort()
}

describe('common mistakes', () => {
  it('has exactly one entry per anti-pattern kind', () => {
    expect(MISTAKES.map((m) => m.id).sort()).toEqual([...ALL].sort())
  })

  it('marks as detected exactly the kinds the detector reports today', () => {
    const reported = reportedByDetectAll(detector)
    expect(reported.length).toBeGreaterThan(0)
    // A rule detectAll() no longer runs is not detected, even if its function is still there.
    const withoutExplosion = detector.replace('findings.addAll(detectCoroutineExplosion())', '')
    expect(reportedByDetectAll(withoutExplosion)).not.toContain('COROUTINE_EXPLOSION')
    expect(MISTAKES.filter((m) => m.detected).map((m) => m.id).sort()).toEqual(reported)
  })

  it('gives every mistake a title, what goes wrong and the fix', () => {
    for (const m of MISTAKES) {
      expect(m.title.length, m.id).toBeGreaterThan(0)
      expect(m.whatGoesWrong.length, m.id).toBeGreaterThan(20)
      expect(m.fix.length, m.id).toBeGreaterThan(20)
    }
  })

  it('points guides only at mistakes that exist', () => {
    for (const guide of Object.values(GUIDES)) {
      if (guide.mistakeId) expect(ALL).toContain(guide.mistakeId)
    }
  })
})

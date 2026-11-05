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

describe('common mistakes', () => {
  it('has exactly one entry per anti-pattern kind', () => {
    expect(MISTAKES.map((m) => m.id).sort()).toEqual([...ALL].sort())
  })

  it('marks as detected exactly the kinds the detector reports today', () => {
    const reported = [...new Set([...detector.matchAll(/AntiPatternType\.([A-Z_]+)/g)].map((m) => m[1]))].sort()
    expect(reported.length).toBeGreaterThan(0)
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

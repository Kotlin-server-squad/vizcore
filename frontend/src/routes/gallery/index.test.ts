import { describe, it, expect } from 'vitest'
import { GALLERY_SCENARIOS } from './index'

/**
 * Regression guard for the Gallery scenario-routing bug: cards used dead sentinel
 * scenarioIds (`_pattern_/`, `_flow_/`, `_sync_/`) that the run path turned into
 * non-existent routes like POST /api/scenarios/_pattern_/retry (404). The
 * scenarioId must be the real route suffix so runScenario hits a real endpoint.
 */
describe('GALLERY_SCENARIOS routing', () => {
  it('uses no dead sentinel prefixes', () => {
    const bad = GALLERY_SCENARIOS.filter((s) => /(^_|_pattern_|_flow_|_sync_)/.test(s.scenarioId))
    expect(bad.map((s) => s.scenarioId)).toEqual([])
  })

  it('maps flow and sync cards to their real route prefixes', () => {
    for (const s of GALLERY_SCENARIOS) {
      if (s.category === 'flow') expect(s.scenarioId.startsWith('flow/')).toBe(true)
      if (s.category === 'sync') expect(s.scenarioId.startsWith('sync/')).toBe(true)
    }
  })

  it('every scenarioId is a clean route suffix (lowercase, slashes, hyphens)', () => {
    for (const s of GALLERY_SCENARIOS) {
      expect(s.scenarioId).toMatch(/^[a-z0-9]+(?:[/-][a-z0-9]+)*$/)
    }
  })
})

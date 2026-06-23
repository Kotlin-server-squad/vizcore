import { describe, it, expect } from 'vitest'
import { buildExportFilename } from './export-filename'

describe('buildExportFilename (UI-SPEC §4 locked naming)', () => {
  it('builds {sessionId}-{panel}-{yyyyMMdd-HHmm}.{ext} for a fixed date', () => {
    // 2026-06-12 14:30 local time
    const date = new Date(2026, 5, 12, 14, 30)
    expect(buildExportFilename('abc', 'tree', 'png', date)).toBe(
      'abc-tree-20260612-1430.png'
    )
  })

  it('zero-pads month, day, hour, and minute', () => {
    const date = new Date(2026, 0, 3, 9, 5)
    expect(buildExportFilename('s1', 'graph', 'svg', date)).toBe(
      's1-graph-20260103-0905.svg'
    )
  })

  it('honors each panel and extension combination', () => {
    const date = new Date(2026, 11, 31, 23, 59)
    expect(buildExportFilename('xyz', 'events', 'json', date)).toBe(
      'xyz-events-20261231-2359.json'
    )
    expect(buildExportFilename('xyz', 'replay', 'webm', date)).toBe(
      'xyz-replay-20261231-2359.webm'
    )
  })

  it('defaults the date to now when omitted', () => {
    const name = buildExportFilename('sess', 'timeline', 'png')
    expect(name).toMatch(/^sess-timeline-\d{8}-\d{4}\.png$/)
  })
})

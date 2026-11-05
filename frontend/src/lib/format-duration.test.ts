import { describe, it, expect } from 'vitest'
import { formatDuration, formatDurationMs } from './format-duration'

const MS = 1_000_000
const S = 1_000 * MS

describe('formatDuration', () => {
  it.each([
    [0, '0 ms'],
    [0.04 * MS, 'under 0.1 ms'],
    [2.5 * MS, '2.5 ms'],
    [200 * MS, '200 ms'],
    [999.6 * MS, '1.0 s'],
    [2.5 * S, '2.5 s'],
    [12 * S, '12.0 s'],
    [59.96 * S, '1 m 0 s'],
    [252 * S, '4 m 12 s'],
    [3_599.6 * S, '1 h 0 m'],
    [(12 * 3600 + 46 * 60 + 20) * S, '12 h 46 m'],
    [(3 * 86400 + 4 * 3600) * S, '3 d 4 h'],
  ])('%d ns reads %s', (nanos, text) => {
    expect(formatDuration(nanos)).toBe(text)
  })

  it('says "so far" for a coroutine that is still running', () => {
    expect(formatDuration(2.5 * S, { soFar: true })).toBe('2.5 s so far')
  })

  it('says "at least" when events were lost or trimmed', () => {
    expect(formatDuration(12 * S, { atLeast: true })).toBe('at least 12.0 s')
  })

  it('never shows a negative duration', () => {
    expect(formatDuration(-5 * S)).toBe('0 ms')
  })

  it('reads "unavailable" for a value it cannot know (H-3)', () => {
    expect(formatDuration(Number.NaN)).toBe('unavailable')
    expect(formatDuration(Number.POSITIVE_INFINITY)).toBe('unavailable')
  })

  it('takes milliseconds too', () => {
    expect(formatDurationMs(46_000)).toBe('46.0 s')
  })
})

import { describe, it, expect } from 'vitest'
import { indexAtTime, replayClock, sliderScale } from './replay-seek'
import type { VizEvent } from '@/types/api'

const ev = (seq: number, tsNanos: number) => ({ seq, tsNanos, sessionId: 's', kind: 'coroutine.created' }) as unknown as VizEvent

describe('replayClock', () => {
  it('measures each event from the first one', () => {
    const clock = replayClock([ev(1, 5e9), ev(2, 6e9), ev(3, 9e9)])
    expect(clock.times).toEqual([0, 1e9, 4e9])
    expect(clock.spanNanos).toBe(4e9)
  })

  it('never goes back when events from two threads interleave', () => {
    // seq 3 was stamped earlier than seq 2: the replay's time stays at seq 2's.
    const clock = replayClock([ev(1, 0), ev(2, 3e9), ev(3, 2e9), ev(4, 5e9)])
    expect(clock.times).toEqual([0, 3e9, 3e9, 5e9])
  })

  it('never reads below zero when a later event was stamped before the first', () => {
    expect(replayClock([ev(1, 5e9), ev(2, 4e9)]).times).toEqual([0, 0])
  })

  it('is empty with nothing to replay', () => {
    expect(replayClock([])).toEqual({ times: [], spanNanos: 0 })
  })
})

describe('indexAtTime', () => {
  const clock = replayClock([ev(1, 0), ev(2, 1e9), ev(3, 1e9), ev(4, 4e9)])

  it('picks the last event at or before the time', () => {
    expect(indexAtTime(clock, 0)).toBe(0)
    expect(indexAtTime(clock, 0.5e9)).toBe(0)
    expect(indexAtTime(clock, 1e9)).toBe(2)
    expect(indexAtTime(clock, 3.9e9)).toBe(2)
    expect(indexAtTime(clock, 4e9)).toBe(3)
  })

  it('clamps outside the replay', () => {
    expect(indexAtTime(clock, -1)).toBe(0)
    expect(indexAtTime(clock, 99e9)).toBe(3)
  })

  it('is 0 with nothing to replay', () => {
    expect(indexAtTime(replayClock([]), 5)).toBe(0)
  })
})

describe('sliderScale', () => {
  it('moves in tenths of a second', () => {
    const scale = sliderScale(12.04e9)
    expect(scale).toEqual({ mode: 'time', unitNanos: 1e8, max: 121 })
  })

  it('moves in milliseconds when the replay is shorter than two seconds', () => {
    expect(sliderScale(1.5e9)).toEqual({ mode: 'time', unitNanos: 1e6, max: 1500 })
  })

  it('falls back to steps by event when no time passes', () => {
    expect(sliderScale(0)).toEqual({ mode: 'event' })
  })
})

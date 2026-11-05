import { describe, it, expect } from 'vitest'
import { replayTimeText } from './replay-time'
import type { VizEvent } from '@/types/api'

const ev = (tsNanos: number) => ({ seq: 1, tsNanos, sessionId: 's', kind: 'CoroutineCreated' }) as unknown as VizEvent

describe('replayTimeText', () => {
  it('reads the cursor as seconds of the whole replay', () => {
    expect(replayTimeText([ev(1e9), ev(5.2e9), ev(13e9)], 1)).toBe('4.2 seconds of 12.0')
  })

  it('reads zero at the start and the whole length at the end', () => {
    expect(replayTimeText([ev(0), ev(12e9)], 0)).toBe('0.0 seconds of 12.0')
    expect(replayTimeText([ev(0), ev(12e9)], 1)).toBe('12.0 seconds of 12.0')
  })

  it('is undefined with nothing to replay', () => {
    expect(replayTimeText([], 0)).toBeUndefined()
  })
})

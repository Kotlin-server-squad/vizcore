import { describe, it, expect } from 'vitest'
import { sessionFirstSeq } from './session-first-seq'

describe('sessionFirstSeq', () => {
  it('is the oldest held event’s seq when the server never trimmed the session (seqs also number ids)', () => {
    expect(sessionFirstSeq({ sessionId: 's', trimmed: false, oldestRetainedSeq: 3 })).toBe(3)
  })

  it('is unknown when the server trimmed it, holds nothing, or does not say', () => {
    expect(sessionFirstSeq({ sessionId: 's', trimmed: true, oldestRetainedSeq: 812 })).toBeUndefined()
    expect(sessionFirstSeq({ sessionId: 's', trimmed: false, oldestRetainedSeq: null })).toBeUndefined()
    expect(sessionFirstSeq({ sessionId: 's' })).toBeUndefined()
    expect(sessionFirstSeq(undefined)).toBeUndefined()
  })
})

import { describe, it, expect } from 'vitest'
import { healthPath, hierarchyKey, hierarchyPath, readAt, timelineKey, timelinePath, type MomentRead } from './moment-read'

const session = { kind: 'session', sessionId: 's 1' } as const
const shared = { kind: 'shared', sessionId: 's 1', token: 't/1' } as const

describe('moment reads', () => {
  it('keeps the live timeline exactly as before: same path, same key', () => {
    const read: MomentRead = { source: session, binding: { kind: 'current' } }
    expect(timelinePath(read, 'c 1')).toBe('/sessions/s%201/coroutines/c%201/timeline')
    expect(timelineKey(read, 'c 1')).toEqual(['sessions', 's 1', 'coroutines', 'c 1', 'timeline'])
  })

  it('binds a replay read to its cursor', () => {
    const read: MomentRead = { source: session, binding: { kind: 'replay', atSeq: 42 } }
    expect(timelinePath(read, 'c')).toBe('/sessions/s%201/coroutines/c/timeline?atSeq=42')
    expect(timelineKey(read, 'c')).toEqual(['sessions', 's 1', 'coroutines', 'c', 'timeline', 'atSeq', 42])
    expect(hierarchyPath(read)).toBe('/sessions/s%201/hierarchy?atSeq=42')
  })

  it('binds a shared view to the health it loaded, with its cut when there is one', () => {
    const withCut: MomentRead = { source: shared, binding: { kind: 'bound', asOfSeq: 7, asOfCut: 'cut-1' } }
    expect(timelinePath(withCut, 'c')).toBe('/shared/t%2F1/coroutines/c/timeline?asOfSeq=7&asOfCut=cut-1')
    expect(timelineKey(withCut, 'c')).toEqual(['shared', 't/1', 'coroutines', 'c', 'timeline', 'asOfSeq', 7, 'cut-1'])
    const noCut: MomentRead = { source: shared, binding: { kind: 'bound', asOfSeq: 7, asOfCut: null } }
    expect(timelinePath(noCut, 'c')).toBe('/shared/t%2F1/coroutines/c/timeline?asOfSeq=7')
  })

  it('replays a shared view through the share token', () => {
    const read: MomentRead = { source: shared, binding: { kind: 'replay', atSeq: 3 } }
    expect(timelinePath(read, 'c')).toBe('/shared/t%2F1/coroutines/c/timeline?atSeq=3')
    expect(hierarchyPath(read)).toBe('/shared/t%2F1/hierarchy?atSeq=3')
  })

  it('keys the hierarchy per moment, and builds the health read Part 7 will use', () => {
    const replay: MomentRead = { source: session, binding: { kind: 'replay', atSeq: 5 } }
    expect(hierarchyKey(replay)).toEqual(['sessions', 's 1', 'hierarchy', 'atSeq', 5])
    expect(healthPath(replay)).toBe('/sessions/s%201/health?atSeq=5')
    expect(healthPath({ source: shared, binding: { kind: 'bound', asOfSeq: 7 } })).toBe('/shared/t%2F1/health')
  })

  it('moves a replay read to another seq, and leaves a bound read on its cut', () => {
    expect(readAt({ source: session, binding: { kind: 'replay', atSeq: 5 } }, 9).binding).toEqual({ kind: 'replay', atSeq: 9 })
    const bound: MomentRead = { source: shared, binding: { kind: 'bound', asOfSeq: 7, asOfCut: 'k' } }
    expect(readAt(bound, 9)).toBe(bound)
  })
})

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { ENDINGS_KEY, rememberEnding, rememberedEnding } from './ending-memory'

beforeEach(() => localStorage.clear())

describe('ending memory', () => {
  it('does not know a session it was never told about', () => {
    expect(rememberedEnding('s-1')).toBeUndefined()
  })

  it('remembers a second ending by its variant id, and the usual one as null', () => {
    rememberEnding('s-1', 'card-declined')
    rememberEnding('s-2', null)
    expect(rememberedEnding('s-1')).toBe('card-declined')
    expect(rememberedEnding('s-2')).toBeNull()
  })

  it('keeps the latest ending of a session, and the 50 most recent sessions', () => {
    rememberEnding('s-0', 'card-declined')
    rememberEnding('s-0', null)
    expect(rememberedEnding('s-0')).toBeNull()
    for (let i = 1; i <= 50; i++) rememberEnding(`s-${i}`, 'card-declined')
    expect(rememberedEnding('s-0')).toBeUndefined()
    expect(rememberedEnding('s-1')).toBe('card-declined')
  })

  it('reads damaged or missing storage as knowing nothing, and survives a failed write', () => {
    localStorage.setItem(ENDINGS_KEY, '{not json')
    expect(rememberedEnding('s-1')).toBeUndefined()
    localStorage.setItem(ENDINGS_KEY, JSON.stringify([['s-1', 7], 'x', ['s-2', 'card-declined']]))
    expect(rememberedEnding('s-1')).toBeUndefined()
    expect(rememberedEnding('s-2')).toBe('card-declined')
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('full')
    })
    expect(() => rememberEnding('s-3', null)).not.toThrow()
    setItem.mockRestore()
  })
})

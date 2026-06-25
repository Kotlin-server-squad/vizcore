import { describe, it, expect } from 'vitest'
import { isLibraryFrame, isUserFrame } from './source-frames'

describe('isLibraryFrame', () => {
  it('classifies kotlinx.coroutines frames as library', () => {
    expect(isLibraryFrame('kotlinx.coroutines.delay')).toBe(true)
  })

  it('classifies kotlin.coroutines intrinsics as library', () => {
    expect(isLibraryFrame('kotlin.coroutines.intrinsics.foo')).toBe(true)
  })

  it('classifies kotlin stdlib frames as library', () => {
    expect(isLibraryFrame('kotlin.collections.map')).toBe(true)
  })

  it('classifies java/jdk/sun frames as library', () => {
    expect(isLibraryFrame('java.lang.Thread.run')).toBe(true)
    expect(isLibraryFrame('jdk.internal.misc.Unsafe.park')).toBe(true)
    expect(isLibraryFrame('sun.nio.ch.SocketChannelImpl.read')).toBe(true)
  })

  it('classifies app package frames as NOT library', () => {
    expect(isLibraryFrame('com.acme.app.OrderService.process')).toBe(false)
  })

  it('treats null/empty/undefined as library (never a jump target)', () => {
    expect(isLibraryFrame('')).toBe(true)
    expect(isLibraryFrame(null)).toBe(true)
    expect(isLibraryFrame(undefined)).toBe(true)
  })
})

describe('isUserFrame', () => {
  it('is the strict negation of isLibraryFrame', () => {
    expect(isUserFrame('com.acme.app.OrderService.process')).toBe(true)
    expect(isUserFrame('kotlinx.coroutines.delay')).toBe(false)
  })

  it('returns false for empty/null/undefined', () => {
    expect(isUserFrame('')).toBe(false)
    expect(isUserFrame(null)).toBe(false)
    expect(isUserFrame(undefined)).toBe(false)
  })
})

import { describe, it, expect } from 'vitest'
import { shouldRedirectBareCompare } from './compare/index'

/**
 * Scenarios and Gallery are retired outright — they were pickers, with no
 * shareable state to preserve. Compare is different: `/compare?a=&b=` is a
 * shareable URL (D-10), so only the *bare* form is retired.
 */
describe('retired destinations', () => {
  it('redirects a bare /compare, which was a picker destination', () => {
    expect(shouldRedirectBareCompare({})).toBe(true)
    expect(shouldRedirectBareCompare({ a: undefined, b: undefined })).toBe(true)
  })

  it('preserves a shared /compare URL carrying session ids', () => {
    expect(shouldRedirectBareCompare({ a: 'sess-1', b: 'sess-2' })).toBe(false)
  })

  it('preserves a half-filled compare URL rather than discarding the selection', () => {
    expect(shouldRedirectBareCompare({ a: 'sess-1' })).toBe(false)
    expect(shouldRedirectBareCompare({ b: 'sess-2' })).toBe(false)
  })
})

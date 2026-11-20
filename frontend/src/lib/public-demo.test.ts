import { describe, it, expect, afterEach } from 'vitest'
import { isPublicDemoVisitor } from './public-demo'
import { clearToken, setToken } from './auth-store'

/** A token shaped like the backend's: only the payload matters here. */
function jwtWithRole(role: string): string {
  const payload = btoa(JSON.stringify({ sub: 'owner', role })).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `header.${payload}.signature`
}

afterEach(() => clearToken())

describe('isPublicDemoVisitor', () => {
  it('is false on a server that is not a public demo, or has not said yet', () => {
    expect(isPublicDemoVisitor(undefined)).toBe(false)
    expect(isPublicDemoVisitor({})).toBe(false)
    expect(isPublicDemoVisitor({ publicDemo: false })).toBe(false)
  })

  it('is true on a public demo for someone who is not signed in', () => {
    expect(isPublicDemoVisitor({ publicDemo: true })).toBe(true)
  })

  it('is false for a signed-in admin, who may still do everything', () => {
    setToken(jwtWithRole('ADMIN'))
    expect(isPublicDemoVisitor({ publicDemo: true })).toBe(false)
  })

  it('is true for a signed-in user who is not an admin, and for a token it cannot read', () => {
    setToken(jwtWithRole('RUNNER'))
    expect(isPublicDemoVisitor({ publicDemo: true })).toBe(true)
    setToken('not-a-jwt')
    expect(isPublicDemoVisitor({ publicDemo: true })).toBe(true)
  })
})

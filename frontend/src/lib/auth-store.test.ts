import { describe, it, expect, beforeEach, vi } from 'vitest'
import { getToken, setToken, clearToken } from './auth-store'

const STORAGE_KEY = 'vizcore.jwt'

describe('auth-store', () => {
  beforeEach(() => {
    localStorage.clear()
    clearToken()
  })

  it('setToken persists to both localStorage and the in-memory copy', () => {
    setToken('jwt-abc')

    expect(localStorage.getItem(STORAGE_KEY)).toBe('jwt-abc')
    expect(getToken()).toBe('jwt-abc')
  })

  it('getToken reads the in-memory copy (no token → null)', () => {
    expect(getToken()).toBeNull()
    setToken('jwt-xyz')
    expect(getToken()).toBe('jwt-xyz')
  })

  it('clearToken wipes both the in-memory copy and localStorage', () => {
    setToken('jwt-to-clear')
    clearToken()

    expect(getToken()).toBeNull()
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('rehydrates the in-memory token from localStorage on a fresh module load (D-06)', async () => {
    // Seed storage, then re-import the module fresh so its module-init
    // rehydration reads the seeded value.
    localStorage.setItem(STORAGE_KEY, 'rehydrated-jwt')
    vi.resetModules()
    const freshModule = await import('./auth-store')

    expect(freshModule.getToken()).toBe('rehydrated-jwt')
  })
})

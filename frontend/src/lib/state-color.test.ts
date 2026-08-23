import { describe, it, expect } from 'vitest'
import { CoroutineState } from '@/types/api'
import { stateColor, stateToken, leakColor } from './state-color'
import { palette } from '@/styles/palette'

describe('stateColor', () => {
  it('maps running states to primary', () => {
    expect(stateColor(CoroutineState.ACTIVE)).toBe(palette.primary)
  })

  it('maps suspended and cancelled to warning', () => {
    expect(stateColor(CoroutineState.SUSPENDED)).toBe(palette.warning)
    expect(stateColor(CoroutineState.CANCELLED)).toBe(palette.warning)
  })

  it('maps completed to success', () => {
    expect(stateColor(CoroutineState.COMPLETED)).toBe(palette.success)
  })

  it('maps failed to danger', () => {
    expect(stateColor(CoroutineState.FAILED)).toBe(palette.danger)
  })

  it('maps created and waiting-for-children to their own neutrals', () => {
    expect(stateColor(CoroutineState.CREATED)).toBe(palette.created)
    expect(stateColor(CoroutineState.WAITING_FOR_CHILDREN)).toBe(palette.primary)
  })

  it('covers every member of CoroutineState', () => {
    for (const state of Object.values(CoroutineState)) {
      expect(stateColor(state)).toMatch(/^#[0-9a-f]{6}$/i)
    }
  })

  it('returns a CSS variable reference from stateToken', () => {
    expect(stateToken(CoroutineState.SUSPENDED)).toBe('var(--warning)')
  })

  it('colours a potential leak amber, never danger red', () => {
    expect(leakColor()).toBe(palette.warning)
    expect(leakColor()).not.toBe(palette.danger)
  })
})

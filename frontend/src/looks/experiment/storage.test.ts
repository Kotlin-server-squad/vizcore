import { describe, it, expect, beforeEach, vi } from 'vitest'
import { EXPERIMENT_STORAGE_KEY } from './constants'
import { forgetExperimentState, readExperimentState, updateExperimentState } from './storage'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

beforeEach(() => {
  localStorage.clear()
  forgetExperimentState()
  vi.restoreAllMocks()
})

describe('the experiment’s browser state', () => {
  it('makes a random anonymous id once and keeps it', () => {
    const first = readExperimentState()
    expect(first.anonId).toMatch(UUID)
    forgetExperimentState()
    expect(readExperimentState().anonId).toBe(first.anonId)
    expect(JSON.parse(localStorage.getItem(EXPERIMENT_STORAGE_KEY)!)).toMatchObject({
      anonId: first.anonId,
    })
  })

  it('stores updates', () => {
    updateExperimentState(s => ({ ...s, entry: { look: 'b', kind: 'assigned' }, assigned: 'b' }))
    forgetExperimentState()
    expect(readExperimentState()).toMatchObject({
      entry: { look: 'b', kind: 'assigned' },
      assigned: 'b',
    })
  })

  it('drops garbage and unknown looks instead of failing', () => {
    localStorage.setItem(
      EXPERIMENT_STORAGE_KEY,
      JSON.stringify({
        anonId: 'x',
        entry: { look: 'zzz', kind: 'assigned' },
        assigned: 'q',
        voted: { b: 9, c: 4 },
      })
    )
    const state = readExperimentState()
    expect(state.anonId).toMatch(UUID)
    expect(state.entry).toBeUndefined()
    expect(state.assigned).toBeUndefined()
    expect(state.voted).toEqual({ c: 4 })
    localStorage.setItem(EXPERIMENT_STORAGE_KEY, '{nope')
    forgetExperimentState()
    expect(readExperimentState().anonId).toMatch(UUID)
  })

  it('keeps working for the visit when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const id = readExperimentState().anonId
    updateExperimentState(s => ({ ...s, hintDismissed: true }))
    expect(readExperimentState()).toMatchObject({ anonId: id, hintDismissed: true })
  })
})

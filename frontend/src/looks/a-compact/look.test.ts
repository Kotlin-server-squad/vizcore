import { describe, it, expect } from 'vitest'
import { compact } from '.'

describe('A · Compact', () => {
  it('is the trial look "a", labelled as the ⋯ menu shows it', () => {
    expect(compact).toEqual({ id: 'a', label: 'A · Compact', trial: true })
  })
})

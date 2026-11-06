import { describe, it, expect } from 'vitest'
import { calm } from '.'

describe('B · Calm', () => {
  it('is the trial look "b", labelled as the ⋯ menu shows it', () => {
    expect(calm).toEqual({ id: 'b', label: 'B · Calm', trial: true })
  })
})

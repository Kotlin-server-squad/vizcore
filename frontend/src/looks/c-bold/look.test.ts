import { describe, it, expect } from 'vitest'
import { bold } from '.'

describe('C · Bold', () => {
  it('is the trial look "c", labelled as the ⋯ menu shows it', () => {
    expect(bold).toEqual({ id: 'c', label: 'C · Bold', trial: true })
  })
})

import { describe, it, expect } from 'vitest'
import { validateSearch } from './index'

describe('/gallery search params', () => {
  it('keeps the selected tab', () => {
    expect(validateSearch({ group: 'basics' })).toEqual({ group: 'basics' })
    expect(validateSearch({ group: 'mistakes' })).toEqual({ group: 'mistakes' })
  })

  it('drops a missing, blank or non-string tab', () => {
    expect(validateSearch({})).toEqual({ group: undefined })
    expect(validateSearch({ group: '  ' })).toEqual({ group: undefined })
    expect(validateSearch({ group: 3 })).toEqual({ group: undefined })
  })
})

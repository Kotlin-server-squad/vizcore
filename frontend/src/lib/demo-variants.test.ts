import { describe, it, expect } from 'vitest'
import { playLabel, secondEnding, validVariant, variantParams } from './demo-variants'

describe('demo variants', () => {
  it('gives each Real life demo its second ending, with the parameter its route reads', () => {
    expect(secondEnding('order-processing')).toEqual({ id: 'card-declined', param: 'fail', outcome: 'card declined' })
    expect(secondEnding('user-registration')).toEqual({ id: 'email-retried', param: 'failEmail', outcome: 'email retried' })
    expect(secondEnding('report-generation')).toEqual({
      id: 'slow-api-cancelled',
      param: 'timeout',
      outcome: 'slow API cancelled',
    })
  })

  it('knows no second ending for other demos', () => {
    expect(secondEnding('nested')).toBeUndefined()
    expect(secondEnding(undefined)).toBeUndefined()
    expect(secondEnding('constructor')).toBeUndefined()
  })

  it('accepts only the variant of that demo', () => {
    expect(validVariant('order-processing', 'card-declined')?.param).toBe('fail')
    expect(validVariant('order-processing', 'email-retried')).toBeUndefined()
    expect(validVariant('nested', 'card-declined')).toBeUndefined()
    expect(validVariant('order-processing', 'fail')).toBeUndefined()
    expect(validVariant('order-processing', true)).toBeUndefined()
    expect(validVariant('order-processing', undefined)).toBeUndefined()
  })

  it('labels the button by the outcome and sends the route its parameter', () => {
    const variant = secondEnding('user-registration')!
    expect(playLabel(variant.outcome)).toBe('Play: email retried')
    expect(variantParams(variant)).toEqual({ failEmail: 'true' })
  })
})

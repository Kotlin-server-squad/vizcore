import { describe, it, expect } from 'vitest'
import type { EventCategories } from '@/hooks/use-event-categories'
import { deriveRung, RUNG_LABEL } from './fidelity-rung'

const categories = (over: Partial<EventCategories> = {}): EventCategories => ({
  hasChannels: false,
  hasFlowOps: false,
  hasSyncPrimitives: false,
  hasJobs: false,
  hasValidation: true,
  ...over,
})

describe('deriveRung', () => {
  it('classifies a scenario-prefixed session as demo', () => {
    expect(deriveRung('scenario-Nested Coroutines', categories())).toBe('demo')
  })

  it('keeps a demo session on the demo rung even with wrapper events', () => {
    // A canned scenario uses the wrappers internally; that is not the user
    // instrumenting their own code.
    expect(deriveRung('scenario-Channel Fan-Out', categories({ hasChannels: true }))).toBe('demo')
  })

  it('classifies a real session with no wrapper evidence as attached', () => {
    expect(deriveRung('checkout-service', categories())).toBe('attached')
  })

  it.each([
    ['hasSyncPrimitives', { hasSyncPrimitives: true }],
    ['hasFlowOps', { hasFlowOps: true }],
    ['hasChannels', { hasChannels: true }],
  ])('promotes a real session to instrumented on %s', (_name, over) => {
    expect(deriveRung('checkout-service', categories(over))).toBe('instrumented')
  })

  it('does NOT promote on hasJobs alone — job events are not confirmed wrapper-only', () => {
    expect(deriveRung('checkout-service', categories({ hasJobs: true }))).toBe('attached')
  })

  it('labels every rung', () => {
    expect(RUNG_LABEL.demo).toBe('DEMO')
    expect(RUNG_LABEL.attached).toBe('ATTACHED')
    expect(RUNG_LABEL.instrumented).toBe('INSTRUMENTED')
  })
})

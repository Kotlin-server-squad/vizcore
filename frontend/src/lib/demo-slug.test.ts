import { describe, it, expect } from 'vitest'
import { demoSlug } from './demo-slug'
import catalog from '@/test/demo-catalog.json'

describe('demoSlug', () => {
  it('replaces every / with -', () => {
    expect(demoSlug('nested')).toBe('nested')
    expect(demoSlug('flow/simple')).toBe('flow-simple')
    expect(demoSlug('sync/semaphore/producer-consumer')).toBe('sync-semaphore-producer-consumer')
  })

  it('gives every catalog demo its own slug', () => {
    const slugs = catalog.scenarios.map((s) => demoSlug(s.id))
    expect(new Set(slugs).size).toBe(slugs.length)
  })
})

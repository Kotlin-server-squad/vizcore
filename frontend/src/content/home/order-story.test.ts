import { describe, it, expect } from 'vitest'
import { ORDER_STORY, ORDER_STORY_DEMO, shortLabel } from './order-story'
import { willSeeFor } from '@/content/gallery/will-see'
import { secondEnding } from '@/lib/demo-variants'
import catalog from '@/test/demo-catalog.json'

describe('the front door\'s order story', () => {
  it('names the demo\'s coroutines, in the order of its "What you will see" picture', () => {
    const picture = willSeeFor(ORDER_STORY.demoId)!
    expect(ORDER_STORY.bars.map((bar) => bar.label)).toEqual(picture.coroutines.map((c) => c.label))
  })

  it('keeps every bar inside the run, the root spanning all of it', () => {
    const [root, ...children] = ORDER_STORY.bars
    expect(root).toMatchObject({ from: 0, to: ORDER_STORY.endSeconds })
    for (const bar of children) {
      expect(bar.from).toBeGreaterThanOrEqual(0)
      expect(bar.to).toBeGreaterThan(bar.from)
      expect(bar.to).toBeLessThanOrEqual(ORDER_STORY.endSeconds)
    }
    // The root waits for its children from the moment the last step it joins has ended.
    expect(root!.waitingFrom).toBe(11.5)
  })

  it('shortens a label to its function', () => {
    expect(shortLabel('OrderService.processOrder')).toBe('processOrder')
    expect(shortLabel('plain')).toBe('plain')
  })

  it('is a demo the server lists, with a second ending', () => {
    expect(catalog.scenarios.map((s) => s.id)).toContain(ORDER_STORY_DEMO.id)
    expect(secondEnding(ORDER_STORY_DEMO.id)).toBeDefined()
  })
})

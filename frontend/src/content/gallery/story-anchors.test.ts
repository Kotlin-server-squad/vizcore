import { describe, it, expect } from 'vitest'
import { GUIDES } from './guides'
import { ANCHOR_EVENTS, STORY_ANCHORS, storyFor } from './story-anchors'
import { CoroutineState } from '@/types/api'
import catalog from '@/test/demo-catalog.json'

const basics = catalog.scenarios.filter((s) => s.group === 'basics').map((s) => s.id)
const states = new Set<string>(Object.values(CoroutineState))

describe('story anchors (S-9, "Story anchors")', () => {
  it('gives every Basics demo a story', () => {
    expect(basics).toHaveLength(7)
    for (const id of basics) expect(storyFor(id), id).not.toBeNull()
  })

  it('has one entry per guide step, in step order', () => {
    for (const [id, steps] of Object.entries(STORY_ANCHORS)) {
      expect(GUIDES[id], id).toBeDefined()
      expect(steps, id).toHaveLength(GUIDES[id]!.steps.length)
    }
  })

  it('anchors on a lifecycle event of one coroutine, counted from 1', () => {
    for (const [id, steps] of Object.entries(STORY_ANCHORS)) {
      for (const step of steps) {
        if (!step.anchor) continue
        expect(ANCHOR_EVENTS, `${id}: ${step.anchor.event}`).toContain(step.anchor.event)
        expect(step.anchor.coroutine.length, id).toBeGreaterThan(0)
        expect(Number.isInteger(step.anchor.occurrence) && step.anchor.occurrence >= 1, id).toBe(true)
      }
    }
  })

  it('expects only real states, and only at an anchor', () => {
    for (const [id, steps] of Object.entries(STORY_ANCHORS)) {
      for (const step of steps) {
        if (!step.expect) continue
        expect(step.anchor, id).toBeDefined()
        for (const state of Object.values(step.expect)) expect(states.has(state), `${id}: ${state}`).toBe(true)
      }
    }
  })

  it('asks predictions with two or three answers and one right one', () => {
    let count = 0
    for (const [id, steps] of Object.entries(STORY_ANCHORS)) {
      for (const step of steps) {
        if (!step.prediction) continue
        count += 1
        const { question, answers, correct } = step.prediction
        expect(question.endsWith('?'), id).toBe(true)
        expect(answers.length >= 2 && answers.length <= 3, id).toBe(true)
        expect(Number.isInteger(correct) && correct >= 0 && correct < answers.length, id).toBe(true)
      }
    }
    expect(count).toBeGreaterThanOrEqual(7)
  })

  it('reads chapters from the guide, with their anchors', () => {
    const story = storyFor('nested')!
    expect(story.map((c) => c.title)).toEqual(GUIDES.nested!.steps.map((s) => s.title))
    expect(story[0]).toMatchObject({
      index: 0,
      text: GUIDES.nested!.steps[0]!.text,
      anchor: { event: 'CoroutineSuspended', coroutine: 'fetch-product', occurrence: 2 },
    })
  })

  it('has no story for a demo without anchors', () => {
    expect(storyFor('channel-buffered')).toBeNull()
    expect(storyFor('no-such-demo')).toBeNull()
  })
})

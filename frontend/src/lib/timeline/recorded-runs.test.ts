import { describe, it, expect } from 'vitest'
import { normalizeEvents } from '../utils'
import { buildTimeline } from './build-timeline'
import { expectAgreesAtEveryCursor } from './test-agreement'
import combined from './fixtures/combined-ecommerce.json'
import mutex from './fixtures/mutex-bank-transfer.json'
import producerConsumer from './fixtures/semaphore-producer-consumer.json'

/**
 * Recorded runs of the Mutex, Semaphore and Combined demos (events as `GET /api/sessions/{id}/events` returned them):
 * the intervals built from them match their events at every cursor (spec Part 10's tests).
 */
const runs = {
  'Mutex · bank transfer': mutex,
  'Semaphore · producer–consumer (permits released by coroutines that hold none)': producerConsumer,
  'Combined · e-commerce': combined,
} as const

describe('the timeline of a recorded run', () => {
  it.each(Object.entries(runs))('%s agrees with the tree and the second line at every cursor', (_, raw) => {
    expectAgreesAtEveryCursor(normalizeEvents(raw as never))
  })

  it('draws a finished row for every coroutine of a finished run, each ending in its terminal event', () => {
    const t = buildTimeline(normalizeEvents(combined as never))
    const rows = [...t.rows.values()].filter(r => !r.createdBefore)
    expect(rows.length).toBeGreaterThan(3)
    for (const row of rows) {
      expect(row.end, row.id).not.toBeNull()
      expect(t.events[row.end!.at]!.kind).toMatch(/^coroutine\.(completed|failed|cancelled)$/)
    }
  })

  it('shows a release whose holder is unknown with its mark, in the run that has them', () => {
    const t = buildTimeline(normalizeEvents(producerConsumer as never))
    expect(t.lanes.some(l => l.unknownMarks.length > 0)).toBe(true)
  })
})

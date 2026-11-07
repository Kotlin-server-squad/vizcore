import { expect } from 'vitest'
import { projectCoroutines } from '../projections/project-coroutines'
import { projectActivity } from '../activity'
import { buildTimeline } from './build-timeline'
import { holdsAt, stateAt } from './at-cursor'
import type { VizEvent } from '@/types/api'

/**
 * At every cursor, the timeline says what the tree and the node's second line say from the same events: each row's
 * state is `projectCoroutines`' over the events up to it, and its lock and permit bands are `projectActivity`'s
 * "holds" / "may hold".
 */
export function expectAgreesAtEveryCursor(events: readonly VizEvent[]) {
  const timeline = buildTimeline(events)
  const sorted = timeline.events
  for (let c = 0; c < sorted.length; c++) {
    const upTo = sorted.slice(0, c + 1) as VizEvent[]
    for (const node of projectCoroutines(upTo)) {
      const row = timeline.rows.get(node.id)
      expect(row && stateAt(timeline, row, c), `${node.id} at ${c}`).toBe(node.state)
    }
    const activity = projectActivity(upTo)
    for (const row of timeline.rows.values()) {
      const holds = holdsAt(timeline, row.id, c)
      const text = activity.get(row.id)?.text ?? ''
      for (const lane of timeline.lanes) {
        const mine = holds.filter(h => h.resourceId === lane.resourceId)
        const name = lane.name
        if (lane.kind === 'lock') {
          expect(mine.length > 0, `${row.id} holds ${name} at ${c}`).toBe(text.includes(`holds ${name}`))
        } else {
          const certain = mine.filter(h => h.certain).length
          const mayHold = mine.some(h => !h.certain)
          expect(mayHold, `${row.id} may hold ${name} at ${c}`).toBe(text.includes(`may hold ${/^[aeiou]/i.test(name) ? 'an' : 'a'} ${name} permit`))
          const held = text.match(new RegExp(`holds (?:an? ${name} permit|(\\d+) ${name} permits)`))
          expect(certain, `${row.id} holds ${name} permits at ${c}`).toBe(held ? Number(held[1] ?? 1) : 0)
        }
      }
    }
  }
}


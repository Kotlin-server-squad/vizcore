import { describe, it, expect } from 'vitest'
import { firstViewCanvasPx } from './graph-first-view'

// The graph's own spacing (CoroutineTreeGraph.tsx): 64 px between rows, 32 px padding, a 40 px toolbar inset.
const spacing = { vGap: 64, padding: 32, toolbarInset: 40 }

describe('the canvas the first view needs (root and its first row of children)', () => {
  it('is two rows at the look’s fit floor: A at full size', () => {
    expect(firstViewCanvasPx({ ...spacing, cardHeight: 142, scale: 1 })).toBe(40 + 32 + 142 + 64 + 142 + 32)
  })

  it('scales with a look that may zoom out a little: B at 0.93', () => {
    expect(firstViewCanvasPx({ ...spacing, cardHeight: 140, scale: 0.93 })).toBe(Math.ceil(40 + 408 * 0.93))
  })

  it('uses half size for Classic, which has no fit floor and shrinks to stay readable', () => {
    expect(firstViewCanvasPx({ ...spacing, cardHeight: 276, scale: 0.5 })).toBe(40 + 340)
  })
})

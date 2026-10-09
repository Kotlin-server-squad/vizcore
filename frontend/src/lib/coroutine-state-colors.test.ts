import { describe, it, expect } from 'vitest'
import { CoroutineState } from '@/types/api'
import { palette } from '@/styles/palette'
import { deriveStateCounts } from './state-counts'
import { stateColor, getStateColors } from './coroutine-state-colors'

const ALL_STATES = Object.values(CoroutineState)

describe('stateColor', () => {
  it('returns a palette value for every coroutine state', () => {
    const paletteValues = new Set<string>(Object.values(palette))

    for (const state of ALL_STATES) {
      const color = stateColor(state)
      // The point of this assertion: a hex that is NOT in the palette means a
      // second colour source has appeared beside it, which is the exact thing
      // the token layer exists to prevent.
      expect(paletteValues.has(color), `${state} -> ${color} is not in the palette`).toBe(true)
    }
  })

  it('gives running states the primary hue', () => {
    expect(stateColor(CoroutineState.ACTIVE)).toBe(palette.primary)
    // WAITING_FOR_CHILDREN is running work: the parent's body has finished but
    // its scope has not (N-1, and state-counts already buckets it there).
    expect(stateColor(CoroutineState.WAITING_FOR_CHILDREN)).toBe(palette.primary)
  })

  it('gives suspended and cancelled the warning hue', () => {
    expect(stateColor(CoroutineState.SUSPENDED)).toBe(palette.warning)
    // Amber, not grey: the state bar has drawn cancelled amber since plan 3
    // and the palette itself files it under warning (N-2).
    expect(stateColor(CoroutineState.CANCELLED)).toBe(palette.warning)
  })

  it('gives completed and failed their own hues', () => {
    expect(stateColor(CoroutineState.COMPLETED)).toBe(palette.success)
    expect(stateColor(CoroutineState.FAILED)).toBe(palette.danger)
  })

  it('gives created the neutral hue', () => {
    expect(stateColor(CoroutineState.CREATED)).toBe(palette.created)
  })

  it('falls back to the created hue for an unknown state rather than throwing', () => {
    // Canvas and SVG call this per node; a new backend state must degrade to a
    // colour, never to a crash.
    expect(stateColor('SOME_FUTURE_STATE')).toBe(palette.created)
  })
})

describe('getStateColors and stateColor agree', () => {
  it('resolves the same family for every state', () => {
    // The class strings and the hex come from one map, so a change to either
    // cannot silently diverge from the other.
    const family: Record<string, string> = {
      [palette.primary]: 'primary',
      [palette.warning]: 'warning',
      [palette.success]: 'success',
      [palette.danger]: 'danger',
      [palette.created]: 'default',
    }

    for (const state of ALL_STATES) {
      const expected = family[stateColor(state)]
      expect(getStateColors(state).text, `${state}`).toContain(expected)
    }
  })
})

describe('the canvas agrees with the state bar', () => {
  /** Which chip each state is counted under, read from state-counts itself. */
  function bucketOf(state: string): string {
    const counts = deriveStateCounts([
      { id: 'c', jobId: 'j', parentId: null, scopeId: 's', label: null, state } as never,
    ])
    return (['running', 'suspended', 'completed', 'cancelled', 'failed'] as const).find(
      b => counts[b] === 1,
    )!
  }

  /** The chip colour StateBar draws for each bucket. */
  const BUCKET_HUE: Record<string, string> = {
    running: palette.primary,
    suspended: palette.warning,
    completed: palette.success,
    cancelled: palette.warning,
    failed: palette.danger,
  }

  it('draws every state in the colour its own chip carries', () => {
    // Derived from state-counts rather than restated, so the two modules cannot
    // drift apart again. They did: the amber Cancelled chip used to filter the
    // canvas to coroutines the canvas then drew grey.
    for (const state of ALL_STATES) {
      // CREATED is the one exception: it is counted as running (so the buckets
      // sum to the total) but drawn neutral, because nothing has started yet.
      if (state === CoroutineState.CREATED) continue
      expect(stateColor(state), `${state}`).toBe(BUCKET_HUE[bucketOf(state)])
    }
  })

  it('keeps WAITING_FOR_CHILDREN tellable from ACTIVE without a separate hue', () => {
    const waiting = getStateColors(CoroutineState.WAITING_FOR_CHILDREN)
    const active = getStateColors(CoroutineState.ACTIVE)

    expect(waiting.hue).toBe(active.hue)
    expect(waiting.Icon).not.toBe(active.Icon)
    expect(waiting.animation).not.toBe(active.animation)
  })

  it('no longer maps any state onto the meaningless secondary token', () => {
    // `secondary` has no vizcore meaning and no palette entry (spec C-1).
    for (const state of ALL_STATES) {
      expect(getStateColors(state).chipColor, `${state}`).not.toBe('secondary')
    }
  })
})

import { LOOKS, parseLook, type LookId } from '../registry'

export type EntryKind = 'assigned' | 'chose'

export interface Entry {
  look: LookId
  kind: EntryKind
}

/** One look, at random, in proportion to its weight; unknown looks and weights of 0 never come up. */
export function pickWeighted(weights: Record<string, number>, random: () => number): LookId {
  const candidates = LOOKS.map(l => ({ id: l.id, weight: Math.max(0, weights[l.id] ?? 0) })).filter(
    c => c.weight > 0
  )
  const total = candidates.reduce((sum, c) => sum + c.weight, 0)
  if (total <= 0) return 'classic'
  let point = random() * total
  for (const c of candidates) {
    if (point < c.weight) return c.id
    point -= c.weight
  }
  return candidates[candidates.length - 1]!.id
}

/**
 * How a browser joins the experiment, the first time it opens a session page
 * while it runs: a `?look=` link wins, then a look the person already picked
 * (both "chose"); otherwise the weights assign one.
 */
export function decideEntry(input: {
  link?: string
  choice: LookId | null
  weights: Record<string, number>
  random: () => number
}): Entry {
  if (input.link !== undefined) return { look: parseLook(input.link), kind: 'chose' }
  if (input.choice !== null) return { look: input.choice, kind: 'chose' }
  return { look: pickWeighted(input.weights, input.random), kind: 'assigned' }
}

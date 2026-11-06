import type { LookCard } from '../types'

/**
 * Today's card. The height holds the tallest one (measured): border + padding
 * 36, a two-line name, the state and the second line (S-6) 80 + 12, four
 * detail rows 92, the progress bar of a running coroutine 16 — 236 px.
 */
export const classicCard: LookCard = { width: 280, height: 240 }

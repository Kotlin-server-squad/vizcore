import type { LookCard } from '../types'
import './card.css'

/**
 * B's graph card (sketch K-B-Calm): the name, a state pill and two lines for what it waits for or holds.
 * 200 × 120 holds them at card.css's sizes (13 + 22 + 6 + 22 + 6 + 2 × 18 + 13 = 118 px tall).
 */
export const calmCard: LookCard = { width: 200, height: 120 }

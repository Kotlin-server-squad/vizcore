import type { LookCard } from '../types'
import './card.css'

/**
 * B's graph card (sketch K-B-Calm): the name, a state pill and the second line, stacked.
 * 200 × 82 holds them at card.css's sizes (border 2 + padding 14 + 20 + 4 + 20 + 4 + 17 = 81 px), and is
 * short enough that on 1440 × 900 with the default dock the first view shows the root and its whole first
 * row of children. The fit stops at 0.93, where the smallest card text (13 px) is still 12 px on screen.
 */
export const calmCard: LookCard = { width: 200, height: 82, fitMinZoom: 0.93 }

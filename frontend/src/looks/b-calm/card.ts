import type { LookCard } from '../types'
import './card.css'

/**
 * B's graph card (sketch K-B-Calm): the name, a state pill and the second line, stacked.
 * 200 × 82 holds them at card.css's sizes (border 2 + padding 14 + 20 + 4 + 20 + 4 + 17 = 81 px), and is
 * short enough that on 1440 × 900 with the default dock the first view shows the root and its whole first
 * row of children; Part 7's row of lifetime, creation site and badges adds 4 + 18 (104 px, measured with two badges);
 * the name on three lines (a 16 px class line and the method on up to two 20 px lines instead of one) adds 36: 140 px.
 * The fit stops at 0.93, where the smallest card text (13 px) is still 12 px on screen.
 */
// The dock starts at 27 % (not 40 %), so the taller card still shows the root and its first row in the first view
// (look.test.ts has the sum).
export const calmCard: LookCard = { width: 200, height: 140, fitMinZoom: 0.93, dockShare: 0.27 }

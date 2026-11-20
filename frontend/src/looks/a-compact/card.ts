import type { LookCard } from '../types'
import './card.css'

/**
 * A's graph card (sketch K-A-Compact): the state beside its dot, the name under it, then the second
 * line on up to two lines. 142 px tall: 6 px padding and a 1 px border on each side, the 18 px state row, the
 * name on three 18 px lines (the class on a row of its own, across the card, and the method on up to two), two
 * 16 px lines and Part 7's 16 px row of lifetime, creation site and badges, 2 px apart (look.test.ts has the sum).
 *
 * 188 px wide, so a root and six children (E-commerce) fit side by side at full
 * size on a 1440 px window: 6 × 188 + 5 gaps of 40 + 2 × 32 padding = 1,392 px,
 * inside the graph's 1,417 px. Its smallest text is 12 px, so the graph's fit never
 * zooms below 1 (12 px on screen).
 */
export const compactCard: LookCard = { width: 188, height: 142, fitMinZoom: 1 }

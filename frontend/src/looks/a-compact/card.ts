import type { LookCard } from '../types'
import './card.css'

/**
 * A's graph card (sketch K-A-Compact): one row of name and state, the second line
 * under it on up to two lines. 66 px tall: 6 px padding and a 1 px border on each
 * side, the 18 px name row, a 2 px gap and two 16 px lines; 84 px with Part 7's row of lifetime, creation site and
 * badges (2 px gap, 16 px).
 *
 * 188 px wide, so a root and six children (E-commerce) fit side by side at full
 * size on a 1440 px window: 6 × 188 + 5 gaps of 40 + 2 × 32 padding = 1,392 px,
 * inside the graph's 1,417 px. Its smallest text is 12 px, so the graph's fit never
 * zooms below 1 (12 px on screen).
 */
export const compactCard: LookCard = { width: 188, height: 84, fitMinZoom: 1 }

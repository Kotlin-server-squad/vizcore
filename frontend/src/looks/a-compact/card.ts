import type { LookCard } from '../types'
import './card.css'

/**
 * A's graph card (sketch K-A-Compact): one row of name and state, the second line
 * under it on up to two lines. 66 px tall: 6 px padding and a 1 px border on each
 * side, the 18 px name row, a 2 px gap and two 16 px lines. Its smallest text is
 * 12 px, so the graph's fit never zooms below 1 (12 px on screen).
 */
export const compactCard: LookCard = { width: 208, height: 66, fitMinZoom: 1 }

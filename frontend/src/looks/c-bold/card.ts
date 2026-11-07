import type { LookCard } from '../types'
import './card.css'

/** C's graph card (sketch K-C-Bold): the name in mono, a bold state pill and the second line. */
export const boldCard: LookCard = {
  // Six children (E-commerce) fit across a 1440 px window at full size: 6 × 180 + 5 × 40 + 64 ≤ 1,358.
  width: 180,
  // 128 with Part 7's row of lifetime, creation site and badges (6 px gap, 20 px pills; measured with two badges).
  height: 128,
  fitMinZoom: 1,
  // The dock starts at 17 % (153 px at 900 px tall), so the root and its first row of children
  // fit the first view at 1440×900 with the "Right now" box open, even when the box wraps to a
  // second line or the header to a second row (two rows need 40 + 32 + 104 + 64 + 104 = 344 px).
  dockShare: 0.17,
}

import type { LookCard } from '../types'
import './card.css'

/** C's graph card (sketch K-C-Bold): the name in mono, a bold state pill and the second line. */
export const boldCard: LookCard = {
  // Six children (E-commerce) fit across a 1440 px window at full size: 6 × 180 + 5 × 40 + 64 ≤ 1,358.
  width: 180,
  // 128 with Part 7's row of lifetime, creation site and badges (6 px gap, 20 px pills; measured with two badges);
  // 164 with the name on three lines (a 16 px class line and the method on up to two 20 px lines instead of one).
  height: 164,
  fitMinZoom: 1,
  // The dock starts at 17 % (153 px at 900 px tall), so the root and its first row of children
  // fit the first view at 1440×900 with the "Right now" box open on one line (two rows need
  // 40 + 32 + 164 + 64 + 164 = 464 px of the canvas's 467).
  dockShare: 0.17,
}

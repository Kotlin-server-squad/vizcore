import type { LookDefinition } from '../types'
import { boldSessionBody } from './SessionBody'
import './tokens.css'

/**
 * High contrast: near-black, saturated colour, display type, and a "Right now"
 * overview above the main view (sketch K-C-Bold). On trial; see
 * docs/superpowers/plans/2026-11-06-look-c.md.
 */
export const bold = {
  id: 'c',
  label: 'C · Bold',
  trial: true,
  slots: { SessionBody: boldSessionBody },
} as const satisfies LookDefinition

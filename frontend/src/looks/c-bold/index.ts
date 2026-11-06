import type { LookDefinition } from '../types'
import './tokens.css'

/** High contrast: near-black, saturated colour, display type. On trial (PR L0); see docs/superpowers/plans/2026-11-06-look-switch.md. */
export const bold = { id: 'c', label: 'C · Bold', trial: true } as const satisfies LookDefinition

import type { LookDefinition } from '../types'
import './tokens.css'

/** Dense, IDE-like: small type, tight spacing, square corners. On trial (PR L0); see docs/superpowers/plans/2026-11-06-look-switch.md. */
export const compact = { id: 'a', label: 'A · Compact', trial: true } as const satisfies LookDefinition

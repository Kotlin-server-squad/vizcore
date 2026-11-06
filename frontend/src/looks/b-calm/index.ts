import type { LookDefinition } from '../types'
import './tokens.css'

/** Light and airy: larger spacing, soft rounded cards. On trial (PR L0); see docs/superpowers/plans/2026-11-06-look-switch.md. */
export const calm = { id: 'b', label: 'B · Calm', trial: true } as const satisfies LookDefinition

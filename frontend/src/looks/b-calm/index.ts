import type { LookDefinition } from '../types'
import { calmHeaderSummary } from './CalmSummary'
import './tokens.css'

/**
 * Calm and clear: a white header band with a plain sentence under the title, an airy body on soft zinc,
 * soft rounded cards. On trial; see docs/superpowers/plans/2026-11-06-look-b.md.
 */
export const calm = {
  id: 'b',
  label: 'B · Calm',
  trial: true,
  slots: { HeaderSummary: calmHeaderSummary },
} as const satisfies LookDefinition

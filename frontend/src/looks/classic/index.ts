import type { LookDefinition, LookSlots } from '../types'
import { classicSessionBody } from './SessionBody'

/** Today's look. It has no CSS of its own, so it cannot drift. */
export const classic = {
  id: 'classic',
  label: 'Classic',
  trial: false,
} as const satisfies LookDefinition

/** Every slot as Classic draws it; a look that leaves a slot out gets this one. */
export const classicSlots: LookSlots = { SessionBody: classicSessionBody }

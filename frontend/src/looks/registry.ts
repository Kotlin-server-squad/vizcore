/**
 * The looks the session page can wear (PR L0, the Look switch).
 *
 * To delete a look: delete its folder and its two lines here (the import and
 * the entry in LOOKS). `LookId` then no longer has its id, so tsc finds any
 * leftover, and a stored or linked id falls back to Classic by itself.
 */
import { classic } from './classic'
import { compact } from './a-compact'
import { calm } from './b-calm'
import { bold } from './c-bold'

export const LOOKS = [classic, compact, calm, bold] as const

export type LookId = (typeof LOOKS)[number]['id']

export const DEFAULT_LOOK: LookId = classic.id

/** A known look id as it is; anything else (an unknown or deleted look, garbage) is Classic. */
export function parseLook(value: unknown): LookId {
  return LOOKS.find((look) => look.id === value)?.id ?? DEFAULT_LOOK
}

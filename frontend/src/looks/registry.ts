/**
 * The looks the session page can wear (PR L0, the Look switch).
 *
 * To delete a look: delete its folder and its lines here (its two imports, its
 * entry in LOOKS and its entry in CARDS). `LookId` then no longer has its id, so tsc finds any
 * leftover, and a stored or linked id falls back to Classic by itself.
 */
import { classic } from './classic'
import { classicCard } from './classic/card'
import { compact } from './a-compact'
import { compactCard } from './a-compact/card'
import { calm } from './b-calm'
import { calmCard } from './b-calm/card'
import { bold } from './c-bold'
import { boldCard } from './c-bold/card'
import type { LookCard } from './types'

export const LOOKS = [classic, compact, calm, bold] as const

export type LookId = (typeof LOOKS)[number]['id']

/** Each look's graph card size (its folder's card.ts, which also loads its card.css). */
export const CARDS: Record<LookId, LookCard> = {
  classic: classicCard,
  a: compactCard,
  b: calmCard,
  c: boldCard,
}

export const DEFAULT_LOOK: LookId = classic.id

/** A known look id as it is; anything else (an unknown or deleted look, garbage) is Classic. */
export function parseLook(value: unknown): LookId {
  return LOOKS.find(look => look.id === value)?.id ?? DEFAULT_LOOK
}

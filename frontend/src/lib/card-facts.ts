/**
 * What a tree card shows beside a coroutine's recorded state (Part 7, P-9; triage "Cards"): its lifetime, and the
 * observation badges and round context the session health lists for it. Presentation only: the health decides what is
 * possibly blocked, long-lived, long suspended or cancelled after a failure (D-8). The health's lists hold their first
 * 50, so a coroutine past them simply carries no badge.
 */
import { createContext } from 'react'
import { formatDuration } from './format-duration'
import type { SessionHealth } from '@/types/health'

export type BadgeKind = 'possibly-blocked' | 'long-lived' | 'long-suspended'

export interface CardBadge {
  kind: BadgeKind
  text: string
}

export interface CardFact {
  badges: CardBadge[]
  /** Words after the state: "after the failure" for a cancellation in a failed round (context, not a cause). */
  context?: string
}

export function cardFacts(health: SessionHealth | undefined | null): ReadonlyMap<string, CardFact> {
  const facts = new Map<string, CardFact>()
  if (!health) return facts
  const fact = (id: string) => {
    let f = facts.get(id)
    if (!f) facts.set(id, (f = { badges: [] }))
    return f
  }
  for (const incident of health.incidents.items) {
    if (incident.type === 'POSSIBLY_BLOCKED') fact(incident.coroutineId).badges.push({ kind: 'possibly-blocked', text: 'Possibly blocked' })
    else if (incident.type === 'FAILED_ROUND') for (const id of incident.cancelledAfter.items) fact(id).context = 'after the failure'
  }
  // In order of weight: the problem first, then long suspended, then long-lived (a card shows the first).
  for (const o of health.observations.longSuspended.items) {
    fact(o.coroutineId).badges.push({ kind: 'long-suspended', text: `Long suspended · ${formatDuration(o.ageNanos)}` })
  }
  for (const o of health.observations.longLived.items) {
    fact(o.coroutineId).badges.push({ kind: 'long-lived', text: `Long-lived · ${formatDuration(o.ageNanos)}` })
  }
  return facts
}

/** A node's lifetime in words, as the server measured it (triage "Lifetimes"); null when it sent none. */
export function lifetimeText(node: { lifetimeNanos?: number | null; lifetimeKind?: 'FINISHED' | 'SO_FAR' | 'AT_LEAST' | null }): string | null {
  if (node.lifetimeNanos == null || !node.lifetimeKind) return null
  return formatDuration(node.lifetimeNanos, { soFar: node.lifetimeKind === 'SO_FAR', atLeast: node.lifetimeKind === 'AT_LEAST' })
}

/**
 * The cards' facts by coroutine id, provided by the session page (empty elsewhere: Compare, exports), with the health's
 * "as of" and whether its last refresh failed, which every badge names (H-2).
 */
export type CardFacts = ReadonlyMap<string, CardFact> & { asOf?: string; stale?: boolean }

export const CardFactsContext = createContext<CardFacts>(new Map())

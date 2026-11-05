/**
 * A node's second line (spec S-6): what a coroutine waits for or holds, as the lock, permit and channel
 * events show it — "waits for inventory-lock · 1st", "holds a payment-gateway permit".
 *
 * A wait is the suspension a wrapper recorded with its kind and resource (S-7, `projectWaits`); an older
 * producer records none, so the latest observed queue also says who waits. The place in the queue is the
 * latest observed one. Permits are counted, never numbered. A wait is drawn in the Suspended colour; a hold
 * alone is neutral, because a held lock is normal work.
 */
import type { VizEvent, WaitKind } from '@/types/api'
import { projectSync, type Holder } from './projections/project-sync'
import { projectWaits } from './projections/project-waits'
import { resourceNames } from './selection'

export interface ActivityLine {
  /** The facts, the wait first, joined with ", ". */
  text: string
  /** It waits for something (drawn as a wait); false when it only holds. */
  waiting: boolean
}

/** 1st, 2nd, 3rd, 4th … 11th, 12th, 13th … 21st. */
export function ordinal(n: number): string {
  const lastTwo = n % 100
  if (lastTwo >= 11 && lastTwo <= 13) return `${n}th`
  switch (n % 10) {
    case 1:
      return `${n}st`
    case 2:
      return `${n}nd`
    case 3:
      return `${n}rd`
    default:
      return `${n}th`
  }
}

/** "a payment-gateway permit", "an inventory permit". */
function aPermit(name: string): string {
  return `${/^[aeiou]/i.test(name) ? 'an' : 'a'} ${name} permit`
}

/** " · 2nd" when the coroutine is in the queue, else nothing. */
function place(coroutineId: string, queue: readonly Holder[] | undefined): string {
  const index = queue?.findIndex(w => w.id === coroutineId) ?? -1
  return index >= 0 ? ` · ${ordinal(index + 1)}` : ''
}

/** Coroutine id -> its second line, for every coroutine that waits for or holds something. */
export function projectActivity(events: readonly VizEvent[]): Map<string, ActivityLine> {
  const sync = projectSync(events)
  const names = resourceNames(events)
  const nameOf = (id: string) => names.get(id) ?? id
  const lockQueues = new Map(sync.mutexes.map(m => [m.mutexId, m.waitQueue]))
  const permitQueues = new Map(sync.semaphores.map(s => [s.semaphoreId, s.waitQueue]))

  const describeWait = (coroutineId: string, kind: WaitKind, resourceId: string | null): string => {
    const name = resourceId ? nameOf(resourceId) : null
    switch (kind) {
      case 'MUTEX':
        return `waits for ${name ?? 'a lock'}${resourceId ? place(coroutineId, lockQueues.get(resourceId)) : ''}`
      case 'SEMAPHORE':
        return `waits for ${name ? aPermit(name) : 'a permit'}${resourceId ? place(coroutineId, permitQueues.get(resourceId)) : ''}`
      case 'CHANNEL_SEND':
        return name ? `waits to send to ${name}` : 'waits to send'
      case 'CHANNEL_RECEIVE':
        return name ? `waits to receive from ${name}` : 'waits to receive'
      default:
        return 'waits'
    }
  }

  const waits = new Map<string, string>()
  for (const [coroutineId, wait] of projectWaits(events)) {
    waits.set(coroutineId, describeWait(coroutineId, wait.waitKind, wait.resourceId))
  }
  // An older producer records no wait kind: the latest queue still says who waits, and where.
  for (const m of sync.mutexes) {
    m.waitQueue.forEach((w, i) => {
      if (!waits.has(w.id)) waits.set(w.id, `waits for ${nameOf(m.mutexId)} · ${ordinal(i + 1)}`)
    })
  }
  for (const s of sync.semaphores) {
    s.waitQueue.forEach((w, i) => {
      if (!waits.has(w.id)) waits.set(w.id, `waits for ${aPermit(nameOf(s.semaphoreId))} · ${ordinal(i + 1)}`)
    })
  }

  const holds = new Map<string, string[]>()
  const hold = (coroutineId: string, text: string) => holds.set(coroutineId, [...(holds.get(coroutineId) ?? []), text])
  for (const m of sync.mutexes) {
    if (m.isLocked && m.currentHolder) hold(m.currentHolder, `holds ${nameOf(m.mutexId)}`)
  }
  for (const s of sync.semaphores) {
    const name = nameOf(s.semaphoreId)
    for (const h of s.activeHolders) {
      hold(h.id, h.permits === 1 ? `holds ${aPermit(name)}` : `holds ${h.permits} ${name} permits`)
    }
  }

  const lines = new Map<string, ActivityLine>()
  for (const coroutineId of new Set([...waits.keys(), ...holds.keys()])) {
    const wait = waits.get(coroutineId)
    lines.set(coroutineId, {
      text: [...(wait ? [wait] : []), ...(holds.get(coroutineId) ?? [])].join(', '),
      waiting: wait !== undefined,
    })
  }
  return lines
}

/**
 * The page's one selection (spec S-2): a coroutine, or a resource — a lock, a semaphore or a channel.
 * And a group of siblings (S-12, Part 6).
 *
 * What an event is about: its coroutine; for lock and permit events, the requester, acquirer or releaser;
 * otherwise the resource it names. A wrapper used outside any coroutine records the coroutine as
 * "unknown" (or "unknown-<nanos>"): that names nobody, so such an event is about its resource.
 */
import type { VizEvent } from '@/types/api'

export type ResourceKind = 'mutex' | 'semaphore' | 'channel'

export type Selection =
  | { kind: 'coroutine'; id: string }
  | { kind: 'resource'; resource: ResourceKind; id: string }
  /** Siblings grouped by name (S-12), by the group's id (`name-groups.ts`). */
  | { kind: 'group'; id: string }

/** The fields of the events this module reads; each kind carries some of them. */
interface Fields {
  kind: string
  label?: string | null
  coroutineId?: string | null
  requesterId?: string
  requesterLabel?: string | null
  acquirerId?: string
  acquirerLabel?: string | null
  releaserId?: string
  releaserLabel?: string | null
  mutexId?: string
  mutexLabel?: string | null
  semaphoreId?: string
  semaphoreLabel?: string | null
  channelId?: string
  name?: string | null
  resourceId?: string | null
}

const fieldsOf = (event: VizEvent) => event as unknown as Fields

/** A coroutine id that names a coroutine; the wrappers write "unknown…" when there was none. */
export function namesCoroutine(id: string | null | undefined): id is string {
  return !!id && id !== 'unknown' && !id.startsWith('unknown-')
}

const RESOURCE_ID: Record<ResourceKind, 'mutexId' | 'semaphoreId' | 'channelId'> = {
  mutex: 'mutexId',
  semaphore: 'semaphoreId',
  channel: 'channelId',
}

/** What the event is about (S-2), or null when it is about nothing one can select. */
export function eventSubject(event: VizEvent): Selection | null {
  const f = fieldsOf(event)
  const who = f.coroutineId ?? f.requesterId ?? f.acquirerId ?? f.releaserId
  if (namesCoroutine(who)) return { kind: 'coroutine', id: who }
  if (f.mutexId) return { kind: 'resource', resource: 'mutex', id: f.mutexId }
  if (f.semaphoreId) return { kind: 'resource', resource: 'semaphore', id: f.semaphoreId }
  if (f.channelId) return { kind: 'resource', resource: 'channel', id: f.channelId }
  return null
}

/** Whether the event belongs in the Events tab while this is selected; a group's are its [members]' events. */
export function eventConcerns(event: VizEvent, selection: Selection, members?: ReadonlySet<string>): boolean {
  if (selection.kind === 'coroutine' || selection.kind === 'group') {
    const subject = eventSubject(event)
    if (subject?.kind !== 'coroutine') return false
    return selection.kind === 'coroutine' ? subject.id === selection.id : !!members?.has(subject.id)
  }
  const f = fieldsOf(event)
  return f[RESOURCE_ID[selection.resource]] === selection.id || f.resourceId === selection.id
}

/** Resource id -> its label, for every lock, semaphore and channel the events name with one. */
export function resourceNames(events: readonly VizEvent[]): Map<string, string> {
  const names = new Map<string, string>()
  for (const event of events) {
    const f = fieldsOf(event)
    if (f.mutexId && f.mutexLabel) names.set(f.mutexId, f.mutexLabel)
    else if (f.semaphoreId && f.semaphoreLabel) names.set(f.semaphoreId, f.semaphoreLabel)
    else if (f.kind === 'ChannelCreated' && f.channelId && f.name) names.set(f.channelId, f.name)
  }
  return names
}

/**
 * Every lock, semaphore and channel the events name, a suspension waiting for one included (as
 * `eventConcerns` counts it): a selected resource no longer among them is gone.
 */
export function resourceIds(events: readonly VizEvent[]): Set<string> {
  const ids = new Set<string>()
  for (const event of events) {
    const f = fieldsOf(event)
    const id = f.mutexId ?? f.semaphoreId ?? f.channelId ?? f.resourceId
    if (id) ids.add(id)
  }
  return ids
}

/** A wrapper used outside any coroutine names no coroutine; the title says so rather than "unknown-123". */
const OUTSIDE = 'outside any coroutine'

/**
 * A row's title in the event list: its coroutine, and for lock, permit and channel events the resource too.
 * A channel is named from `names` (the page's resource names, `resourceNames`), else by its id.
 */
export function eventTitle(event: VizEvent, names: ReadonlyMap<string, string> = new Map()): string {
  const f = fieldsOf(event)
  const id = f.coroutineId ?? f.requesterId ?? f.acquirerId ?? f.releaserId
  const label = f.label ?? f.requesterLabel ?? f.acquirerLabel ?? f.releaserLabel
  const who = label ?? (id == null ? null : namesCoroutine(id) ? id : OUTSIDE)
  const channel = f.channelId ? (f.kind === 'ChannelCreated' && f.name ? f.name : (names.get(f.channelId) ?? f.channelId)) : null
  const what = f.mutexLabel ?? f.mutexId ?? f.semaphoreLabel ?? f.semaphoreId ?? channel
  return [who, what].filter(Boolean).join(' · ')
}

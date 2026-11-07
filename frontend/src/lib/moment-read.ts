/**
 * How a read is bound to the page's moment (Part 8, M-3; triage "Modes"). The live view reads the session as it is
 * now; a replay reads it at the cursor (`atSeq`, lenient); a shared link reads it as it was loaded (`asOfSeq`, strict,
 * with the health's cut id when there is one). A shared view goes through its token, never the protected routes.
 */
export type MomentSource =
  | { kind: 'session'; sessionId: string }
  | { kind: 'shared'; sessionId: string; token: string }

export type MomentBinding =
  | { kind: 'current' }
  | { kind: 'replay'; atSeq: number }
  | { kind: 'bound'; asOfSeq: number; asOfCut?: string | null }

export interface MomentRead {
  source: MomentSource
  binding: MomentBinding
}

/** The seq a read is bound to, or null for the live view. */
export function boundSeq(binding: MomentBinding): number | null {
  return binding.kind === 'replay' ? binding.atSeq : binding.kind === 'bound' ? binding.asOfSeq : null
}

function base(source: MomentSource): string {
  return source.kind === 'shared'
    ? `/shared/${encodeURIComponent(source.token)}`
    : `/sessions/${encodeURIComponent(source.sessionId)}`
}

function query(binding: MomentBinding): string {
  switch (binding.kind) {
    case 'current':
      return ''
    case 'replay':
      return `?atSeq=${binding.atSeq}`
    case 'bound':
      return `?asOfSeq=${binding.asOfSeq}${binding.asOfCut ? `&asOfCut=${encodeURIComponent(binding.asOfCut)}` : ''}`
  }
}

/** `…/coroutines/{id}/timeline` for this read, relative to the API base. */
export function timelinePath(read: MomentRead, coroutineId: string): string {
  return `${base(read.source)}/coroutines/${encodeURIComponent(coroutineId)}/timeline${query(read.binding)}`
}

/** `…/hierarchy` for this read, relative to the API base. */
export function hierarchyPath(read: MomentRead): string {
  return `${base(read.source)}/hierarchy${query(read.binding)}`
}

/**
 * The timeline's query key. The live view keeps today's key, so the stream's invalidations and every existing
 * observer still meet on it; a bound read adds its binding, so two moments never share a cache entry.
 */
export function timelineKey(read: MomentRead, coroutineId: string): readonly unknown[] {
  const head =
    read.source.kind === 'shared'
      ? ['shared', read.source.token, 'coroutines', coroutineId, 'timeline']
      : ['sessions', read.source.sessionId, 'coroutines', coroutineId, 'timeline']
  const b = read.binding
  if (b.kind === 'current') return head
  if (b.kind === 'replay') return [...head, 'atSeq', b.atSeq]
  return [...head, 'asOfSeq', b.asOfSeq, b.asOfCut ?? null]
}

/** `…/hierarchy`'s query key for this read: one cache entry per moment. */
export function hierarchyKey(read: MomentRead): readonly unknown[] {
  const head = read.source.kind === 'shared' ? ['shared', read.source.token, 'hierarchy'] : ['sessions', read.source.sessionId, 'hierarchy']
  const b = read.binding
  if (b.kind === 'current') return [...head, 'current']
  if (b.kind === 'replay') return [...head, 'atSeq', b.atSeq]
  return [...head, 'asOfSeq', b.asOfSeq, b.asOfCut ?? null]
}

/**
 * `…/health` for this read (Part 7's Problems tab reads through it, M-6b): the health takes the replay's `atSeq`; a
 * shared view's live health is the share payload's, so a bound read asks for none.
 */
export function healthPath(read: MomentRead): string {
  const atSeq = read.binding.kind === 'replay' ? `?atSeq=${read.binding.atSeq}` : ''
  return `${base(read.source)}/health${atSeq}`
}

/** [read] moved to [seq]: a replay read follows the cursor; a bound read keeps its cut. */
export function readAt(read: MomentRead, seq: number): MomentRead {
  return read.binding.kind === 'replay' ? { ...read, binding: { kind: 'replay', atSeq: seq } } : read
}

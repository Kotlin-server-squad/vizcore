import { useEffect, useState } from 'react'
import { formatNanoTime } from '@/lib/utils'
import { InspectorCard, InspectorRow, NotReported } from './InspectorCard'
import type { CoroutineTimeline } from '@/types/api'

/**
 * Running this long since its last resume, with no suspension in between, is worth a hint: a
 * coroutine stuck in a blocking call on a shared thread looks exactly like this (#218).
 */
export const POSSIBLY_BLOCKED_AFTER_NANOS = 10_000_000_000

/**
 * The lifetime the timeline shows so far, for a coroutine that has not
 * finished (#145). The backend reports `totalDuration` only on completion, and
 * its active/suspended figures count CLOSED intervals only; the span from the
 * first to the last recorded event is the most the client can say from the
 * events alone. Event timestamps are the app JVM's monotonic clock, so they are
 * compared with each other only — never with the browser's clock.
 */
export function lifetimeSoFar(timeline: CoroutineTimeline): number | null {
  const stamps = timeline.events.map(e => e.tsNanos).filter(t => Number.isFinite(t))
  const span = stamps.length >= 2 ? Math.max(...stamps) - Math.min(...stamps) : null
  const closed =
    timeline.activeDuration != null && timeline.suspendedDuration != null
      ? timeline.activeDuration + timeline.suspendedDuration
      : null
  if (span === null && closed === null) return null
  return Math.max(span ?? 0, closed ?? 0)
}

export interface LiveTiming {
  total: number
  active: number | null
  suspended: number | null
  openPhase: 'ACTIVE' | 'SUSPENDED'
  /** How long the open phase has lasted so far. */
  openFor: number
}

/**
 * Timing of an unfinished coroutine counted up to now (#218), or null when the timeline lacks
 * what that needs (a finished coroutine, or a backend that predates the open-phase fields).
 *
 * "Now" on the app JVM's monotonic clock is estimated from the coroutine's creation, which the
 * backend reports on both of that JVM's clocks, plus the wall-clock time since then: the
 * backend's at [CoroutineTimeline.observedAtEpochMs], then the browser's since the response
 * arrived at [receivedAtMs]. Like the leak age, this assumes the app host's and the backend's
 * wall clocks agree: a backend clock N minutes ahead adds N minutes. The estimate never goes back
 * before the latest recorded event. The backend sends an open phase only while the app is still
 * streaming, and the inspector refetches the timeline while it is open.
 */
export function liveTiming(
  timeline: CoroutineTimeline,
  receivedAtMs: number,
  nowMs: number,
): LiveTiming | null {
  const { openPhase, openPhaseSinceNanos, createdAtNanos, createdAtEpochMs, observedAtEpochMs } = timeline
  if (timeline.totalDuration != null || !openPhase || openPhaseSinceNanos == null) return null
  if (createdAtNanos == null || createdAtEpochMs == null || observedAtEpochMs == null) return null
  if (createdAtEpochMs <= 0) return null // unset on the emitter, not a 1970 creation

  const elapsedMs = observedAtEpochMs - createdAtEpochMs + Math.max(0, nowMs - receivedAtMs)
  const latestEvent = Math.max(openPhaseSinceNanos, ...timeline.events.map(e => e.tsNanos))
  const now = Math.max(createdAtNanos + Math.max(0, elapsedMs) * 1_000_000, latestEvent)
  const openFor = now - openPhaseSinceNanos
  return {
    total: now - createdAtNanos,
    active: openPhase === 'ACTIVE' ? (timeline.activeDuration ?? 0) + openFor : (timeline.activeDuration ?? null),
    suspended:
      openPhase === 'SUSPENDED' ? (timeline.suspendedDuration ?? 0) + openFor : (timeline.suspendedDuration ?? null),
    openPhase,
    openFor,
  }
}

/** The current time, re-read every [intervalMs] while [enabled]. */
function useNow(enabled: boolean, intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!enabled) return
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timer)
  }, [enabled, intervalMs])
  return now
}

/**
 * How long this coroutine has been alive, working, and waiting.
 *
 * Every duration is `~`-prefixed: the backend samples on a poll, so these are
 * bounded by the poll interval rather than exact (validated sketch 001-C). A
 * coroutine still running gets "~N so far", counting up to now (#145, #218).
 */
export function TimingCard({
  timeline,
  receivedAtMs,
}: {
  timeline: CoroutineTimeline | undefined
  /** When the browser received [timeline] (epoch ms), so live timing can count on from it. */
  receivedAtMs?: number
}) {
  const unfinished = !!timeline && timeline.totalDuration == null
  const now = useNow(unfinished && !!timeline?.openPhase)
  const live = unfinished && timeline ? liveTiming(timeline, receivedAtMs ?? now, now) : null
  const soFar = unfinished && timeline && !live ? lifetimeSoFar(timeline) : null
  const possiblyBlocked = live?.openPhase === 'ACTIVE' && live.openFor >= POSSIBLY_BLOCKED_AFTER_NANOS

  return (
    <InspectorCard title="Timing" testId="timing-card">
      <InspectorRow
        label="Total"
        value={
          live ? (
            <span title="Still running: time since it was created">~{formatNanoTime(live.total)} so far</span>
          ) : soFar !== null ? (
            <span title="Still running: time from its first to its latest recorded event">
              ~{formatNanoTime(soFar)} so far
            </span>
          ) : (
            <Approx nanos={timeline?.totalDuration} />
          )
        }
      />
      <InspectorRow
        label="Active"
        value={<Approx nanos={live ? live.active : timeline?.activeDuration} soFar={live?.openPhase === 'ACTIVE'} />}
      />
      <InspectorRow
        label="Suspended"
        value={
          <Approx nanos={live ? live.suspended : timeline?.suspendedDuration} soFar={live?.openPhase === 'SUSPENDED'} />
        }
      />
      {possiblyBlocked && live && (
        <p data-testid="possibly-blocked" className="mt-2 text-xs text-warning">
          Running for ~{formatNanoTime(live.openFor)} without suspending since it last resumed. It may be
          blocked in a call on its thread.
        </p>
      )}
      <p className="mt-2 text-xs text-default-400">
        Approximate — sampling is poll-bounded.
      </p>
    </InspectorCard>
  )
}

function Approx({ nanos, soFar = false }: { nanos: number | null | undefined; soFar?: boolean }) {
  if (nanos === null || nanos === undefined) return <NotReported />
  return <>~{formatNanoTime(nanos)}{soFar ? ' so far' : ''}</>
}

import { seconds } from '@/lib/replay-seek'

/** The moment a part of the page shows, outside the live view (Part 8, M-6). */
export type Moment =
  | {
      kind: 'replay'
      /** The cursor's time since the replay's first event (the replay clock). */
      atNanos: number
      /** The cursor's index in the replay, 0-based. */
      index: number
      total: number
    }
  | { kind: 'loaded'; seq: number }

/**
 * One line naming the moment a part shows: "At 4.2 s · event 37 of 120" in replay, "As loaded · event 812" in a
 * shared link. Nothing in the live view, which always shows now. Same markup in every look (`data-look-part="moment"`).
 */
export function MomentNote({ moment }: { moment: Moment | null }) {
  if (!moment) return null
  const text =
    moment.kind === 'replay'
      ? `At ${seconds(moment.atNanos)} s · event ${moment.index + 1} of ${moment.total}`
      : `As loaded · event ${moment.seq}`
  return (
    <p data-testid="moment-note" data-look-part="moment" className="text-xs text-default-500">
      {text}
    </p>
  )
}

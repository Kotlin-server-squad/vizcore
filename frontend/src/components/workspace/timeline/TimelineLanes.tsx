import { pieces } from '@/lib/timeline/at-cursor'
import { bucketMarks, uncertainPieces } from '@/lib/timeline/uncertain-pieces'
import { aPermit } from '@/lib/timeline/row-sentence'
import type { HoldBand, Timeline, TimelineLane } from '@/lib/timeline/build-timeline'
import { between, describeLane } from '@/lib/timeline/describe'
import { place, pct, TEXT_MIN_PCT } from './geometry'
import { HATCH, UnknownMarks } from './TimelineRows'

/** The most lane rows drawn (plan T-7); the rest are counted. */
export const MAX_LANE_ROWS = 12

/** One drawn lane row: a lock, or one display slot of a semaphore's permits. */
export interface LaneRow {
  lane: TimelineLane
  slot: number
}

/** The lane rows in order, at most [MAX_LANE_ROWS], and how many more there are. */
export function laneRows(lanes: readonly TimelineLane[]): { rows: LaneRow[]; more: number } {
  const all: LaneRow[] = lanes.flatMap(lane =>
    Array.from({ length: Math.max(1, lane.slots) }, (_, slot) => ({ lane, slot })),
  )
  return { rows: all.slice(0, MAX_LANE_ROWS), more: Math.max(0, all.length - MAX_LANE_ROWS) }
}

/** The label of a lane row: a lock's name, a permit group's title on its first slot (the slots are not numbered). */
export function laneLabel({ lane, slot }: LaneRow): { title: string | null; note: string | null } {
  if (lane.kind === 'lock') return { title: lane.name, note: null }
  if (slot > 0) return { title: null, note: null }
  const over = lane.permits !== null && lane.slots > lane.permits
  return {
    title: `${lane.name} permits`,
    note: over ? 'more listed holders than permits: who holds which is unknown' : null,
  }
}

/**
 * One lane row's track (S-10): the holder bands of a lock, or of one permit slot, with the "unknown" marks of S-7.
 * `timeline-lane`, `timeline-lane-band`, `timeline-unknown`.
 */
export function LaneTrack({
  timeline,
  row,
  cursor,
  labelOf,
}: {
  timeline: Timeline
  row: LaneRow
  cursor: number | null
  labelOf: (coroutineId: string) => string
}) {
  const { lane, slot } = row
  const bands = lane.bands.filter(b => b.slot === slot)
  // A mark already drawn on one of this slot's bands is not drawn again on the lane.
  const onBands = new Set(bands.flatMap(b => b.unknownMarks))
  return (
    <div
      data-look-part="timeline-lane"
      data-testid={`timeline-lane-${lane.resourceId}-${slot}`}
      role="img"
      aria-label={describeLane(timeline, lane, slot, cursor, labelOf)}
      className="relative h-7 border-b border-default-100"
    >
      {bands.map(band => (
        <LaneBand key={`${band.coroutineId}-${band.from}`} timeline={timeline} band={band} cursor={cursor} name={lane.name} holder={labelOf(band.coroutineId)} />
      ))}
      {slot === 0 &&
        bucketMarks(
          timeline,
          lane.unknownMarks.filter(at => !onBands.has(at)),
          cursor,
        ).map(({ at, nanos, after, count }) => (
            <span
              key={at}
              data-look-part="timeline-unknown"
              data-after-cursor={after ? 'true' : undefined}
              title={`${count === 1 ? `${aPermit(lane.name)} came back` : `${count} ${lane.name} permits came back`} · holder unknown`}
              className="absolute top-2 flex h-3 w-3 -translate-x-1/2 items-center justify-center rounded-full border border-warning bg-content1 text-[8px] font-bold leading-none text-warning"
              style={{ left: `${pct(nanos, timeline.spanNanos)}%` }}
            >
              <span aria-hidden>?</span>
            </span>
          ))}
    </div>
  )
}

function LaneBand({
  timeline,
  band,
  cursor,
  name,
  holder,
}: {
  timeline: Timeline
  band: HoldBand
  cursor: number | null
  name: string
  holder: string
}) {
  const { filled, hollow } = pieces(timeline, band, cursor)
  const what = band.kind === 'lock' ? name : aPermit(name)
  const wide = (range: [number, number]) => pct(range[1], timeline.spanNanos) - pct(range[0], timeline.spanNanos) >= TEXT_MIN_PCT
  const style = (range: [number, number]) => place(range, timeline.spanNanos)
  return (
    <>
      {filled && (
        <span
          data-look-part="timeline-lane-band"
          title={`${holder} holds ${what} · ${between(filled)}`}
          className="absolute top-1 flex h-5 min-w-[2px] items-center overflow-hidden rounded-sm bg-default-200 text-[11px] leading-none text-foreground"
          style={style(filled)}
        >
          {wide(filled) && <span className="truncate px-1">{holder}</span>}
        </span>
      )}
      {hollow && (
        <span
          data-look-part="timeline-lane-band"
          data-after-cursor="true"
          title={`${holder} holds ${what} · ${between(hollow)} (after the cursor)`}
          className="absolute top-1 flex h-5 min-w-[2px] items-center overflow-hidden rounded-sm border border-dashed border-default-400 text-[11px] leading-none text-default-500"
          style={style(hollow)}
        >
          {!filled && wide(hollow) && <span className="truncate px-1">{holder}</span>}
        </span>
      )}
      {uncertainPieces(timeline, band, cursor).map(piece => (
        <span
          key={`${piece.range[0]}-${piece.after}`}
          data-look-part="timeline-lane-band"
          data-uncertain="true"
          data-after-cursor={piece.after ? 'true' : undefined}
          title={`${holder} may hold ${what} · holder unknown · ${piece.atCursor ? 'at the cursor' : between(piece.range)}`}
          className={`absolute top-1 h-5 rounded-sm bg-content1/60 text-default-500 ${HATCH} ${piece.atCursor ? 'min-w-[6px] -translate-x-1/2' : ''}`}
          style={style(piece.range)}
        />
      ))}
      <UnknownMarks timeline={timeline} band={band} cursor={cursor} name={name} top={0} />
    </>
  )
}

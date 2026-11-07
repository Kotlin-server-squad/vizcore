import { forwardRef, type KeyboardEvent } from 'react'
import { getStateColors, stateLabel } from '@/lib/coroutine-state-colors'
import { seconds } from '@/lib/replay-seek'
import { pieces } from '@/lib/timeline/at-cursor'
import { aPermit } from '@/lib/timeline/row-sentence'
import { bucketMarks, uncertainPieces } from '@/lib/timeline/uncertain-pieces'
import type { HoldBand, StateSpan, Timeline, TimelineRow } from '@/lib/timeline/build-timeline'
import { between, describeRow, endOutlasted, holdWhat, waitWords } from '@/lib/timeline/describe'
import { place, TEXT_MIN_PCT, pct } from './geometry'

/** One row's height, in the label column and the track alike, so the two columns line up. */
export const ROW_HEIGHT = 'h-10'

const MAX_HOLD_LANES = 3

const END_GLYPH: Record<string, string> = { COMPLETED: '✓', FAILED: '✕', CANCELLED: '⊘' }

/** Classic's look for a state bar (the look folders restyle every hook). */
const FILLED: Record<string, string> = {
  CREATED: 'bg-default-200 text-default-600',
  ACTIVE: 'bg-primary/35 text-foreground',
  WAITING_FOR_CHILDREN: 'bg-primary/20 text-foreground',
  SUSPENDED: 'bg-warning/35 text-foreground',
}

/** The diagonal hatching of "may hold". */
export const HATCH = 'bg-[repeating-linear-gradient(135deg,currentColor_0_1px,transparent_1px_5px)]'

interface RowTrackProps {
  timeline: Timeline
  row: TimelineRow | undefined
  name: string
  /** The tree's state at the moment: the row says it in words. */
  state: string
  cursor: number | null
  selected: boolean
  focusable: boolean
  nameOf: (resourceId: string) => string
  onSelect?: (id: string) => void
  onKeyDown: (event: KeyboardEvent<HTMLButtonElement>) => void
  id: string
}

/**
 * A row's track (S-10): its state bars, the thin hold band above them and the end mark, as one button that selects the
 * coroutine (S-2). Same markup in every look: `timeline-row`, `timeline-bar`, `timeline-hold`, `timeline-end`.
 */
export const RowTrack = forwardRef<HTMLButtonElement, RowTrackProps>(function RowTrack(
  { timeline, row, name, state, cursor, selected, focusable, nameOf, onSelect, onKeyDown, id },
  ref,
) {
  const span = timeline.spanNanos
  // Hold bands that overlap share the band area in sub-lanes.
  // At most three sub-lanes fit above the bar; more holds at once share the third.
  const holdLanes: HoldBand[][] = []
  for (const band of row?.holds ?? []) {
    const lane = holdLanes.find(l => l.every(b => (b.to ?? Infinity) <= band.from || (band.to ?? Infinity) <= b.from))
    if (lane) lane.push(band)
    else if (holdLanes.length < MAX_HOLD_LANES) holdLanes.push([band])
    else holdLanes[MAX_HOLD_LANES - 1]!.push(band)
  }
  const bandHeight = holdLanes.length > 2 ? 2 : holdLanes.length > 1 ? 3 : 5

  return (
    <button
      ref={ref}
      type="button"
      data-look-part="timeline-row"
      data-selected={selected ? 'true' : undefined}
      aria-label={`${name}, ${stateLabel(state)}`}
      aria-describedby={`timeline-desc-${id}`}
      aria-current={selected ? 'true' : undefined}
      tabIndex={focusable ? 0 : -1}
      onClick={() => onSelect?.(id)}
      onKeyDown={onKeyDown}
      className={`relative block w-full ${ROW_HEIGHT} border-b border-default-100 text-left outline-none focus-visible:ring-2 focus-visible:ring-primary ${
        selected ? 'bg-primary/10' : 'hover:bg-default-100'
      }`}
    >
      <span id={`timeline-desc-${id}`} className="sr-only">
        {describeRow(timeline, row, cursor, nameOf)}
      </span>
      {row?.createdBefore ? (
        <span className="absolute inset-y-0 left-1 flex items-center text-xs text-default-500">
          created before these events: its states are not drawn
        </span>
      ) : !row || row.spans.length === 0 ? (
        <span className="absolute inset-y-0 left-1 flex items-center text-xs text-default-500">
          no recorded intervals in these events
        </span>
      ) : null}

      {holdLanes.map((lane, i) =>
        lane.map(band => (
          <HoldPieces
            key={`${band.resourceId}-${band.from}-${band.slot}`}
            timeline={timeline}
            band={band}
            cursor={cursor}
            name={nameOf(band.resourceId)}
            top={2 + i * (bandHeight + 1)}
            height={bandHeight}
            ended={row ? (endOutlasted(row, band) ?? null) : null}
          />
        )),
      )}

      {row?.spans.map(s => (
        <SpanPieces key={s.from} timeline={timeline} span={s} cursor={cursor} nameOf={nameOf} />
      ))}

      {row?.ends.map(end => {
        const after = cursor !== null && end.at > cursor
        const at = timeline.times[end.at] ?? 0
        const words = `${stateLabel(end.state)} at ${seconds(at)} s`
        return (
          <span
            key={end.at}
            data-look-part="timeline-end"
            data-state={end.state}
            data-after-cursor={after ? 'true' : undefined}
            title={words}
            className={`absolute top-3 flex h-5 w-5 -translate-x-1/2 items-center justify-center rounded-full text-[11px] font-bold ${
              after ? `border border-dashed ${getStateColors(end.state).border} ${getStateColors(end.state).text}` : `${getStateColors(end.state).iconBg} ${getStateColors(end.state).text}`
            }`}
            style={{ left: `${pct(at, span)}%` }}
          >
            <span aria-hidden>{END_GLYPH[end.state]}</span>
          </span>
        )
      })}
    </button>
  )
})

function SpanPieces({
  timeline,
  span,
  cursor,
  nameOf,
}: {
  timeline: Timeline
  span: StateSpan
  cursor: number | null
  nameOf: (id: string) => string
}) {
  const { filled, hollow } = pieces(timeline, span, cursor)
  const label = stateLabel(span.state)
  const wait = span.wait ? waitWords(span.wait.kind, span.wait.resourceId ? nameOf(span.wait.resourceId) : null) : null
  const colors = getStateColors(span.state)
  const text = (range: [number, number]) => `${label}${wait ? ` · ${wait}` : ''} · ${between(range)}`
  const wide = (range: [number, number]) => pct(range[1], timeline.spanNanos) - pct(range[0], timeline.spanNanos) >= TEXT_MIN_PCT
  return (
    <>
      {filled && (
        <span
          data-look-part="timeline-bar"
          data-state={span.state}
          title={text(filled)}
          className={`absolute top-3 flex h-5 min-w-[2px] items-center overflow-hidden rounded-sm text-[11px] leading-none ${FILLED[span.state] ?? 'bg-default-200'}`}
          style={place(filled, timeline.spanNanos)}
        >
          {wide(filled) && <span className="truncate px-1">{wait ? `${label} · ${wait}` : label}</span>}
        </span>
      )}
      {hollow && (
        <span
          data-look-part="timeline-bar"
          data-state={span.state}
          data-after-cursor="true"
          title={`${text(hollow)} (after the cursor)`}
          className={`absolute top-3 flex h-5 min-w-[2px] items-center overflow-hidden rounded-sm border border-dashed text-[11px] leading-none text-default-500 ${colors.border}`}
          style={place(hollow, timeline.spanNanos)}
        >
          {wide(hollow) && <span className="truncate px-1">{label}</span>}
        </span>
      )}
    </>
  )
}

function HoldPieces({
  timeline,
  band,
  cursor,
  name,
  top,
  height,
  ended,
}: {
  timeline: Timeline
  band: HoldBand
  cursor: number | null
  name: string
  top: number
  height: number
  /** The row's end that this band outlasted. */
  ended: { state: string; at: number } | null
}) {
  const what = holdWhat(band, name)
  const { filled, hollow } = pieces(timeline, band, cursor)
  const afterEnd = ended ? ` · still held after it ${stateLabel(ended.state).toLowerCase()}` : ''
  // Said of the filled part only once the cursor has passed that end (never a later moment's fact).
  const after = ended && (cursor === null || ended.at <= cursor) ? afterEnd : ''
  const style = (range: [number, number]) => ({ ...place(range, timeline.spanNanos), top: `${top}px`, height: `${height}px` })
  return (
    <>
      {filled && (
        <span
          data-look-part="timeline-hold"
          title={`holds ${what} · ${between(filled)}${after}`}
          className="absolute min-w-[2px] rounded-full bg-default-500"
          style={style(filled)}
        />
      )}
      {hollow && (
        <span
          data-look-part="timeline-hold"
          data-after-cursor="true"
          title={`holds ${what} · ${between(hollow)}${afterEnd} (after the cursor)`}
          className="absolute min-w-[2px] rounded-full border border-dashed border-default-500"
          style={style(hollow)}
        />
      )}
      {uncertainPieces(timeline, band, cursor).map(piece => (
        <span
          key={`${piece.range[0]}-${piece.after}`}
          data-look-part="timeline-hold"
          data-uncertain="true"
          data-after-cursor={piece.after ? 'true' : undefined}
          title={`may hold ${what} · holder unknown · ${piece.atCursor ? `at ${seconds(piece.range[0])} s` : between(piece.range)}`}
          className={`absolute rounded-full bg-content1 text-default-500 ${HATCH} ${piece.atCursor ? 'min-w-[6px] -translate-x-1/2' : ''}`}
          style={style(piece.range)}
        />
      ))}
      <UnknownMarks timeline={timeline} band={band} cursor={cursor} name={name} top={top - 1} buckets={40} />
    </>
  )
}

/**
 * The "unknown" marks on a band (S-7): where a permit came back with an unknown holder, and where the band ended without
 * a release of its holder's own.
 */
export function UnknownMarks({
  timeline,
  band,
  cursor,
  name,
  top,
  buckets = 100,
}: {
  timeline: Timeline
  band: HoldBand
  cursor: number | null
  name: string
  top: number
  /** How finely marks are kept apart: a row's thin band needs fewer than a lane. */
  buckets?: number
}) {
  const came = (count: number) => (count === 1 ? `${aPermit(name)} came back` : `${count} ${name} permits came back`)
  const marks = [
    ...bucketMarks(timeline, band.unknownMarks, cursor, buckets).map(m => ({ at: m.at, nanos: m.nanos, after: m.after, title: `${came(m.count)} · holder unknown` })),
    ...(band.end === 'unknown' && band.to !== null && !band.unknownMarks.includes(band.to)
      ? [{ at: band.to, nanos: timeline.times[band.to] ?? 0, after: cursor !== null && band.to > cursor, title: band.kind === 'lock' ? `${name} freed by another coroutine · holder unknown` : `stopped being listed as holding ${aPermit(name)} · holder unknown` }]
      : []),
  ]
  return (
    <>
      {marks.map(({ at, nanos, after, title }) => (
        <span
          key={at}
          data-look-part="timeline-unknown"
          data-after-cursor={after ? 'true' : undefined}
          title={title}
          className="absolute flex h-3 w-3 -translate-x-1/2 items-center justify-center rounded-full border border-warning bg-content1 text-[8px] font-bold leading-none text-warning"
          style={{ left: `${pct(nanos, timeline.spanNanos)}%`, top: `${top}px` }}
        >
          <span aria-hidden>?</span>
        </span>
      ))}
    </>
  )
}

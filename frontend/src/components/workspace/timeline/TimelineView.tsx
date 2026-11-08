import { useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { getStateColors, stateLabel } from '@/lib/coroutine-state-colors'
import { seconds } from '@/lib/replay-seek'
import type { Timeline } from '@/lib/timeline/build-timeline'
import type { CoroutineNode } from '@/types/api'
import { pct, ticks } from './geometry'
import { HATCH, ROW_HEIGHT, RowTrack } from './TimelineRows'
import { LaneTrack, laneLabel, laneRows } from './TimelineLanes'

interface TimelineViewProps {
  timeline: Timeline
  /** The coroutines the tree shows, in its order (plan T-3): the rows. */
  rows: readonly CoroutineNode[]
  /** The replay cursor's index into `timeline.events`; null live or loaded. */
  cursor: number | null
  /** "Some intervals may be missing: …" (T-9), or null. */
  note: string | null
  selectedId: string | null
  onSelect?: (id: string) => void
}

const LEGEND_STATES = ['CREATED', 'ACTIVE', 'SUSPENDED', 'WAITING_FOR_CHILDREN'] as const
const LEGEND_SWATCH: Record<string, string> = {
  CREATED: 'bg-default-200',
  ACTIVE: 'bg-primary/35',
  SUSPENDED: 'bg-warning/35',
  WAITING_FOR_CHILDREN: 'bg-primary/20',
}

/**
 * The Timeline (spec S-10), the second main view: one row per coroutine over time, the lock and permit lanes under the
 * rows, and in replay the cursor with what comes after it outlined (S-11). Presentational: the rows, the moment and
 * the note come from the caller, and every derivation is in `lib/timeline`.
 */
export function TimelineView({ timeline, rows, cursor, note, selectedId, onSelect }: TimelineViewProps) {
  const span = timeline.spanNanos
  const { rows: lanes, more } = useMemo(() => laneRows(timeline.lanes), [timeline.lanes])
  const names = useMemo(() => new Map(timeline.lanes.map(l => [l.resourceId, l.name])), [timeline.lanes])
  const nameOf = (id: string) => names.get(id) ?? id
  const labels = useMemo(() => {
    const map = new Map<string, string>()
    for (const e of timeline.events) {
      const f = e as { kind: string; coroutineId?: string; label?: string | null }
      if (f.kind === 'coroutine.created' && f.coroutineId && f.label) map.set(f.coroutineId, f.label)
    }
    for (const r of rows) if (r.label) map.set(r.id, r.label)
    return map
  }, [timeline.events, rows])
  const labelOf = (id: string) => labels.get(id) ?? id

  // One tab stop for the rows (S-14): ↑ ↓ Home End move it, Enter or Space selects.
  const [focusIndex, setFocusIndex] = useState(0)
  const focusAt = Math.min(focusIndex, Math.max(0, rows.length - 1))
  const buttons = useRef<(HTMLButtonElement | null)[]>([])
  const move = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next =
      event.key === 'ArrowDown' ? index + 1 : event.key === 'ArrowUp' ? index - 1 : event.key === 'Home' ? 0 : event.key === 'End' ? rows.length - 1 : null
    if (next === null) return
    event.preventDefault()
    const clamped = Math.min(Math.max(next, 0), rows.length - 1)
    setFocusIndex(clamped)
    buttons.current[clamped]?.focus()
  }

  const cursorAt = cursor !== null ? (timeline.times[cursor] ?? 0) : null

  return (
    <div data-look-part="timeline" className="flex min-h-0 min-w-0 flex-1 flex-col gap-2">
      {note && (
        <p data-look-part="trimmed-note" className="text-xs text-warning">
          {note}
        </p>
      )}
      <Legend replay={cursor !== null} />
      <div data-testid="timeline-scroll" className="min-h-0 min-w-0 flex-1 overflow-auto rounded-medium border border-default-200">
        {/* A minimum width: below 1,024 px the drawing scrolls inside this box, never the page (S-14). */}
        <div className="flex min-w-[960px]">
          <div className="sticky left-0 z-10 w-44 shrink-0 border-r border-default-200 bg-content1">
            <div className="h-6 border-b border-default-200" />
            {rows.map(r => (
              <div
                key={r.id}
                data-look-part="timeline-label"
                data-testid={`timeline-label-${r.id}`}
                className={`${ROW_HEIGHT} flex cursor-pointer flex-col justify-center border-b border-default-100 px-2 leading-tight`}
                onClick={() => onSelect?.(r.id)}
              >
                <span className="truncate font-mono text-xs">{r.label ?? r.id}</span>
                <span className={`text-[11px] ${getStateColors(r.state).text}`}>{stateLabel(r.state)}</span>
              </div>
            ))}
            {lanes.length > 0 && (
              <>
                <div className="flex h-6 items-end border-b border-default-200 px-2 pb-0.5 text-[11px] font-semibold text-default-500">
                  Locks and permits
                </div>
                {lanes.map(row => {
                  const { title, note: laneNote } = laneLabel(row)
                  return (
                    <div
                      key={`${row.lane.resourceId}-${row.slot}`}
                      className="flex h-7 flex-col justify-center border-b border-default-100 px-2 leading-tight"
                    >
                      {title && <span className="truncate text-xs">{title}</span>}
                      {laneNote && <span className="truncate text-[10px] text-warning">{laneNote}</span>}
                    </div>
                  )
                })}
              </>
            )}
          </div>

          <div className="relative min-w-0 flex-1">
            <div data-look-part="timeline-axis" className="relative h-6 border-b border-default-200 text-[10px] text-default-500">
              {ticks(span).map((t, i, all) => (
                <span
                  key={t}
                  // The first and last labels stay inside the axis.
                  className={`absolute top-1 whitespace-nowrap ${i === 0 ? 'pl-1' : i === all.length - 1 && pct(t, span) > 95 ? '-translate-x-full pr-1' : '-translate-x-1/2'}`}
                  style={{ left: `${pct(t, span)}%` }}
                >
                  {seconds(t)} s
                </span>
              ))}
            </div>
            {rows.map((r, i) => (
              <RowTrack
                key={r.id}
                ref={el => {
                  buttons.current[i] = el
                }}
                id={r.id}
                timeline={timeline}
                row={timeline.rows.get(r.id)}
                name={r.label ?? r.id}
                state={r.state}
                cursor={cursor}
                selected={r.id === selectedId}
                focusable={i === focusAt}
                nameOf={nameOf}
                onSelect={id => {
                  setFocusIndex(i)
                  onSelect?.(id)
                }}
                onKeyDown={event => move(event, i)}
              />
            ))}
            {lanes.length > 0 && (
              <div role="group" aria-label="Locks and permits">
                <div className="h-6 border-b border-default-200" />
                {lanes.map(row => (
                  <LaneTrack key={`${row.lane.resourceId}-${row.slot}`} timeline={timeline} row={row} cursor={cursor} labelOf={labelOf} />
                ))}
                {more > 0 && <p className="px-2 py-1 text-xs text-default-500">{more} more lanes</p>}
              </div>
            )}
            {cursorAt !== null && (
              <div className="pointer-events-none absolute inset-0">
                <div
                  data-look-part="timeline-future"
                  data-testid="timeline-future"
                  className="absolute inset-y-0 right-0 border border-dashed border-default-400 bg-default-50/40"
                  style={{ left: `${pct(cursorAt, span)}%` }}
                />
                <div
                  data-look-part="timeline-cursor"
                  data-testid="timeline-cursor"
                  className="absolute inset-y-0 w-0.5 -translate-x-1/2 bg-primary"
                  style={{ left: `${pct(cursorAt, span)}%` }}
                />
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

/** What the colours and marks mean, in words (S-14: state is always text plus colour). */
function Legend({ replay }: { replay: boolean }) {
  return (
    <div data-look-part="timeline-legend" data-testid="timeline-legend" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-default-600">
      {LEGEND_STATES.map(state => (
        <span key={state} className="flex items-center gap-1">
          <span aria-hidden data-state={state} className={`inline-block h-2.5 w-4 rounded-sm ${LEGEND_SWATCH[state]}`} />
          <span>{stateLabel(state)}</span>
        </span>
      ))}
      <span className="flex items-center gap-1">
        <span aria-hidden>✓ ✕ ⊘</span>
        <span>Completed, Failed, Cancelled</span>
      </span>
      <span className="flex items-center gap-1">
        <span aria-hidden className="inline-block h-1 w-4 rounded-full bg-default-500" />
        <span>Holds</span>
      </span>
      <span className="flex items-center gap-1">
        <span aria-hidden className={`inline-block h-2.5 w-4 rounded-sm text-default-500 ${HATCH}`} />
        <span>May hold</span>
      </span>
      <span className="flex items-center gap-1">
        <span aria-hidden className="flex h-3 w-3 items-center justify-center rounded-full border border-warning text-[8px] font-bold text-warning">?</span>
        <span>Holder unknown</span>
      </span>
      {replay && (
        <span className="flex items-center gap-1">
          <span aria-hidden className="inline-block h-2.5 w-4 rounded-sm border border-dashed border-default-400" />
          <span>After the cursor</span>
        </span>
      )}
    </div>
  )
}

import { useLayoutEffect, useMemo, useRef } from 'react'
import { DOCK_MIN_PX, MAIN_MIN_PX } from '@/lib/dock-layout'
import type { SessionOverview } from '../types'
import { ROW_CAP, rightNowLines, segmentsLabel, stateSegments } from './overview'

/** The CSS variable C's body reads for its top padding: the overview's height. */
export const OVERVIEW_HEIGHT_VAR = '--c-overview-h'

/** What the body must keep under the overview: the main view's minimum, the 12 px gap, the smallest dock. */
export const ROOM_BELOW_PX = MAIN_MIN_PX + 12 + DOCK_MIN_PX

/**
 * C's "Right now" overview (sketch K-C-Bold): what holds and what waits, then
 * a bar of the session's states, above the main view.
 *
 * It is the body's third child (the slot keeps the main view and the dock
 * first), drawn at the top of the body. The dock sizes itself from the body's
 * content height, so the overview sits in the body's top padding rather than
 * in its flow: it measures itself and sets that padding through
 * `--c-overview-h` on the body. When the body is too short to keep the main
 * view's and the dock's minimums under it, the overview steps aside (hidden,
 * no padding) rather than squeeze them. On unmount (another look, or nothing
 * left to show) it takes the variable away: the body element stays, and is
 * Classic's then.
 */
export function RightNow({ overview }: { overview: SessionOverview | undefined }) {
  const ref = useRef<HTMLElement>(null)
  const coroutines = overview?.coroutines
  const activity = overview?.activity
  const counts = overview?.counts
  const lines = useMemo(
    () => (coroutines && activity ? rightNowLines(coroutines, activity) : []),
    [coroutines, activity]
  )
  const segments = useMemo(() => (counts ? stateSegments(counts) : []), [counts])
  const empty = lines.length === 0 && segments.length === 0

  useLayoutEffect(() => {
    const el = ref.current
    const body = el?.parentElement
    if (!el || !body) return
    const apply = () => {
      const height = Math.ceil(el.getBoundingClientRect().height)
      // The body's own size does not depend on its padding (it fills the page),
      // so this cannot feed back on itself.
      const room = body.getBoundingClientRect().height - height >= ROOM_BELOW_PX
      el.toggleAttribute('data-no-room', !room)
      body.style.setProperty(OVERVIEW_HEIGHT_VAR, room ? `${height}px` : '0px')
    }
    apply()
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(apply)
    observer?.observe(el)
    observer?.observe(body)
    return () => {
      observer?.disconnect()
      body.style.removeProperty(OVERVIEW_HEIGHT_VAR)
    }
  }, [empty])

  if (empty) return null
  const shown = lines.slice(0, ROW_CAP)
  const more = lines.length - shown.length

  return (
    <section ref={ref} data-c="overview" aria-label="Right now">
      {shown.length > 0 && (
        <div data-c="callout">
          <h2>Right now</h2>
          <p>
            {shown.map(line => (
              <span key={line.id} data-c="line" data-waiting={line.waiting || undefined}>
                <strong>{line.name}</strong> {line.text}.
              </span>
            ))}
            {more > 0 && <span data-c="line">And {more} more.</span>}
          </p>
        </div>
      )}
      {segments.length > 0 && (
        <div data-c="state-bar" role="img" aria-label={`Coroutine states: ${segmentsLabel(segments)}`}>
          {segments.map(s => (
            <span key={s.key} style={{ flexGrow: s.count, background: s.color }} />
          ))}
        </div>
      )}
    </section>
  )
}

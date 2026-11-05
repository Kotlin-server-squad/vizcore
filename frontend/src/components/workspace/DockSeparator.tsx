import { useEffect, useRef, type KeyboardEvent, type PointerEvent } from 'react'
import { DOCK_MIN_PX, DOCK_STEP_PX } from '@/lib/dock-layout'

interface DockSeparatorProps {
  /** The id of the pane it sizes: the dock. */
  controls: string
  /** The dock's current height in px. */
  height: number
  /** The largest the dock may be; Infinity until the page has been measured. */
  maxHeight: number
  folded: boolean
  onResize: (height: number) => void
  onToggleFold: () => void
}

/**
 * The handle between the main view and the dock (spec S-1, S-14): a focusable
 * `separator` following the WAI-ARIA window-splitter pattern, with the dock as
 * the pane it sizes. Up makes the dock taller, down smaller; Home gives the
 * dock its smallest size and End its largest; Enter folds or unfolds it, and a
 * folded dock reads as collapsed (0). Dragging follows the pointer.
 */
export function DockSeparator({ controls, height, maxHeight, folded, onResize, onToggleFold }: DockSeparatorProps) {
  const drag = useRef<{ startY: number; startHeight: number } | null>(null)
  const detach = useRef<(() => void) | null>(null)
  useEffect(() => () => detach.current?.(), [])

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = { ArrowUp: DOCK_STEP_PX, ArrowDown: -DOCK_STEP_PX, PageUp: 4 * DOCK_STEP_PX, PageDown: -4 * DOCK_STEP_PX }[
      e.key
    ]
    if (step !== undefined) {
      e.preventDefault()
      onResize(height + step)
    } else if (e.key === 'Home') {
      e.preventDefault()
      onResize(DOCK_MIN_PX)
    } else if (e.key === 'End' && Number.isFinite(maxHeight)) {
      e.preventDefault()
      onResize(maxHeight)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      onToggleFold()
    }
  }

  // Moves are read on the window, so a fast drag that leaves the thin handle
  // keeps resizing until the button is released. Only the pointer that started
  // the drag counts, and a cancelled gesture ends it like a release.
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return
    e.preventDefault()
    detach.current?.()
    const pointerId = e.pointerId
    drag.current = { startY: e.clientY, startHeight: height }
    const onMove = (m: globalThis.PointerEvent) => {
      const d = drag.current
      if (d && m.pointerId === pointerId) onResize(d.startHeight + (d.startY - m.clientY))
    }
    const onEnd = (m: globalThis.PointerEvent) => {
      if (m.pointerId === pointerId) detach.current?.()
    }
    detach.current = () => {
      drag.current = null
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onEnd)
      window.removeEventListener('pointercancel', onEnd)
      detach.current = null
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onEnd)
    window.addEventListener('pointercancel', onEnd)
  }

  return (
    <div
      role="separator"
      aria-orientation="horizontal"
      aria-label="Resize the dock"
      aria-controls={controls}
      // Folded is the collapsed position; unfolded, the dock is at least DOCK_MIN_PX.
      aria-valuenow={folded ? 0 : height}
      aria-valuemin={0}
      aria-valuemax={Number.isFinite(maxHeight) ? maxHeight : undefined}
      aria-valuetext={folded ? 'Dock folded' : `Dock ${height} pixels tall`}
      tabIndex={0}
      onKeyDown={onKeyDown}
      onPointerDown={onPointerDown}
      className="group flex h-3 shrink-0 cursor-row-resize touch-none items-center justify-center rounded-medium outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <span
        aria-hidden="true"
        className="h-1 w-12 rounded-full bg-default-300 transition-colors group-hover:bg-primary group-focus-visible:bg-primary"
      />
    </div>
  )
}
